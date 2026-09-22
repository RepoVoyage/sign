import asyncio
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

PATH = '/v1/polish'
BODY = {'sessionId': '12345678-1234-1234-1234-123456789abc', 'segmentId': 'seg-1',
        'revision': 3, 'rawChinese': '我 需要 帮助', 'targetLanguages': ['en-US'], 'context': []}
OUTPUT = {'polishedChinese': '我需要帮助。', 'translations': {'en-US': 'I need help.'}, 'issues': []}


def upstream(output):
    return httpx.Response(200, json={'choices': [{'message': {'content': json.dumps(output, ensure_ascii=False)}}]})


def client_for(handler=None, **overrides):
    settings = Settings(api_key='test-key', base_url='https://model.example/v1/', model='test', **overrides)
    return TestClient(create_app(settings, httpx.MockTransport(handler or (lambda request: upstream(OUTPUT)))))


def test_protocol_and_context():
    body = {**BODY, 'context': [{'segmentId': 'previous', 'rawChinese': '已确认 原文'}]}
    def handler(request):
        assert str(request.url) == 'https://model.example/v1/chat/completions'
        assert request.headers['Authorization'] == 'Bearer test-key'
        payload = json.loads(request.content)
        assert json.loads(payload['messages'][1]['content']) == {
            key: body[key] for key in ('rawChinese', 'targetLanguages', 'context')}
        return upstream(OUTPUT)
    with client_for(handler) as client:
        response = client.post(PATH, json=body)
        assert response.status_code == 200
        assert response.json() == {'segmentId': 'seg-1', 'revision': 3, **OUTPUT}


@pytest.mark.parametrize('change', [
    {'rawChinese': ''}, {'rawChinese': ' '}, {'rawChinese': ['我']}, {'rawChinese': 'a'*2001},
    {'revision': -1}, {'revision': '1'}, {'revision': True}, {'sessionId': 'bad'},
    {'segmentId': ' '}, {'words': ['我']}, {'targetLanguages': []},
    {'targetLanguages': ['en-US', 'en-US']}, {'targetLanguages': ['en_US']},
    {'context': [{'segmentId': 'x', 'rawChinese': ' '}]},
    {'context': [{'segmentId': 'x', 'rawChinese': '我'}] * 6},
])
def test_invalid_input(change):
    with client_for() as client:
        response = client.post(PATH, json={**BODY, **change})
        assert response.status_code == 422
        assert response.json()['error']['code'] == 'INVALID_REQUEST'
        assert response.json()['segmentId'] is None


def test_defaults_and_zero_revision():
    with client_for(lambda r: upstream({**OUTPUT, 'translations': {}})) as client:
        body = {k: v for k, v in BODY.items() if k not in {'targetLanguages', 'context'}}
        response = client.post(PATH, json={**body, 'revision': 0})
        assert response.status_code == 200
        assert response.json()['translations'] == {}
        assert response.json()['revision'] == 0


def test_invalid_json():
    with client_for() as client:
        assert client.post(PATH, content='{', headers={'Content-Type': 'application/json'}).status_code == 422


@pytest.mark.parametrize('status,expected,code,retryable', [
    (401, 502, 'MODEL_AUTH_FAILED', False), (403, 502, 'MODEL_AUTH_FAILED', False),
    (429, 503, 'MODEL_RATE_LIMITED', True), (500, 502, 'MODEL_UPSTREAM_ERROR', True),
    (400, 502, 'MODEL_UPSTREAM_ERROR', False), (302, 502, 'MODEL_UPSTREAM_ERROR', False),
])
def test_upstream_errors(status, expected, code, retryable):
    with client_for(lambda r: httpx.Response(status, text='private provider detail')) as client:
        response = client.post(PATH, json=BODY)
        assert response.status_code == expected
        assert response.json()['error']['code'] == code
        assert response.json()['error']['retryable'] == retryable
        assert response.json()['segmentId'] == BODY['segmentId']
        assert 'private provider' not in response.text


