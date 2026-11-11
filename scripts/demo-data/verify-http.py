"""Verify the real local application's fixtures, pagination, filters and login. No email."""
import hashlib
import http.cookiejar
import json
import re
import sys
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = json.loads(Path(__file__).with_name('catalog.json').read_text(encoding='utf-8'))
BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8180'
if urllib.parse.urlparse(BASE).hostname not in ('127.0.0.1','localhost'):
    raise ValueError('Only the local application may receive synthetic credentials')
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
checks = 0
MARKERS = re.compile(r'合成测试|测试样本|·样本|流程样本|【测试|模拟设定|流程说明|虚构|测试区间')

def natural_content(value):
    assert not MARKERS.search(value), 'A fixture marker is visible in user-facing text'

def call(path, body=None, method=None):
    global checks
    headers = {}
    if body is not None:
        csrf = call('/auth/csrf')
        headers[csrf['headerName']] = csrf['token']
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(BASE+'/api/v1'+path,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method)
    with opener.open(request,timeout=20) as response:
        raw = response.read()
        checks += 1
        return json.loads(raw) if raw else None

def fixture_id(key):
    return str(uuid.UUID(bytes=hashlib.md5((DATA['batch']+':'+key).encode()).digest(), version=3))

def all_pages(path, filters=None):
    query = dict(filters or {})
    items, seen = [], set()
    while True:
        page = call(path + ('?'+urllib.parse.urlencode(query) if query else ''))
        assert len(page['items']) <= 20
        for item in page['items']:
            assert item['id'] not in seen, 'Pagination repeated a row'
            seen.add(item['id'])
            items.append(item)
        if not page.get('nextCursor'):
            return items
        query['cursor'] = page['nextCursor']

public = all_pages('/public/requests')
actual = {item['id']:item for item in public}
expected = {fixture_id(row['key']):row for row in DATA['requests'] if row['status']=='OPEN'}
assert expected.keys() <= actual.keys(), 'Some public fixtures are missing'
for rid,row in expected.items():
    result = actual[rid]
    assert result['title'] == row['fields']['title']
    assert result['publisherDisplayName'] == row['publisherDisplayName']
    natural_content(result['title'])
    natural_content(result['publisherDisplayName'])
    natural_content(result['goal'])
    assert result['locationText'].startswith('深圳市坪山区')
    assert len(result['locationText']) > len('深圳市坪山区') + 2
    assert not {'email','password','publisherDeclaration','phone','wechat'} & result.keys()
    detail = call('/public/requests/'+rid)
    for key,value in row['fields'].items():
        assert detail[key] == value, f'Public field mismatch: {key}'
hidden = {fixture_id(row['key']) for row in DATA['requests'] if row['status']!='OPEN'}
assert not hidden & actual.keys(), 'A workflow fixture leaked into public results'

categories = ['EXTRACURRICULAR_STUDY','DRAWING','DANCE','OTHER_SKILL']
for category in categories:
    items = all_pages('/public/requests',dict(categoryCode=category))
    assert all(item['categoryCode']==category for item in items)
    wanted = {rid for rid,row in expected.items() if row['fields']['categoryCode']==category}
    assert wanted <= {item['id'] for item in items}
for mode in ['ONLINE','OFFLINE']:
    items = all_pages('/public/requests',dict(serviceMode=mode))
    assert all(item['serviceMode']==mode for item in items)
for area in sorted({row['fields']['areaCode'] for row in expected.values()}):
    items = all_pages('/public/requests',dict(areaCode=area))
    assert all(item['areaCode']==area for item in items)

credentials = json.loads((ROOT/'.tools/demo-data/credentials.local.json').read_text(encoding='utf-8'))
def login(account):
    credential = next(item for item in credentials if item['account']==account)
    result = call('/auth/login',dict(account=account,password=credential['password']))
    assert result['businessIdentity']==credential['identity']
    return result

try:
    login('lk_demo_r001')
    teachers = all_pages('/teachers')
    actual_teachers = {item['id']:item for item in teachers}
    wanted_teachers = {fixture_id(row['account']):row for row in DATA['accounts'] if row['identity']=='TUTOR'}
    assert wanted_teachers.keys() <= actual_teachers.keys()
    for tid,row in wanted_teachers.items():
        detail = call('/teachers/'+tid)
        assert detail['displayName']==row['displayName']
        natural_content(detail['displayName'])
        natural_content(detail['introduction'])
        for key in ['categoryCodes','serviceModes','areaCodes','introduction','availabilityText']:
            assert detail[key]==row['teacher'][key]
        assert not {'email','password','phone','wechat'} & detail.keys()
    for category in categories:
        items = all_pages('/teachers',dict(categoryCode=category))
        assert all(category in item['categoryCodes'] for item in items)
        assert {tid for tid,row in wanted_teachers.items() if category in row['teacher']['categoryCodes']} <= {item['id'] for item in items}
    for mode in ['ONLINE','OFFLINE']:
        assert all(mode in item['serviceModes'] for item in all_pages('/teachers',dict(serviceMode=mode)))
    for area in sorted({area for row in wanted_teachers.values() for area in row['teacher']['areaCodes']}):
        assert all(area in item['areaCodes'] for item in all_pages('/teachers',dict(areaCode=area)))
    call('/auth/logout',{},'POST')
    login('lk_demo_t001')
    profile = call('/me/profile')
    assert profile['teacher']['public'] is True
    call('/auth/logout',{},'POST')
finally:
    try: call('/auth/logout',{},'POST')
    except Exception: pass
report = dict(result='PASS',httpRequests=checks,publicSeedRequests=len(expected),publicSeedTeachers=len(wanted_teachers),hiddenWorkflowRequests=len(hidden),allFourCategories=True,allSixAreas=True,bothServiceModes=True,loginBothRoles=True,noVisibleFixtureMarkers=True,neighborhoodLocations=len({row['fields']['locationText'] for row in expected.values()}))
(ROOT/'.tools/demo-data/http-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
