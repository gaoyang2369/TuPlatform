import {test,expect} from '@playwright/test';
import type {Page} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {readdirSync,mkdirSync} from 'node:fs';
import path from 'node:path';
test.setTimeout(120000);

const root=path.resolve(import.meta.dirname,'../..');
const driverRoot=path.join(root,'.tools/maven-repository/org/postgresql/postgresql');
const version=readdirSync(driverRoot).filter(v=>/^\d/.test(v)).sort().at(-1)!;
const driver=path.join(driverRoot,version,`postgresql-${version}.jar`);
type User={id:string;account:string;password:string};const actors:User[]=[];
function fixture(action:string,value:string){return JSON.parse(execFileSync('java',['--class-path',driver,path.join(root,'backend/src/test/tools/BrowserFixture.java'),action,value],{encoding:'utf8',env:process.env}));}
function user(role:string):User{const u=fixture('create',role);actors.push(u);return u;}
async function login(page:Page,u:User){await page.goto('/');await page.getByLabel('账号',{exact:true}).fill(u.account);await page.getByLabel('密码',{exact:true}).fill(u.password);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('heading',{name:/本人基本资料|让合适的人认识你/})).toBeVisible();}
async function write(page:Page,u:User,url:string,body:unknown,method='PUT',key=crypto.randomUUID()){
  const csrf=await(await page.request.get('/api/v1/auth/csrf')).json();
  return page.request.fetch('/api/v1'+url,{method,headers:{[csrf.headerName]:csrf.token,'X-Account-Id':u.id,'Idempotency-Key':key},data:body});
}
async function saveContacts(page:Page,phone:string,wechat:string){
  await page.getByRole('button',{name:'私密联系方式',exact:true}).click();await expect(page.getByRole('heading',{name:'本人私密联系方式'})).toBeVisible();
  await page.getByLabel('手机号（选填，+86）').fill(phone);await page.getByLabel('微信号（选填）').fill(wechat);
  await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await expect(page.getByRole('status')).toContainText('私密联系方式已保存');
}
async function prepare(ownerPage:Page,tutorPage:Page,operatorPage:Page,ui=false){
  const owner=user('REQUESTER'),tutor=user('TUTOR'),operator=user('OPERATOR');
  await login(ownerPage,owner);await login(tutorPage,tutor);await login(operatorPage,operator);
  await ownerPage.locator('#field-displayName').fill('合成需求方');await ownerPage.getByRole('button',{name:'保存资料',exact:true}).click();await expect(ownerPage.getByRole('status')).toContainText('资料已保存');
  await tutorPage.locator('#field-displayName').fill('合成绘画老师');await tutorPage.getByRole('checkbox',{name:'绘画',exact:true}).check();await tutorPage.getByRole('checkbox',{name:'线上',exact:true}).check();await tutorPage.locator('#field-introduction').fill('合成老师自述');
  await tutorPage.getByRole('button',{name:'保存资料',exact:true}).click();await expect(tutorPage.getByRole('status')).toContainText('资料已保存');await tutorPage.getByRole('button',{name:'预览并公开'}).click();await tutorPage.getByRole('button',{name:'确认公开资料'}).click();await expect(tutorPage.getByText('资料已公开',{exact:true})).toBeVisible();
  let request:string;
  if(ui){
    await ownerPage.getByRole('button',{name:'我的需求',exact:true}).click();await ownerPage.getByRole('button',{name:'新建需求草稿'}).click();
    await ownerPage.getByLabel('需求标题',{exact:false}).fill('合成联系方式闭环');await ownerPage.getByLabel('服务类别',{exact:false}).selectOption('DRAWING');await ownerPage.getByLabel('服务对象阶段').selectOption('ADULT');await ownerPage.getByLabel('坪山区街道',{exact:false}).selectOption('PS_PINGSHAN');await ownerPage.getByLabel('目标与现状',{exact:false}).fill('合成绘画目标');
    await ownerPage.getByRole('button',{name:'保存并下一步'}).click();await ownerPage.getByLabel('预算下限').fill('100');await ownerPage.getByLabel('预算上限').fill('200');await ownerPage.getByLabel('单次时长').selectOption('1.5');await ownerPage.getByRole('combobox',{name:'频率',exact:true}).selectOption('NEGOTIABLE');await ownerPage.getByRole('button',{name:'添加时段'}).click();await ownerPage.getByRole('button',{name:'保存并预览'}).click();
    await expect(ownerPage.getByRole('button',{name:'确认提交审核',exact:true})).toBeDisabled();await ownerPage.getByRole('radio',{name:'我为已满18岁的本人发布本条需求。',exact:true}).check();await ownerPage.getByRole('button',{name:'确认提交审核',exact:true}).click();await expect(ownerPage.getByText('待审核',{exact:true})).toBeVisible();
    request=(await(await ownerPage.request.get('/api/v1/me/requests')).json()).items[0].id;
    await operatorPage.getByRole('button',{name:'需求审核',exact:true}).click();await operatorPage.getByRole('button',{name:'核对提交',exact:true}).click();await operatorPage.getByRole('radio',{name:'通过审核并公开',exact:true}).check();await operatorPage.getByRole('button',{name:'核对并确认审核'}).click();await operatorPage.getByRole('button',{name:'确认通过并公开',exact:true}).click();await expect(operatorPage.getByText('已通过',{exact:true})).toBeVisible();
  }else{
    const d=await(await write(ownerPage,owner,'/requests',{},'POST')).json();
    const saved=await(await write(ownerPage,owner,`/requests/${d.id}/draft`,{expectedVersion:d.version,fields:{title:'合成联系方式闭环',categoryCode:'DRAWING',audienceStageCode:'ADULT',areaCode:'PS_PINGSHAN',goal:'合成绘画目标',budgetMin:'100',budgetMax:'200',durationHours:'1.5',frequency:{mode:'NEGOTIABLE'},availableSlots:[{weekday:1,startMinute:600,endMinute:660}]}},'PATCH')).json();
    const submitted=await(await write(ownerPage,owner,`/requests/${d.id}/submit`,{expectedVersion:saved.version,publisherDeclaration:'SELF_ADULT',declarationVersion:'publisher-v1'},'POST')).json();
    expect((await write(operatorPage,operator,`/operations/submissions/${submitted.submissionId}/decision`,{expectedVersion:0,decision:'APPROVE'},'POST')).ok()).toBeTruthy();request=d.id;
  }
  await saveContacts(ownerPage,'13800000001','owner_contact');await saveContacts(tutorPage,'13800000002','tutor_contact');
  return {owner,tutor,request};
}
async function progress(page:Page){await page.getByRole('button',{name:'撮合进度',exact:true}).click();await page.getByRole('button',{name:'查看撮合进度',exact:true}).click();}
async function mutual(ownerPage:Page,tutorPage:Page,invite=false,request?:string){
  if(invite){await ownerPage.getByRole('button',{name:'发现老师',exact:true}).click();await ownerPage.getByRole('button',{name:'查看老师',exact:true}).click();await ownerPage.getByRole('button',{name:'选择需求邀请',exact:true}).click();await ownerPage.getByLabel('选择本人招募中的需求').selectOption(request!);await ownerPage.getByRole('button',{name:'确认邀请',exact:true}).click();await expect(ownerPage.getByRole('heading',{name:'等待对方回应',exact:true})).toBeVisible();await progress(tutorPage);await tutorPage.getByRole('button',{name:'接受意向',exact:true}).click();}
  else{await tutorPage.getByRole('button',{name:'浏览需求',exact:true}).click();await tutorPage.getByRole('button',{name:'查看需求',exact:true}).click();await tutorPage.getByRole('button',{name:'准备投递',exact:true}).click();await tutorPage.getByRole('button',{name:'确认投递',exact:true}).click();await expect(tutorPage.getByRole('heading',{name:'等待对方回应',exact:true})).toBeVisible();await progress(ownerPage);await ownerPage.getByRole('button',{name:'接受意向',exact:true}).click();}
  await expect((invite?tutorPage:ownerPage).getByRole('heading',{name:'已互选',exact:true})).toBeVisible();await (invite?ownerPage:tutorPage).getByRole('button',{name:'读取最新进度',exact:true}).click();
  const m=(await(await ownerPage.request.get('/api/v1/me/matches')).json()).items[0];return m;
}
async function authorize(page:Page,field:'手机号'|'微信',expectSaved=true){
  await page.getByRole('checkbox',{name:`分享本人${field}`,exact:false}).check();await page.getByRole('button',{name:'预览本人分享内容'}).click();await expect(page.getByRole('button',{name:'确认本轮授权',exact:true})).toBeDisabled();await page.getByRole('checkbox',{name:'我已核对，明确同意',exact:false}).check();await page.getByRole('button',{name:'确认本轮授权',exact:true}).click();
  if(expectSaved)await expect(page.getByRole('status')).toContainText('本次授权操作已完成');
}
function hidden(g:Record<string,unknown>){expect(g.canViewContacts).toBe(false);expect(g).not.toHaveProperty('mySharedContacts');expect(g).not.toHaveProperty('otherSharedContacts');}
test.beforeAll(()=>{if(!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/))throw new Error('Dedicated _test database required');mkdirSync(path.join(root,'.tools/stage5-browser'),{recursive:true});});
test.afterEach(()=>{for(const actor of actors.splice(0))fixture('cleanup',actor.id);});