@pytest.mark.parametrize('exception,status,code', [
    (httpx.ReadTimeout, 504, 'MODEL_TIMEOUT'), (httpx.ConnectError, 502, 'MODEL_UNAVAILABLE'),
])
def test_network_errors(exception, status, code):
    def handler(request):
        raise exception('secret diagnostic', request=request)
    with client_for(handler) as client:
        response = client.post(PATH, json=BODY)
        assert response.status_code == status
        assert response.json()['error']['code'] == code


def test_remaining_budget():
    async def handler(request):
        await asyncio.sleep(0.1)
        return upstream(OUTPUT)
    with client_for(handler) as client:
        response = client.post(PATH, json=BODY, headers={'X-Remaining-Budget-Ms': '10'})
        assert response.status_code == 504
        assert response.json()['error']['code'] == 'MODEL_TIMEOUT'


def test_cloud_cap_even_with_old_env():
    def handler(request):
        assert request.extensions['timeout']['read'] == 10
        return upstream(OUTPUT)
    with client_for(handler, timeout_seconds=30) as client:
        assert client.post(PATH, json=BODY).status_code == 200


@pytest.mark.parametrize('budget', ['0', '-1', '10001', 'abc'])
def test_invalid_budget(budget):
    with client_for() as client:
        assert client.post(PATH, json=BODY, headers={'X-Remaining-Budget-Ms': budget}).status_code == 422


@pytest.mark.parametrize('output', [
    {}, [], {**OUTPUT, 'extra': True}, {**OUTPUT, 'polishedChinese': ' '},
    {**OUTPUT, 'translations': {'en-US': ''}},
    {**OUTPUT, 'translations': {'ja-JP': '助けてください'}},
    {**OUTPUT, 'issues': [{'language': 'en-US', 'code': 'BAD', 'message': 'x'}]},
    {**OUTPUT, 'issues': [{'language': 'fr-FR', 'code': 'AMBIGUITY', 'message': 'x'}]},
])
def test_invalid_model_output(output):
    with client_for(lambda r: upstream(output)) as client:
        response = client.post(PATH, json=BODY)
        assert response.status_code == 502
        assert response.json()['error']['code'] == 'MODEL_INVALID_RESPONSE'


@pytest.mark.parametrize('raw,chinese', [('我 不 买', '我买。'), ('买 2 个', '买3个。'),
    ('我 买', '我不买。'), ('买 两个', '买三个。'), ('帮助 帮助', '帮助。')])
def test_chinese_fidelity(raw, chinese):
    result = {**OUTPUT, 'polishedChinese': chinese, 'translations': {}}
    with client_for(lambda r: upstream(result)) as client:
        response = client.post(PATH, json={**BODY, 'rawChinese': raw, 'targetLanguages': ['zh-CN']})
        assert response.status_code == 200
        assert any(i['code'] == 'FIDELITY_CHECK_FAILED' and i['language'] == 'zh-CN' for i in response.json()['issues'])


def test_partial_languages_keep_completed():
    with client_for() as client:
        response = client.post(PATH, json={**BODY, 'targetLanguages': ['en-US', 'ja-JP']}).json()
        assert response['translations'] == OUTPUT['translations']
        assert response['issues'] == [{'language': 'ja-JP', 'code': 'UNAVAILABLE', 'message': '该语言翻译未完成。'}]


def test_chinese_only_no_translations():
    with client_for(lambda r: upstream({**OUTPUT, 'translations': {}})) as client:
        assert client.post(PATH, json={**BODY, 'targetLanguages': ['zh-CN']}).json()['translations'] == {}
    with client_for() as client:
        assert client.post(PATH, json={**BODY, 'targetLanguages': ['zh-CN']}).status_code == 502


def test_no_chinese_disguised_as_translation():
    with client_for(lambda r: upstream({**OUTPUT, 'translations': {'en-US': '我需要帮助。'}})) as client:
        response = client.post(PATH, json=BODY).json()
        assert response['translations'] == {}
        assert response['issues'][0]['code'] == 'UNAVAILABLE'


