from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator

Identifier = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=128)]
# 保留 FINAL 原文，不在校验阶段 strip 或拆词。
Text = Annotated[str, StringConstraints(strict=True, min_length=1, max_length=2000)]
Language = Annotated[str, StringConstraints(strict=True, pattern=r'^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?$')]
Revision = Annotated[int, Field(strict=True, ge=0, le=2147483647)]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class ContextSentence(ContractModel):
    segmentId: Identifier
    rawChinese: Text

    @field_validator('rawChinese', 'segmentId')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('must not be blank')
        return value


class PolishRequest(ContextSentence):
    sessionId: Identifier
    revision: Revision
    targetLanguages: list[Language] = Field(default_factory=lambda: ['zh-CN'], min_length=1, max_length=8)
    context: list[ContextSentence] = Field(default_factory=list, max_length=5)

    @field_validator('sessionId')
    @classmethod
    def uuid_session(cls, value):
        UUID(value)
        return value

    @field_validator('targetLanguages')
    @classmethod
    def unique_languages(cls, value):
        if len(set(value)) != len(value):
            raise ValueError('duplicate target languages')
        return value


class FidelityIssue(ContractModel):
    language: Language
    code: Literal['AMBIGUITY', 'FIDELITY_CHECK_FAILED', 'UNAVAILABLE']
    message: Annotated[str, StringConstraints(strict=True, strip_whitespace=True, min_length=1, max_length=300)]


class ModelOutput(ContractModel):
    polishedChinese: Text | None
    translations: dict[Language, Text]
    issues: list[FidelityIssue] = Field(max_length=32)

    @field_validator('polishedChinese', 'translations')
    @classmethod
    def nonblank_output(cls, value):
        values = value.values() if isinstance(value, dict) else [value]
        if any(item is not None and not item.strip() for item in values):
            raise ValueError('output must not be blank')
        return value


class PolishResponse(ModelOutput):
    segmentId: Identifier
    revision: Revision


SignWord = Literal['一定', '你', '只', '可以', '回', '大家', '好', '家', '年', '很久不',
                   '我', '我们', '新', '是', '照顾', '祝贺', '自己', '要', '见']
DemoSentence = Literal['我想回家', '你一定可以', '你要照顾好自己', '祝大家新年好', '我们只是好久不见']


class SignCandidate(ContractModel):
    label: SignWord
    score: float | None = Field(default=None, ge=0, le=1, description='未校准的 CV softmax 分数，不是可靠概率')


class SignGesture(ContractModel):
    candidates: list[SignCandidate] = Field(min_length=1, max_length=3)

    @field_validator('candidates')
    @classmethod
    def unique_candidates(cls, value):
        if len({item.label for item in value}) != len(value):
            raise ValueError('duplicate candidate labels')
        return value


class SignComposeRequest(ContractModel):
    sessionId: Identifier
    segmentId: Identifier
    revision: Revision
    gestures: list[SignGesture] = Field(min_length=1, max_length=12)

    @field_validator('sessionId')
    @classmethod
    def uuid_session(cls, value):
        UUID(value)
        return value

    @field_validator('segmentId')
    @classmethod
    def nonblank_segment(cls, value):
        if not value.strip():
            raise ValueError('must not be blank')
        return value


class SignComposeModelOutput(ContractModel):
    sentence: DemoSentence | None


class SignComposeResponse(ContractModel):
    segmentId: Identifier
    revision: Revision
    sentence: DemoSentence | None
    alternatives: list[DemoSentence]
    status: Literal['CANDIDATE', 'AMBIGUOUS', 'INSUFFICIENT_EVIDENCE']
    needsConfirmation: Literal[True] = True


class ErrorDetail(ContractModel):
    code: str
    message: str
    retryable: bool


class ErrorResponse(ContractModel):
    segmentId: Identifier | None = None
    revision: Revision | None = None
    error: ErrorDetail


class HealthResponse(BaseModel):
    status: Literal['ok'] = 'ok'
    model_configured: bool
