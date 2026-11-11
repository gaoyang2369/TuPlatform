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
async function login(page:Page,u:User){await page.goto('/?view=profile');await page.getByLabel('账号',{exact:true}).fill(u.account);await page.getByLabel('密码',{exact:true}).fill(u.password);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('heading',{name:/本人基本资料|让合适的人认识你/})).toBeVisible();}
async function write(page:Page,u:User,url:string,body:unknown,method='PUT',key=crypto.randomUUID()){
  const csrf=await(await page.request.get('/api/v1/auth/csrf')).json();
  return page.request.fetch('/api/v1'+url,{method,headers:{[csrf.headerName]:csrf.token,'X-Account-Id':u.id,'Idempotency-Key':key},data:body});
}
async function saveContacts(page:Page,phone:string,wechat:string){
  await page.getByRole('banner').getByRole('button',{name:'本人资料',exact:true}).click();
  await page.getByRole('button',{name:'私密联系方式',exact:true}).click();await expect(page.getByRole('heading',{name:'本人私密联系方式'})).toBeVisible();
  await page.getByLabel('手机号（选填，+86）').fill(phone);await page.getByLabel('微信号（选填）').fill(wechat);
  await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.locator('.message.success')).toContainText('私密联系方式已保存');
}
async function prepare(ownerPage:Page,tutorPage:Page,operatorPage:Page,ui=false){
  const owner=user('REQUESTER'),tutor=user('TUTOR'),operator=user('OPERATOR');
  await login(ownerPage,owner);await login(tutorPage,tutor);await login(operatorPage,operator);
  await ownerPage.locator('#field-displayName').fill('合成需求方');await ownerPage.getByRole('button',{name:'保存资料',exact:true}).click();await expect(ownerPage.locator('.feedback-toast').getByRole('status')).toContainText('资料已保存');
  await tutorPage.locator('#field-displayName').fill('合成绘画老师');await tutorPage.getByRole('checkbox',{name:'绘画',exact:true}).check();await tutorPage.getByRole('checkbox',{name:'线上',exact:true}).check();await tutorPage.locator('#field-introduction').fill('合成老师自述');
  await tutorPage.getByRole('button',{name:'保存资料',exact:true}).click();await expect(tutorPage.locator('.feedback-toast').getByRole('status')).toContainText('资料已保存');await tutorPage.getByRole('banner').getByRole('button',{name:'本人资料',exact:true}).click();await tutorPage.getByRole('button',{name:'预览并公开'}).click();await tutorPage.getByRole('button',{name:'确认公开资料'}).click();await expect(tutorPage.getByText('资料已公开',{exact:true})).toBeVisible();
  let request:string;
  if(ui){
    await ownerPage.getByRole('button',{name:'我的需求',exact:true}).click();await ownerPage.getByRole('button',{name:'新建需求草稿'}).click();
    await ownerPage.getByLabel('需求标题',{exact:false}).fill('合成联系方式闭环');await ownerPage.getByLabel('服务类别',{exact:false}).selectOption('DRAWING');await ownerPage.getByLabel('服务对象阶段').selectOption('ADULT');await ownerPage.getByLabel('小区或附近地标',{exact:false}).fill('坪山区 · 合成花园');await ownerPage.getByLabel('目标与现状',{exact:false}).fill('合成绘画目标');
    await ownerPage.getByRole('button',{name:'保存并下一步'}).click();await ownerPage.getByLabel('预算下限').fill('100');await ownerPage.getByLabel('预算上限').fill('200');await ownerPage.getByLabel('单次时长（小时）',{exact:true}).fill('1.5');await ownerPage.getByRole('combobox',{name:'频率',exact:true}).selectOption('NEGOTIABLE');await ownerPage.getByRole('radio',{name:'填写精确时间',exact:true}).check();await ownerPage.getByRole('button',{name:'添加时段'}).click();await ownerPage.getByRole('button',{name:'保存并预览'}).click();
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
async function progress(page:Page){await page.getByRole('button',{name:'撮合进度',exact:true}).click();await page.locator('.match-row').getByRole('button').click();}
async function accept(page:Page){await page.getByRole('button',{name:'接受意向',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'接受意向',exact:true}).click();await expect(page.locator('.match-detail .status-pill')).toHaveText('已互选');}
async function refresh(page:Page){await page.getByRole('button',{name:'读取最新进度',exact:true}).click();}
async function agree(page:Page){await page.getByRole('button',{name:'同意交换联系方式',exact:true}).click();}
async function stop(page:Page,cancel=false){await page.getByText('管理我的联系方式',{exact:true}).click();await page.getByRole('button',{name:'停止分享',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:cancel?'取消':'停止分享',exact:true}).click();}
function hidden(g:Record<string,unknown>){expect(g.canViewContacts).toBe(false);expect(g).not.toHaveProperty('mySharedContacts');expect(g).not.toHaveProperty('otherSharedContacts');}
test.beforeAll(()=>{if(!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/))throw new Error('Dedicated _test database required');mkdirSync(path.join(root,'.tools/match-simplification'),{recursive:true});});
test.afterEach(()=>{for(const actor of actors.splice(0))fixture('cleanup',actor.id);});
test('无需发布需求，发送和接受即同意交换，互选前隐藏、互选后直接联系',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile,hasTouch:info.project.use.hasTouch});
  try{
    const tp=await tc.newPage(),owner=user('REQUESTER'),tutor=user('TUTOR');let native=0;for(const p of [page,tp])p.on('dialog',async d=>{native++;await d.dismiss();});
    await login(page,owner);await login(tp,tutor);
    expect((await write(page,owner,'/me/profile',{expectedVersion:0,displayName:'合成直接邀请人'},'PATCH')).ok()).toBe(true);
    const saved=await write(tp,tutor,'/me/teacher-profile',{expectedVersion:0,displayName:'合成直接邀请老师',categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[],introduction:'水彩入门教学',priceMin:null,priceMax:null,availabilityText:'周末下午'});
    expect(saved.ok()).toBe(true);const profile=await saved.json();expect((await write(tp,tutor,'/me/teacher-profile/publish',{expectedVersion:profile.version},'POST')).ok()).toBe(true);
    await saveContacts(page,'','direct_owner');await saveContacts(tp,'','direct_teacher');
    await page.goto('/?view=teachers&teacher='+tutor.id);await page.getByRole('button',{name:'邀请这位老师'}).click();await expect(page.locator('.exchange-notice')).toContainText('direct_owner');await page.locator('#invitation-introduction').fill('想了解周末水彩课');await page.getByRole('button',{name:'发送邀请',exact:true}).click();
    await expect(page.locator('.match-detail .status-pill')).toHaveText('等待对方回应');expect((await(await page.request.get('/api/v1/me/requests')).json()).items).toHaveLength(0);
    const m=(await(await page.request.get('/api/v1/me/matches')).json()).items[0];expect(m.requestId).toBeNull();hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
    await progress(tp);await expect(tp.locator('.person-heading')).toContainText('合成直接邀请人');await expect(tp.locator('.invitation-summary')).toContainText('想了解周末水彩课');await accept(tp);
    await expect(tp.getByTestId('shared-contacts')).toContainText('direct_owner');await refresh(page);await expect(page.getByTestId('shared-contacts')).toContainText('direct_teacher');
    for(const p of [page,tp]){await expect(p.getByRole('button',{name:'同意交换联系方式'})).toHaveCount(0);await expect(p.getByRole('checkbox')).toHaveCount(0);expect(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}expect(native).toBe(0);
    await page.screenshot({path:path.join(root,`.tools/match-simplification/real-mutual-${info.project.name}.png`),fullPage:true});
  }finally{await tc.close();}
});
test('完整投递闭环：发布审核、接受后联系，停止分享可取消和确认',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile,hasTouch:info.project.use.hasTouch}),oc=await browser.newContext({viewport:info.project.use.viewport});
  try{const tp=await tc.newPage(),op=await oc.newPage();await prepare(page,tp,op,true);
    await tp.getByRole('button',{name:'浏览需求',exact:true}).click();await tp.getByRole('button',{name:'查看需求',exact:true}).click();await tp.getByRole('button',{name:'我对这个需求感兴趣',exact:true}).click();await tp.getByRole('button',{name:'确认投递',exact:true}).click();await expect(tp.locator('.match-detail .status-pill')).toHaveText('等待对方回应');
    await progress(page);await accept(page);await expect(page.getByTestId('shared-contacts')).toContainText('tutor_contact');await refresh(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('13800000001');
    const m=(await(await page.request.get('/api/v1/me/matches')).json()).items[0];const publicRequest=await(await page.request.get(`/api/v1/public/requests/${m.requestId}`)).json();for(const f of ['phone','wechat','email'])expect(publicRequest).not.toHaveProperty(f);
    await stop(page,true);await expect(page.getByTestId('shared-contacts')).toBeVisible();await page.getByRole('button',{name:'停止分享',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'停止分享',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);await expect(page.getByRole('button',{name:'同意交换联系方式',exact:true})).toBeEnabled();hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await refresh(tp);await expect(tp.getByTestId('shared-contacts')).toHaveCount(0);
  }finally{await tc.close();await oc.close();}
});
test('旧关系一次同意即可交换，变更需重新确认，丢失响应同键恢复且不复活已撤回值',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport}),oc=await browser.newContext({viewport:info.project.use.viewport});
  try{const tp=await tc.newPage(),op=await oc.newPage(),data=await prepare(page,tp,op);
    const teacher=await(await page.request.get('/api/v1/teachers/'+data.tutor.id)).json();const result=await(await write(page,data.owner,'/matches',{kind:'DIRECT_INVITATION',tutorUserId:data.tutor.id,expectedTeacherProfileVersion:teacher.profileVersion},'POST')).json();const m=result.match;
    expect((await write(tp,data.tutor,`/matches/${m.id}/accept`,{roundId:m.roundId,expectedVersion:m.version},'POST')).ok()).toBe(true);
    await page.goto('/?view=matches&match='+m.id);await tp.goto('/?view=matches&match='+m.id);await agree(tp);hidden(await(await page.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await refresh(page);
    const keys:string[]=[];page.on('request',r=>{if(r.method()==='PUT'&&r.url().endsWith('/contact-consent'))keys.push(r.headers()['idempotency-key']);});let lost=false;
    await page.route('**/api/v1/matches/*/contact-consent',async route=>{if(route.request().method()==='PUT'&&!lost){lost=true;await route.fetch();await route.fulfill({status:200,contentType:'application/json',body:'{"version":'});}else await route.continue();});
    await agree(page);await expect(page.getByRole('button',{name:'重试同一次授权操作'})).toBeVisible();const g=await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json();expect(g.canViewContacts).toBe(true);
    expect((await write(tp,data.tutor,`/matches/${m.id}/contact-consent`,{roundId:m.roundId,expectedGrantVersion:g.version},'DELETE')).ok()).toBe(true);
    await page.getByRole('button',{name:'重试同一次授权操作'}).click();await expect(page.getByRole('button',{name:'重试同一次授权操作'})).toHaveCount(0);await expect(page.getByTestId('shared-contacts')).toHaveCount(0);expect(keys).toHaveLength(2);expect(new Set(keys).size).toBe(1);
    await refresh(tp);await agree(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('owner_contact');await saveContacts(page,'13800000003','changed_owner');hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await refresh(tp);await expect(tp.getByText('联系方式有更新，确认后即可继续交换。')).toBeVisible();await agree(tp);await progress(page);await agree(page);await expect(page.getByTestId('shared-contacts')).toContainText('tutor_contact');await refresh(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('changed_owner');
  }finally{await tc.close();await oc.close();}
});

test('私密表单失败留输入、旧版核对与越权和换账号清理',async({page,browser,context},info)=>{
  const owner=user('REQUESTER'),outsider=user('REQUESTER');page.on('dialog',d=>d.accept());await login(page,owner);await saveContacts(page,'13800000001','owner_contact');
  const c=await(await page.request.get('/api/v1/me/contacts')).json();expect((await write(page,owner,'/me/contacts',{expectedVersion:c.version,phone:c.phone,wechat:'other_tab'})).ok()).toBeTruthy();
  await page.getByLabel('微信号（选填）').fill('preserved_input');await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('alert')).toContainText('已变化');await expect(page.getByLabel('微信号（选填）')).toHaveValue('preserved_input');
  await page.getByRole('button',{name:'读取最新联系方式',exact:true}).click();await page.getByRole('button',{name:'已核对，保留输入',exact:true}).click();await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.locator('.message.success')).toContainText('私密联系方式已保存');
  await page.getByLabel('微信号（选填）').fill('invalid value');await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('alert')).toContainText('不含空格');await expect(page.getByLabel('微信号（选填）')).toHaveValue('invalid value');
  const ac=await browser.newContext({viewport:info.project.use.viewport});try{const visitor=await ac.newPage();await visitor.goto('/');expect((await visitor.request.get('/api/v1/me/contacts')).status()).toBe(401);}finally{await ac.close();}
  const second=await context.newPage();try{await second.goto('/');await second.getByRole('button',{name:'退出登录',exact:true}).click();await expect(second.getByRole('button',{name:'我想找老师',exact:true})).toBeVisible();await login(second,outsider);
    await page.getByRole('button',{name:'读取最新联系方式',exact:true}).click();await expect(page.getByRole('heading',{name:'登录邻课'})).toBeVisible();expect(await page.locator('body').innerText()).not.toContain('preserved_input');
    expect((await write(second,owner,'/me/contacts',{expectedVersion:0,phone:'13800000009',wechat:null})).status()).toBe(409);
    expect((await(await second.request.get('/api/v1/me/contacts')).json()).phone).toBeNull();
  }finally{await second.close();}
});