def test_ambiguity_and_unavailable():
    output = {**OUTPUT, 'issues': [
        {'language': 'zh-CN', 'code': 'AMBIGUITY', 'message': '请核对原意'},
        {'language': 'en-US', 'code': 'UNAVAILABLE', 'message': '未完成'}]}
    with client_for(lambda r: upstream(output)) as client:
        response = client.post(PATH, json=BODY).json()
        assert response['polishedChinese'] == OUTPUT['polishedChinese']
        assert response['translations'] == {}
        assert response['issues'] == output['issues']


def test_repeated_issues_and_missing_languages():
    issue = {'language': 'zh-CN', 'code': 'AMBIGUITY', 'message': '核对'}
    output = {'polishedChinese': None, 'translations': {}, 'issues': [issue] * 32}
    with client_for(lambda r: upstream(output)) as client:
        response = client.post(PATH, json=BODY)
        assert response.status_code == 200
        assert len(response.json()['issues']) == 3


def test_invalid_raw_model_text():
    with client_for(lambda r: httpx.Response(200, json={'choices': [{'message': {'content': 'not json'}}]})) as client:
        assert client.post(PATH, json=BODY).json()['error']['code'] == 'MODEL_INVALID_RESPONSE'


def test_translation_numbers_flag_only_affected_language():
    output = {'polishedChinese': '买2个。', 'translations': {'en-US': 'Buy 3.'}, 'issues': []}
    with client_for(lambda r: upstream(output)) as client:
        response = client.post(PATH, json={**BODY, 'rawChinese': '买 2 个'}).json()
        assert response['issues'][0]['language'] == 'en-US'
        assert response['issues'][0]['code'] == 'FIDELITY_CHECK_FAILED'


def test_missing_configuration_and_health():
    with TestClient(create_app(Settings())) as client:
        assert client.get('/health').json() == {'status': 'ok', 'model_configured': False}
        assert client.post(PATH, json=BODY).json()['error']['code'] == 'MODEL_NOT_CONFIGURED'


def test_openapi_and_removed_old_route():
    with client_for() as client:
        paths = client.get('/openapi.json').json()['paths']
        assert '/api/v1/compose' not in paths
        assert set(paths[PATH]['post']['responses']) == {'200', '401', '422', '500', '502', '503', '504'}
        assert client.post('/api/v1/compose', json=BODY).status_code == 404


def test_env_precedence(tmp_path, monkeypatch):
    import app.config as config
    path = tmp_path / '.env'
    path.write_text('LLM_API_KEY=file-key\nLLM_BASE_URL=https://model.example/v1\nLLM_MODEL=file-model\n', encoding='utf-8')
    monkeypatch.setattr(config, 'ENV_FILE', path)
    for name in ('LLM_BASE_URL', 'LLM_MODEL', 'LLM_TIMEOUT_SECONDS', 'SERVICE_API_KEY'):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv('LLM_API_KEY', 'environment-key')
    settings = Settings.from_env()
    assert settings.api_key == 'environment-key'
    assert settings.model == 'file-model'
    assert settings.timeout_seconds == 10


def test_authentication():
    with client_for(service_api_key='test-service-token') as client:
        assert client.get('/health').status_code == 200
        for headers in ({}, {'Authorization': 'Bearer wrong'}, {'Authorization': 'Basic test-service-token'}):
            response = client.post(PATH, json=BODY, headers=headers)
            assert response.status_code == 401
            assert response.json()['error']['code'] == 'UNAUTHORIZED'
        assert client.post(PATH, json=BODY, headers={'Authorization': 'Bearer test-service-token'}).status_code == 200


def test_production_schema_contains_no_fixed_example():
    with client_for() as client:
        schema = client.get('/openapi.json').json()['components']['schemas']['PolishRequest']
        assert 'example' not in schema