test('完整投递闭环：资料、发布审核、互选、逐项授权、取消与确认撤回',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile,hasTouch:info.project.use.hasTouch}),oc=await browser.newContext({viewport:info.project.use.viewport});
  try{const tp=await tc.newPage(),op=await oc.newPage();let acceptDialog=true;page.on('dialog',d=>acceptDialog?d.accept():d.dismiss());tp.on('dialog',d=>d.accept());
    await prepare(page,tp,op,true);const m=await mutual(page,tp);
    const publicRequest=await(await page.request.get(`/api/v1/public/requests/${m.requestId}`)).json();for(const f of ['phone','wechat','email'])expect(publicRequest).not.toHaveProperty(f);
    await authorize(page,'手机号');hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await expect(page.getByTestId('shared-contacts')).toHaveCount(0);
    await tp.getByRole('button',{name:'读取最新授权',exact:true}).click();await authorize(tp,'微信');await expect(tp.getByTestId('shared-contacts')).toContainText('13800000001');await expect(tp.getByTestId('shared-contacts')).not.toContainText('13800000002');
    await page.getByRole('button',{name:'读取最新授权',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toContainText('tutor_contact');
    for(const [p,role] of [[page,'requester'],[tp,'tutor']] as const){expect(p.viewportSize()).toEqual(info.project.use.viewport);expect(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBeTruthy();await p.screenshot({path:path.join(root,`.tools/stage5-browser/shared-${role}-${info.project.name}.png`),fullPage:true});}
    acceptDialog=false;await page.getByRole('button',{name:'撤回本人联系方式授权',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toBeVisible();
    acceptDialog=true;await page.getByRole('button',{name:'撤回本人联系方式授权',exact:true}).click();await expect(page.getByRole('status')).toContainText('本次授权操作已完成');await expect(page.getByTestId('shared-contacts')).toHaveCount(0);hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
    await tp.getByRole('button',{name:'读取最新授权',exact:true}).click();await expect(tp.getByTestId('shared-contacts')).toHaveCount(0);await expect(tp.getByText('我的授权：已同意 · 对方授权：未有效同意')).toBeVisible();
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('heading',{name:'登录邻课'})).toBeVisible();expect(await page.locator('body').innerText()).not.toContain('tutor_contact');
  }finally{await tc.close();await oc.close();}
});

test('邀请闭环及到期停止公开后授权，字段变更要求双方重确认',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile,hasTouch:info.project.use.hasTouch}),oc=await browser.newContext({viewport:info.project.use.viewport});
  try{const tp=await tc.newPage(),op=await oc.newPage();page.on('dialog',d=>d.accept());tp.on('dialog',d=>d.accept());const data=await prepare(page,tp,op,true);const m=await mutual(page,tp,true,data.request);
    fixture('expire',data.request);const p=await(await tp.request.get('/api/v1/me/profile')).json();expect((await write(tp,data.tutor,'/me/teacher-profile/unpublish',{expectedVersion:p.version},'POST')).ok()).toBeTruthy();
    await authorize(page,'手机号');await tp.getByRole('button',{name:'读取最新授权',exact:true}).click();await authorize(tp,'微信');
    // A field that this owner did not select does not invalidate the relationship.
    expect((await write(page,data.owner,'/me/contacts',{expectedVersion:1,phone:'13800000001',wechat:'unshared_change'})).ok()).toBeTruthy();expect((await(await page.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json()).canViewContacts).toBe(true);
    await saveContacts(page,'13800000003','unshared_change');hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
    await tp.getByRole('button',{name:'读取最新授权',exact:true}).click();await expect(tp.getByTestId('shared-contacts')).toHaveCount(0);await expect(tp.getByText('已选联系方式发生变化，本关系停止展示；双方须分别重新核对并确认。')).toBeVisible();
    await authorize(tp,'微信');hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
    await progress(page);await authorize(page,'手机号');await expect(page.getByTestId('shared-contacts')).toContainText('13800000003');
    await tp.getByRole('button',{name:'读取最新授权',exact:true}).click();await expect(tp.getByTestId('shared-contacts')).toContainText('13800000003');
    await tp.getByRole('button',{name:'撤回本人联系方式授权',exact:true}).click();await expect(tp.getByRole('status')).toContainText('本次授权操作已完成');hidden(await(await page.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
    await page.getByRole('button',{name:'读取最新授权',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);
    await authorize(tp,'微信');await expect(tp.getByTestId('shared-contacts')).toContainText('13800000003');
    await saveContacts(page,'','unshared_change');hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await tp.getByRole('button',{name:'读取最新授权',exact:true}).click();await expect(tp.getByTestId('shared-contacts')).toHaveCount(0);
  }finally{await tc.close();await oc.close();}
});

test('授权响应损坏、成功后读取失败可同键恢复，撤回后旧回执不复活值',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport}),oc=await browser.newContext({viewport:info.project.use.viewport});
  try{const tp=await tc.newPage(),op=await oc.newPage();page.on('dialog',d=>d.accept());tp.on('dialog',d=>d.accept());const data=await prepare(page,tp,op);const m=await mutual(page,tp);
    await authorize(tp,'微信');await page.getByRole('button',{name:'读取最新授权',exact:true}).click();
    const keys:string[]=[];page.on('request',r=>{if(r.method()==='PUT'&&r.url().endsWith('/contact-consent'))keys.push(r.headers()['idempotency-key']);});
    let lost=false;await page.route('**/api/v1/matches/*/contact-consent',async route=>{if(route.request().method()==='PUT'&&!lost){lost=true;await route.fetch();await route.fulfill({status:200,contentType:'application/json',body:'{"version":'});}else await route.continue();});
    await authorize(page,'手机号',false);await expect(page.getByRole('button',{name:'重试同一次授权操作',exact:true})).toBeVisible();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);
    const g=await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json();expect(g.canViewContacts).toBe(true);
    expect((await write(tp,data.tutor,`/matches/${m.id}/contact-consent`,{roundId:m.roundId,expectedGrantVersion:g.version},'DELETE')).ok()).toBeTruthy();
    let readLost=false;await page.route('**/api/v1/matches/*/contact-consent',async route=>{if(route.request().method()==='GET'&&!readLost){readLost=true;await route.abort('failed');}else await route.fallback();});
    await page.getByRole('button',{name:'重试同一次授权操作',exact:true}).click();await expect(page.getByText('本次操作已成功，但最新授权未能刷新。可同键重试，或读取最新授权核对。')).toBeVisible();
    await page.getByRole('button',{name:'重试同一次授权操作',exact:true}).click();await expect(page.getByRole('button',{name:'重试同一次授权操作',exact:true})).toHaveCount(0);await expect(page.getByTestId('shared-contacts')).toHaveCount(0);expect(keys).toHaveLength(3);expect(new Set(keys).size).toBe(1);
    hidden(await(await page.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
  }finally{await tc.close();await oc.close();}
});

test('私密表单失败留输入、旧版核对与越权和换账号清理',async({page,browser,context},info)=>{
  const owner=user('REQUESTER'),outsider=user('REQUESTER');page.on('dialog',d=>d.accept());await login(page,owner);await saveContacts(page,'13800000001','owner_contact');
  const c=await(await page.request.get('/api/v1/me/contacts')).json();expect((await write(page,owner,'/me/contacts',{expectedVersion:c.version,phone:c.phone,wechat:'other_tab'})).ok()).toBeTruthy();
  await page.getByLabel('微信号（选填）').fill('preserved_input');await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await expect(page.getByRole('alert')).toContainText('已变化');await expect(page.getByLabel('微信号（选填）')).toHaveValue('preserved_input');
  await page.getByRole('button',{name:'读取最新联系方式',exact:true}).click();await page.getByRole('button',{name:'已核对，保留输入',exact:true}).click();await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await expect(page.getByRole('status')).toContainText('私密联系方式已保存');
  await page.getByLabel('微信号（选填）').fill('invalid value');await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await expect(page.getByRole('alert')).toContainText('不含空格');await expect(page.getByLabel('微信号（选填）')).toHaveValue('invalid value');
  const ac=await browser.newContext({viewport:info.project.use.viewport});try{const visitor=await ac.newPage();await visitor.goto('/');expect((await visitor.request.get('/api/v1/me/contacts')).status()).toBe(401);}finally{await ac.close();}
  const second=await context.newPage();try{await second.goto('/');await second.getByRole('button',{name:'退出登录',exact:true}).click();await expect(second.getByRole('heading',{name:'登录邻课'})).toBeVisible();await login(second,outsider);
    await page.getByRole('button',{name:'读取最新联系方式',exact:true}).click();await expect(page.getByRole('heading',{name:'登录邻课'})).toBeVisible();expect(await page.locator('body').innerText()).not.toContain('preserved_input');
    expect((await write(second,owner,'/me/contacts',{expectedVersion:0,phone:'13800000009',wechat:null})).status()).toBe(409);
    expect((await(await second.request.get('/api/v1/me/contacts')).json()).phone).toBeNull();
  }finally{await second.close();}
});
