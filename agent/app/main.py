from contextlib import asynccontextmanager
import secrets
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings
from .schemas import ErrorResponse, ErrorDetail, HealthResponse, PolishRequest, PolishResponse
from .service import ServiceError, polish


def error_response(status, code, message, retryable, segment_id=None, revision=None):
    body = ErrorResponse(segmentId=segment_id, revision=revision,
                         error=ErrorDetail(code=code, message=message, retryable=retryable))
    return JSONResponse(status_code=status, content=body.model_dump())


def create_app(settings: Settings | None = None, transport=None) -> FastAPI:
    config = settings if settings is not None else Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(transport=transport, follow_redirects=False) as client:
            app.state.client = client
            yield

    app = FastAPI(title='随心说 Language Processing API', version='2.0.0', lifespan=lifespan)
    bearer = HTTPBearer(auto_error=False)

    async def authorize(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]):
        if config.service_api_key and (credentials is None or not secrets.compare_digest(
            credentials.credentials.encode(), config.service_api_key.encode()
        )):
            raise ServiceError(401, 'UNAUTHORIZED', '请提供有效的服务访问令牌。', False)

    @app.exception_handler(ServiceError)
    async def service_error(request: Request, exc: ServiceError):
        return error_response(exc.http_status, exc.code, exc.message, exc.retryable)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError):
        return error_response(422, 'INVALID_REQUEST', '请检查请求字段类型、格式和长度。', False)

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exc: Exception):
        return error_response(500, 'INTERNAL_ERROR', '服务内部错误。', False)

    @app.get('/health', response_model=HealthResponse)
    async def health():
        return HealthResponse(model_configured=config.configured)

    @app.post('/v1/polish', response_model=PolishResponse, dependencies=[Depends(authorize)],
              responses={code: {'model': ErrorResponse} for code in (401, 422, 500, 502, 503, 504)})
    async def polish_endpoint(
        body: PolishRequest, request: Request,
        x_remaining_budget_ms: Annotated[int, Header(ge=1, le=10000,
            description='可选：客户端发送时剩余预算；手机仍须执行从 FINAL 起算的总期限。')] = 10000,
    ):
        try:
            result = await polish(body, config, request.app.state.client,
                                  min(config.timeout_seconds, x_remaining_budget_ms / 1000, 10))
        except ServiceError as exc:
            return error_response(exc.http_status, exc.code, exc.message, exc.retryable,
                                  body.segmentId, body.revision)
        return PolishResponse(segmentId=body.segmentId, revision=body.revision, **result.model_dump())

    return app


app = create_app()
