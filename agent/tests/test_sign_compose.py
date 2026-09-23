import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


PATH = '/v1/compose-signs'
BASE = {'sessionId': '12345678-1234-1234-1234-123456789abc',
        'segmentId': 'seg-sign-1', 'revision': 1}


def body(*words):
    return {**BASE, 'gestures': [{'candidates': [{'label': word, 'score': 0.8}]}
                                for word in words]}


def upstream(sentence):
    return httpx.Response(200, json={'choices': [{'message': {
        'content': json.dumps({'sentence': sentence}, ensure_ascii=False)}}]})


def client_for(handler, **settings):
    return TestClient(create_app(Settings(api_key='test-key', base_url='https://model.example/v1',
                                          model='test-model', **settings), httpx.MockTransport(handler)))


@pytest.mark.parametrize('words,sentence', [
    (('我', '回', '家'), '我想回家'),
    (('你', '一定', '可以'), '你一定可以'),
    (('你', '要', '照顾', '好', '自己'), '你要照顾好自己'),
    (('祝贺', '大家', '新', '年', '好'), '祝大家新年好'),
    (('我们', '只', '是', '很久不', '见'), '我们只是好久不见'),
])
def test_five_demo_sentences(words, sentence):
    def handler(request):
        payload = json.loads(request.content)
        assert payload['model'] == 'test-model'
        assert payload['enable_thinking'] is False
        assert payload['max_tokens'] == 64
        user = json.loads(payload['messages'][1]['content'])
        assert sentence in user['allowedSentences']
        assert len(user['gestures']) == len(words)
        return upstream(sentence)
    with client_for(handler) as client:
        response = client.post(PATH, json=body(*words))
        assert response.status_code == 200
        assert response.json()['sentence'] == sentence
        assert response.json()['status'] == 'CANDIDATE'
        assert response.json()['needsConfirmation'] is True
        assert response.json()['segmentId'] == BASE['segmentId']


def test_ranked_candidates_and_ambiguous_result():
    request = body('你', '一定', '可以')
    request['gestures'][1]['candidates'] = [
        {'label': '要', 'score': 0.5}, {'label': '一定', 'score': 0.4},
        {'label': '好', 'score': 0.1}]
    with client_for(lambda r: upstream(None)) as client:
        result = client.post(PATH, json=request).json()
        assert result['sentence'] is None
        assert result['status'] == 'AMBIGUOUS'
        assert '你一定可以' in result['alternatives']


def test_two_third_rank_candidates_do_not_create_spurious_sentence():
    request = {'gestures': [
        {'candidates': [{'label': '我'}, {'label': '照顾'}, {'label': '新'}]},
        {'candidates': [{'label': '回'}, {'label': '你'}, {'label': '好'}]},
        {'candidates': [{'label': '家'}, {'label': '见'}, {'label': '很久不'}]},
    ], **BASE}
    with client_for(lambda r: upstream('我想回家')) as client:
        result = client.post(PATH, json=request).json()
        assert result['alternatives'] == ['我想回家']


def test_insufficient_evidence_skips_model():
    def forbidden(request):
        raise AssertionError('no model call expected')
    with client_for(forbidden) as client:
        result = client.post(PATH, json=body('你')).json()
        assert result['status'] == 'INSUFFICIENT_EVIDENCE'
        assert result['sentence'] is None
        assert result['alternatives'] == []


@pytest.mark.parametrize('invalid', [
    {'gestures': []},
    body('未知'),
    {**BASE, 'gestures': [{'candidates': []}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你'}] * 2}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你', 'score': 1.2}]}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你', 'score': 0.5}], 'extra': 1}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你'}]}] * 13},
])
def test_invalid_input(invalid):
    with client_for(lambda r: upstream(None)) as client:
        assert client.post(PATH, json={**BASE, **invalid}).status_code == 422


def test_model_cannot_choose_unsupported_sentence():
    with client_for(lambda r: upstream('祝大家新年好')) as client:
        result = client.post(PATH, json=body('我', '回', '家'))
        assert result.status_code == 502
        assert result.json()['error']['code'] == 'MODEL_INVALID_RESPONSE'


def test_authorization_and_openapi():
    with client_for(lambda r: upstream('我想回家'), service_api_key='secret-token') as client:
        assert client.post(PATH, json=body('我', '回', '家')).status_code == 401
        response = client.post(PATH, json=body('我', '回', '家'),
                               headers={'Authorization': 'Bearer secret-token'})
        assert response.status_code == 200
        assert PATH in client.get('/openapi.json').json()['paths']
