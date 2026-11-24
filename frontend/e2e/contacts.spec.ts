import { expectMobileFit } from "./mobile-layout";
import { sampleResume, fillResume } from "./resume-fixture";
import {test,expect} from '@playwright/test';
import type {BrowserContext, Page} from '@playwright/test';
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
  await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('status').filter({hasText:'私密联系方式已保存'})).toContainText('私密联系方式已保存');
}
async function prepare(ownerPage:Page,tutorPage:Page,operatorPage:Page,ui=false){
  const owner=user('REQUESTER'),tutor=user('TUTOR'),operator=user('OPERATOR');
  await login(ownerPage,owner);await login(tutorPage,tutor);await login(operatorPage,operator);
  await ownerPage.locator('#field-displayName').fill('合成需求方');await ownerPage.getByRole('button',{name:'保存资料',exact:true}).click();await expect(ownerPage.locator('.feedback-toast').getByRole('status')).toContainText('资料已保存');
  await tutorPage.locator('#field-displayName').fill('合成绘画老师');await tutorPage.getByRole('checkbox',{name:'绘画',exact:true}).check();await tutorPage.getByRole('checkbox',{name:'线上',exact:true}).check();await tutorPage.locator('#field-introduction').fill('合成老师自述');
  await fillResume(tutorPage);
  await tutorPage.getByRole('button',{name:'保存资料',exact:true}).click();await expect(tutorPage.locator('.feedback-toast').getByRole('status')).toContainText('资料已保存');await tutorPage.getByRole('banner').getByRole('button',{name:'本人资料',exact:true}).click();await tutorPage.getByRole('button',{name:'预览并公开'}).click();await tutorPage.getByRole('button',{name:'确认公开资料'}).click();await expect(tutorPage.getByText('资料已公开',{exact:true})).toBeVisible();
  let request:string;
  if(ui){
    await ownerPage.getByRole('button',{name:'我的需求',exact:true}).click();await ownerPage.getByRole('button',{name:'新建需求草稿'}).click();
    await ownerPage.getByLabel('需求标题',{exact:false}).fill('合成联系方式闭环');await ownerPage.getByRole("group",{name:"服务类别",exact:true}).getByRole("radio",{name:"绘画",exact:true}).check();await ownerPage.getByRole("group",{name:"服务对象阶段",exact:true}).getByRole("radio",{name:"成年",exact:true}).check();await ownerPage.getByLabel("公开街道").selectOption("坪山街道");await ownerPage.getByLabel("具体地址").fill("合成私密花园3栋");await ownerPage.getByLabel('目标与现状',{exact:false}).fill('合成绘画目标');
    await ownerPage.getByRole("group",{name:"服务方式",exact:true}).getByRole("radio",{name:"线上",exact:true}).check();
    await ownerPage.getByRole('button',{name:'保存并下一步'}).click();await ownerPage.getByLabel('预算下限').fill('100');await ownerPage.getByLabel('预算上限').fill('200');await ownerPage.getByLabel('单次时长（小时）',{exact:true}).fill('1.5');await ownerPage.getByRole("group",{name:"频率",exact:true}).getByRole("radio",{name:"可协商",exact:true}).check();await ownerPage.getByRole('radio',{name:'填写精确时间',exact:true}).check();await ownerPage.getByRole('button',{name:'添加时段'}).click();await ownerPage.getByRole('button',{name:'保存并预览'}).click();
    await expect(ownerPage.getByRole('button',{name:'确认提交审核',exact:true})).toBeDisabled();await ownerPage.getByRole('radio',{name:'我为已满18岁的本人发布本条需求。',exact:true}).check();await ownerPage.getByRole("checkbox",{name:/我已阅读并同意/}).check();await ownerPage.getByRole('button',{name:'确认提交审核',exact:true}).click();await expect(ownerPage.getByText('待审核',{exact:true})).toBeVisible();
    const recommended = ownerPage.getByRole('button',{name:'查看老师资料：合成绘画老师'});
    await expect(recommended).toBeVisible();
    await ownerPage.getByRole('button',{name:'返回我的需求',exact:true}).click();
    await recommended.click();
    await expect(ownerPage.getByRole('dialog',{name:'老师资料'})).toContainText('合成绘画老师');
    await ownerPage.getByRole('button',{name:'邀请这位老师'}).click();
    await expect(ownerPage.getByRole('button',{name:'发送邀请',exact:true})).toBeEnabled();
    await ownerPage.getByRole('button',{name:'返回我的需求',exact:true}).click();
    await expect(ownerPage.getByRole('heading',{name:'我的需求',exact:true})).toBeVisible();
    request=(await(await ownerPage.request.get('/api/v1/me/requests')).json()).items[0].id;
    await operatorPage.getByRole('button',{name:'需求审核',exact:true}).click();await operatorPage.getByRole('button',{name:'核对提交',exact:true}).click();await operatorPage.getByRole('radio',{name:'通过审核并公开',exact:true}).check();await operatorPage.getByRole('button',{name:'核对并确认审核'}).click();await operatorPage.getByRole('button',{name:'确认通过并公开',exact:true}).click();await expect(operatorPage.getByRole("dialog", {name:"核对提交", exact:true}).getByText('已通过',{exact:true})).toBeVisible();
  }else{
    const d=await(await write(ownerPage,owner,'/requests',{},'POST')).json();
    const saved=await(await write(ownerPage,owner,`/requests/${d.id}/draft`,{expectedVersion:d.version,fields:{title:'合成联系方式闭环',categoryCode:'DRAWING',audienceStageCode:'ADULT',areaCode:'PS_PINGSHAN',goal:'合成绘画目标',budgetMin:'100',budgetMax:'200',durationHours:'1.5',frequency:{mode:'NEGOTIABLE'},availableSlots:[{weekday:1,startMinute:600,endMinute:660}]}},'PATCH')).json();
    const submission=await write(ownerPage,owner,`/requests/${d.id}/submit`,{expectedVersion:saved.version,publisherDeclaration:'SELF_ADULT',declarationVersion:'publisher-v1',policiesAccepted:true},'POST');
    expect(submission.ok(),await submission.text()).toBe(true);
    const submitted=await submission.json();
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
test('公开评价冷启动门槛与老师视角的家长信用', async ({page, browser}, info) => {
  const contexts: BrowserContext[] = [];
  async function separatePage() {
    const {viewport,isMobile,hasTouch} = info.project.use;
    const context = await browser.newContext({viewport,isMobile,hasTouch});
    contexts.push(context);
    return context.newPage();
  }
  try {
    const tp = await separatePage(), op = await separatePage();
    const {owner, tutor, request} = await prepare(page,tp,op);
    const profile = await (await page.request.get('/api/v1/teachers/'+tutor.id)).json();
    const submission = await (await tp.request.get('/api/v1/public/requests/'+request)).json();
    async function checkTeacher(count: number) {
      const reviewPath = '**/api/v1/teachers/'+tutor.id+'/reviews*';
      if (count === 2) {
        let failContinuation = true;
        await page.route(reviewPath, async route => {
          const url = new URL(route.request().url());
          if (failContinuation && url.searchParams.has('cursor')) {
            failContinuation = false;
            return route.fulfill({status:503,json:{message:'评价暂时无法读取'}});
          }
          url.searchParams.set('limit','1');
          return route.continue({url:url.toString()});
        });
      }
      await page.goto('/?view=teachers');
      const card = page.locator('.teacher-card').filter({hasText:'合成绘画老师'});
      await expect(card).toBeVisible();
      const checkRating = async (region: typeof card) => {
        await expect(region.getByLabel(/综合评分/)).toHaveCount(count >= 3 ? 1 : 0);
        if (count >= 3) await expect(region.getByLabel(/综合评分/)).toHaveText(/★\s*5\.0.*3 条评价/);
        else if (count) await expect(region.getByText(`已有 ${count} 条真实评价`)).toBeVisible();
        else await expect(region).not.toContainText('条真实评价');
      };
      await checkRating(card);
      await card.getByRole('button',{name:'查看老师'}).click();
      const section = page.getByRole('region',{name:'真实家长评价'});
      await expect(section).toHaveAttribute('aria-busy','false');
      if (!count) await expect(section.getByText(/完成首次试课或合作后/)).toBeVisible();
      else {
        if (count === 2) {
          await expect(section.locator('article')).toHaveCount(1);
          await section.getByRole('button',{name:'查看更多评价'}).click();
          await expect(section.getByRole('alert')).toBeVisible();
          await expect(section.locator('article')).toHaveCount(1);
          await section.getByRole('button',{name:'重新读取评价'}).click();
        }
        await expect(section.locator('article')).toHaveCount(count);
        await expect(section.getByLabel('维度均分').locator('dt')).toHaveCount(4);
        await expect(section.getByLabel('高频标签')).toContainText('很有耐心 · '+count);
        await expect(section.locator('article').last()).toContainText('合家长');
        await expect(section.locator('article').last()).toContainText('谢谢家长');
        await expect(section).not.toContainText('合成需求方');
      }
      await checkRating(section);
      expect(await section.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
      if (count === 2) await page.unroute(reviewPath);
    }
    await checkTeacher(0);
    for (let i=0; i<3; i++) {
      const parent = i===0 ? owner : user('REQUESTER');
      const parentPage = i===0 ? page : await separatePage();
      if (i) {
        await login(parentPage,parent);
        expect((await write(parentPage,parent,'/me/profile',{expectedVersion:0,displayName:'林测试家长'},'PATCH')).ok()).toBe(true);
        expect((await write(parentPage,parent,'/me/contacts',{expectedVersion:0,wechat:'sample_parent',phone:null})).ok()).toBe(true);
      }
      const consent = {agree:true,expectedVersion:1};
      const source = i===0 ? {kind:'INVITATION',requestId:request,expectedSubmissionId:submission.submissionId} : {kind:'DIRECT_INVITATION'};
      const started = await write(parentPage,parent,'/matches',{...source,tutorUserId:tutor.id,
        expectedTeacherProfileVersion:profile.profileVersion,contactExchange:consent},'POST');
      expect(started.ok()).toBe(true);
      let match = (await started.json()).match;
      for (const [action,actor,tab] of [
        ['accept',tutor,tp],['request-cooperation',parent,parentPage],['confirm-cooperation',tutor,tp],
        ['request-completion',tutor,tp],['confirm-completion',parent,parentPage],
      ] as const) {
        const response = await write(tab,actor,'/matches/'+match.id+'/'+action,{roundId:match.roundId,expectedVersion:match.version,
          ...(action==='accept'?{contactExchange:consent}:{})},'POST');
        expect(response.ok(),action).toBe(true);
        match = await response.json();
      }
      const path = '/matches/'+match.id+'/reviews';
      const parentReview = await write(parentPage,parent,path,{roundId:match.roundId,
        subScores:{守时履约:5,讲解清晰度:5,备课用心度:5,孩子接受度:5},tags:['很有耐心'],content:'讲题很细致'},'POST');
      expect(parentReview.ok()).toBe(true);
      const blind = await page.request.get('/api/teachers/'+tutor.id+'/reviews');
      expect((await blind.json()).published_count).toBe(i);
      expect((await write(tp,tutor,path,{roundId:match.roundId,subScores:{需求描述真实:5,沟通尊重顺畅:5,课时费结算及时:5},
        tags:['沟通顺畅'],content:'仅双方可读的评语'},'POST')).ok()).toBe(true);
      if (i===0) {
        const entry = (await (await tp.request.get('/api/v1'+path)).json()).reviews.find((r:{mine:boolean})=>!r.mine);
        expect((await write(tp,tutor,'/reviews/'+entry.id+'/reply',{content:'谢谢家长'},'POST')).ok()).toBe(true);
      }
      await checkTeacher(i+1);
    }
    await tp.goto('/?view=public&request='+request);
    await expect(tp.getByRole('dialog',{name:'需求详情'})).toContainText('已完成 1 次合作 · 沟通顺畅');
    const visitor = await separatePage();
    await visitor.goto('/?view=public&request='+request);
    await expect(visitor.getByRole('dialog',{name:'需求详情'})).toContainText('合成需求方');
    await expect(visitor.getByRole('dialog')).not.toContainText('次合作');

    await op.goto('/admin');
    await op.getByRole('button',{name:'用户与风控'}).click();
    await op.getByLabel('姓名、账号或邮箱').fill(owner.account);
    await op.getByRole('button',{name:'搜索用户'}).click();
    const author = op.getByRole('article').filter({hasText:owner.account});
    await author.getByRole('button',{name:'查看已发评价'}).click();
    await author.getByRole('button',{name:'隐藏评价'}).click();
    const confirm = op.getByRole('dialog',{name:'隐藏评价'});
    await confirm.getByLabel('操作理由').fill('合成评价复核');
    await confirm.getByRole('button',{name:'确认',exact:true}).click();
    await expect(author).toContainText('已隐藏 · 合成评价复核');
    await page.goto('/?view=teachers');
    const moderated = page.locator('.teacher-card').filter({hasText:'合成绘画老师'});
    await expect(moderated).toContainText('已有 2 条真实评价');
    await expect(moderated.getByLabel(/综合评分/)).toHaveCount(0);
  } finally { for (const context of contexts) await context.close(); }
});
test('直接邀请双方从交换联系方式到合作完结解锁互评',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile,hasTouch:info.project.use.hasTouch});
  try{
    const tp=await tc.newPage(),owner=user('REQUESTER'),tutor=user('TUTOR');let native=0;for(const p of [page,tp])p.on('dialog',async d=>{native++;await d.dismiss();});
    await login(page,owner);await login(tp,tutor);
    expect((await write(page,owner,'/me/profile',{expectedVersion:0,displayName:'合成直接邀请人'},'PATCH')).ok()).toBe(true);
    const saved=await write(tp,tutor,'/me/teacher-profile',{expectedVersion:0,...sampleResume,displayName:'合成直接邀请老师',categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[],introduction:'水彩入门教学',priceMin:null,priceMax:null,availabilityText:'周末下午'});
    expect(saved.ok()).toBe(true);const profile=await saved.json();expect((await write(tp,tutor,'/me/teacher-profile/publish',{expectedVersion:profile.version},'POST')).ok()).toBe(true);
    await saveContacts(page,'','direct_owner');await saveContacts(tp,'','direct_teacher');
    const recorded = new Set<string>();
    page.on("response", response => {
      if (new URL(response.url()).pathname === "/api/v1/events" && response.status()===204)
        recorded.add(response.request().postDataJSON().eventName);
    });
    await page.goto('/?view=teachers&teacher='+tutor.id);
    await expect.poll(()=>[...recorded].sort()).toEqual(["open_teacher_detail","page_view_teachers"]);
    await page.getByRole('button',{name:'邀请这位老师'}).click();await expect(page.locator('.exchange-notice')).toContainText('direct_owner');await page.locator('#invitation-introduction').fill('想了解周末水彩课');await page.getByRole('button',{name:'发送邀请',exact:true}).click();
    await expect(page.locator('.match-detail .status-pill')).toHaveText('等待对方回应');expect((await(await page.request.get('/api/v1/me/requests')).json()).items).toHaveLength(0);
    const m=(await(await page.request.get('/api/v1/me/matches')).json()).items[0];expect(m.requestId).toBeNull();hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());
    await progress(tp);await expect(tp.getByRole('heading',{name:'合成直接邀请人',exact:true})).toBeVisible();await expect(tp.locator('.invitation-summary')).toContainText('想了解周末水彩课');await accept(tp);
    await reveal(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('direct_owner');await refresh(page);await reveal(page);await expect(page.getByTestId('shared-contacts')).toContainText('direct_teacher');
    for(const p of [page,tp]){await expect(p.getByRole('button',{name:'同意交换联系方式'})).toHaveCount(0);await expect(p.getByRole('checkbox')).toHaveCount(0);expect(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}expect(native).toBe(0);
    await page.screenshot({path:path.join(root,`.tools/match-simplification/real-mutual-${info.project.name}.png`),fullPage:true});
    async function action(p: Page, label: string) {
      await p.getByRole('button', { name: label, exact: true }).click();
      await p.getByRole('dialog').getByRole('button', { name: label, exact: true }).click();
    }
    await action(page, '已约试课 / 开始合作');
    await expect(page.getByRole('heading', { name: '等待确认合作' })).toBeVisible();
    await refresh(tp);
    await action(tp, '确认对方发起的合作');
    await expect(tp.locator('.status-pill')).toHaveText('合作中');
    await action(tp, '标记阶段完结');
    await expect(tp.getByText(/已发起完结确认，等待对方确认/)).toBeVisible();
    await refresh(page);
    await action(page, '确认阶段完结');
    await expect(page.getByRole('heading', { name: '互评资格已解锁' })).toBeVisible();
    const completed = await (await page.request.get('/api/v1/matches/' + m.id)).json();
    expect(completed.reviewEligible).toBe(true);
    expect(Date.parse(completed.reviewDeadlineAt) - Date.parse(completed.completedAt)).toBe(14 * 86400000);
    await page.screenshot({path:path.join(root,`.tools/match-simplification/completed-${info.project.name}.png`),fullPage:true});
    for (const participant of [page, tp]) {
      await refresh(participant);
      await expect(participant.getByRole('heading', { name: '互评资格已解锁' })).toBeVisible();
      const stages = participant.getByLabel('撮合阶段');
      await expect(stages.locator('li')).toHaveCount(6);
      await expectMobileFit(participant);
      const columns = await stages.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(" ").length);
      expect(columns).toBe(participant.viewportSize()!.width < 768 ? 3 : 6);
      await expect(stages.locator('[aria-current="step"]')).toContainText('完结互评');
      expect(await participant.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await participant.getByRole('button', { name: '返回列表' }).click();
      await participant.getByRole('button', { name: '已完结', exact: true }).click();
      await expect(participant.locator('.match-row')).toHaveCount(1);
    }
    async function review(p: Page, dimensions: string[], tag: string, content: string) {
      await p.getByRole('button', {name:/去评价（剩余/}).click();
      const dialog = p.getByRole('dialog', {name:'合作互评'});
      await expect(dialog.getByRole('button', {name:'提交评价'})).toBeDisabled();
      for (const dimension of dimensions) {
        const group = dialog.getByRole('group', {name:dimension});
        const fiveStars = group.getByRole('radio', {name:'5 星', exact:true});
        if (info.project.use.isMobile) {
          await group.locator('label').filter({has: p.getByRole('radio', {name:'5 星', exact:true})}).tap();
        } else {
          await fiveStars.focus();
          await p.keyboard.press('Space');
        }
        await expect(fiveStars).toBeChecked();
      }
      await dialog.getByRole('button', {name:tag, exact:true}).click();
      await dialog.getByLabel(/文字评语/).fill(content);
      await expectMobileFit(p);
      await dialog.getByRole('button', {name:'提交评价'}).click();
      await expect(dialog.getByRole('status')).toHaveText('评价已提交');
      expect(await dialog.evaluate(el=>el.scrollWidth <= el.clientWidth)).toBe(true);
      return dialog;
    }
    const parentDialog = await review(page,['守时履约','讲解清晰度','备课用心度','孩子接受度'],'很有耐心','合成家长反馈');
    await expect(parentDialog.getByText(/待对方提交或到期后公开/)).toBeVisible();
    await parentDialog.getByRole('button',{name:'关闭'}).click();
    await expect(page.getByRole('button',{name:'已提交评价，等待双盲公开'})).toBeVisible();
    const blind = await tp.request.get('/api/matches/' + m.id + '/reviews');
    expect(blind.ok()).toBe(true);
    expect((await blind.json()).reviews).toEqual([]);
    const teacherDialog = await review(tp,['需求描述真实','沟通尊重顺畅','课时费结算及时'],'沟通顺畅','合成老师反馈');
    await expect(teacherDialog.getByText('合成家长反馈',{exact:true})).toBeVisible();
    await teacherDialog.getByLabel(/礼貌回复/).fill('合成老师回复');
    await teacherDialog.getByRole('button',{name:'发布回复'}).click();
    await expect(teacherDialog.getByRole('status')).toHaveText('回复已发布');
    await expect(teacherDialog.getByLabel(/礼貌回复/)).toHaveCount(0);
    await refresh(page);
    await page.getByRole('button',{name:'查看互评'}).click();
    await expect(parentDialog.getByText('合成老师反馈',{exact:true})).toBeVisible();
    await expect(parentDialog.getByText(/合成老师回复/)).toBeVisible();
    await parentDialog.getByLabel(/礼貌回复/).fill('合成家长回复');
    await parentDialog.getByRole('button',{name:'发布回复'}).click();
    await expect(parentDialog.getByRole('status')).toHaveText('回复已发布');
    await expect(parentDialog.getByLabel(/礼貌回复/)).toHaveCount(0);
  }finally{await tc.close();}
});
test('完整投递闭环：发布审核、接受后联系，停止分享可取消和确认',async({page,browser},info)=>{
  const tc=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile,hasTouch:info.project.use.hasTouch}),oc=await browser.newContext({viewport:info.project.use.viewport});
  try{const tp=await tc.newPage(),op=await oc.newPage();const data=await prepare(page,tp,op,true);
    for (const route of ['/public/requests','/public/requests/'+data.request]) {
      const response=await tp.request.get('/api/v1'+route);
      expect(response.ok()).toBe(true);
      const json=await response.text();
      expect(json).toContain('坪山街道');
      expect(json).not.toMatch(/exact_address|locationText|合成私密花园/);
    }
    await tp.getByRole('button',{name:'浏览需求',exact:true}).click();await tp.getByRole('button',{name:'查看需求',exact:true}).click();await tp.getByRole('button',{name:'发出意向',exact:true}).click();await tp.getByRole('button',{name:'确认投递',exact:true}).click();await expect(tp.locator('.match-detail .status-pill')).toHaveText('等待对方回应');
    const matchId=new URL(tp.url()).searchParams.get("match");
    const detail=()=>tp.request.get("/api/v1/matches/"+matchId);
    expect(await (await detail()).text()).not.toMatch(/exact_address|合成私密花园/);
    await progress(page);await accept(page);await reveal(page);await expect(page.getByTestId('shared-contacts')).toContainText('tutor_contact');await refresh(tp);await reveal(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('13800000001');await reveal(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('合成私密花园3栋');expect((await (await detail()).json()).exact_address).toBe('合成私密花园3栋');
    const m=(await(await page.request.get('/api/v1/me/matches')).json()).items[0];const publicRequest=await(await page.request.get(`/api/v1/public/requests/${m.requestId}`)).json();for(const f of ['phone','wechat','email'])expect(publicRequest).not.toHaveProperty(f);
    await stop(page,true);await reveal(page);await expect(page.getByTestId('shared-contacts')).toBeVisible();await page.getByRole('button',{name:'停止分享',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'停止分享',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);await expect(page.getByRole('button',{name:'同意交换联系方式',exact:true})).toBeEnabled();hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await refresh(tp);await expect(tp.getByTestId('shared-contacts')).toHaveCount(0);expect(await (await detail()).text()).not.toMatch(/exact_address|合成私密花园/);
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
    await refresh(tp);await agree(tp);await reveal(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('owner_contact');await saveContacts(page,'13800000003','changed_owner');hidden(await(await tp.request.get(`/api/v1/matches/${m.id}/contact-consent`)).json());await refresh(tp);await expect(tp.getByText('联系方式有更新，确认后即可继续交换。')).toBeVisible();await agree(tp);await progress(page);await agree(page);await reveal(page);await expect(page.getByTestId('shared-contacts')).toContainText('tutor_contact');await refresh(tp);await reveal(tp);await expect(tp.getByTestId('shared-contacts')).toContainText('changed_owner');
  }finally{await tc.close();await oc.close();}
});

test('私密表单失败留输入、旧版核对与越权和换账号清理',async({page,browser,context},info)=>{
  const owner=user('REQUESTER'),outsider=user('REQUESTER');page.on('dialog',d=>d.accept());await login(page,owner);await saveContacts(page,'13800000001','owner_contact');
  const c=await(await page.request.get('/api/v1/me/contacts')).json();expect((await write(page,owner,'/me/contacts',{expectedVersion:c.version,phone:c.phone,wechat:'other_tab'})).ok()).toBeTruthy();
  await page.getByLabel('微信号（选填）').fill('preserved_input');await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('alert')).toContainText('已变化');await expect(page.getByLabel('微信号（选填）')).toHaveValue('preserved_input');
  await page.getByRole('button',{name:'读取最新联系方式',exact:true}).click();await page.getByRole('button',{name:'已核对，保留输入',exact:true}).click();await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('status').filter({hasText:'私密联系方式已保存'})).toContainText('私密联系方式已保存');
  await page.getByLabel('微信号（选填）').fill('invalid value');await page.getByRole('button',{name:'保存私密联系方式',exact:true}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('alert')).toContainText('不含空格');await expect(page.getByLabel('微信号（选填）')).toHaveValue('invalid value');
  const ac=await browser.newContext({viewport:info.project.use.viewport});try{const visitor=await ac.newPage();await visitor.goto('/');expect((await visitor.request.get('/api/v1/me/contacts')).status()).toBe(401);}finally{await ac.close();}
  const second=await context.newPage();try{await second.goto('/');await second.getByRole('button',{name:/切换账号/}).click();await second.getByRole('button',{name:'退出登录',exact:true}).click();await expect(second.getByRole('button',{name:'我想找老师',exact:true})).toBeVisible();await login(second,outsider);
    await page.getByRole('button',{name:'读取最新联系方式',exact:true}).click();await expect(page.getByRole('heading',{name:'登录邻课'})).toBeVisible();expect(await page.locator('body').innerText()).not.toContain('preserved_input');
    expect((await write(second,owner,'/me/contacts',{expectedVersion:0,phone:'13800000009',wechat:null})).status()).toBe(409);
    expect((await(await second.request.get('/api/v1/me/contacts')).json()).phone).toBeNull();
  }finally{await second.close();}
});

async function reveal(page: Page) {
  const view = page.getByRole("button", {name:"查看对方联系方式"});
  const contacts = page.getByTestId("shared-contacts");
  await expect(view.or(contacts)).toBeVisible();
  if (await view.isVisible()) await view.click();
}
