import os
from pathlib import Path

from dotenv import dotenv_values
from pydantic import BaseModel, Field, model_validator
from urllib.parse import urlsplit

ENV_FILE = Path(__file__).resolve().parents[1] / '.env'


class Settings(BaseModel):
    api_key: str = ''
    base_url: str = ''
    model: str = ''
    timeout_seconds: float = Field(default=10, gt=0, le=120)
    service_api_key: str = ''

    @model_validator(mode='after')
    def check_url(self):
        if self.base_url:
            url = urlsplit(self.base_url)
            if (url.scheme not in {'http', 'https'} or not url.hostname
                    or url.username or url.password or url.query or url.fragment):
                raise ValueError('LLM_BASE_URL 必须是无凭据、查询参数和片段的 HTTP(S) API 前缀')
        return self

    @property
    def configured(self) -> bool:
        return bool(self.api_key and self.base_url and self.model)

    @classmethod
    def from_env(cls):
        values = {**dotenv_values(ENV_FILE), **os.environ}
        mapping = {'api_key': 'LLM_API_KEY', 'base_url': 'LLM_BASE_URL',
                   'model': 'LLM_MODEL', 'timeout_seconds': 'LLM_TIMEOUT_SECONDS',
                   'service_api_key': 'SERVICE_API_KEY'}
        return cls(**{key: values[name].strip() for key, name in mapping.items()
                      if values.get(name) is not None})
