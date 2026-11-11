import {test,expect,type Page} from '@playwright/test';
import path from 'node:path';
async function fixture(page:Page){
  const teacher={id:'tutor',displayName:'合成老师',profileVersion:1,categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[],introduction:'水彩教学\n'.repeat(20),priceMin:'100',priceMax:'150',availabilityText:'周末'};
  const match={id:'waiting',requestId:null,roundId:'round',version:1,initiatorRole:'REQUESTER',myRole:'TUTOR',requestExpired:false,teacherPublic:true,submissionSnapshot:{title:'直接邀请',publisherDisplayName:'合成需求方',introduction:'想了解周末的水彩课'},teacherSnapshot:teacher,submissionChanged:false,teacherProfileChanged:false,canWithdraw:false,canInitiateAgain:false,state:'WAITING',waitingForMe:true,canAccept:true,canReject:true};
  const state={match,myConsent:false,otherConsent:true,writes:[] as any[],failRead:false,empty:false,failAccept:false,emptyContacts:false};
  await page.route('**/api/v1/**',async route=>{
    const req=route.request(),url=new URL(req.url()),p=url.pathname.replace('/api/v1','');
    if(p==='/me')return route.fulfill({json:{id:'tutor',account:'tutor',businessIdentity:'TUTOR',operatorGranted:false}});
    if(p==='/me/profile')return route.fulfill({json:{displayName:'合成老师',version:1,teacher}});
    if(p==='/profile-options')return route.fulfill({json:{categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[]}});
    if(p==='/auth/csrf')return route.fulfill({json:{token:'csrf',headerName:'X-XSRF-TOKEN'}});
    if(p==='/me/matches')return route.fulfill({json:{items:state.empty||url.searchParams.get('state')==='REJECTED'?[]:[match,{...match,id:'other',waitingForMe:false,canAccept:false,canReject:false}],nextCursor:null}});
    if(p==='/matches/waiting')return route.fulfill(state.failRead?{status:503,json:{message:'暂时无法读取'}}:{json:match});
    if(p==='/matches/waiting/accept'){
      state.writes.push({body:req.postDataJSON(),key:req.headers()['idempotency-key']});expect(req.postDataJSON().contactExchange).toEqual({agree:true,expectedVersion:1});
      if(state.failAccept)return route.fulfill({status:503,json:{message:'发送结果暂未确认'}});
      Object.assign(match,{state:'MUTUAL',waitingForMe:false,canAccept:false,canReject:false});state.myConsent=true;return route.fulfill({json:match});
    }
    if(p==='/me/contacts')return route.fulfill({json:{version:1,phone:null,wechat:state.emptyContacts?null:'my_demo_wechat',fieldVersions:{phone:0,wechat:1}}});
    if(p==='/matches/waiting/contact-consent'){
      if(req.method()==='PUT'){expect(req.postDataJSON().selectedFields).toEqual(['WECHAT']);state.myConsent=true;state.writes.push(req.postDataJSON());}
      if(req.method()==='DELETE'){state.myConsent=false;state.writes.push(req.postDataJSON());}
      return route.fulfill({json:{roundId:'round',version:state.writes.length,myConsent:state.myConsent,otherConsent:state.otherConsent,mySelectedFields:state.myConsent?['WECHAT']:[],requiresReconfirmation:false,otherRequiresReconfirmation:false,canViewContacts:state.myConsent&&state.otherConsent,...(state.myConsent&&state.otherConsent?{mySharedContacts:{wechat:'my_demo_wechat'},otherSharedContacts:{phone:'13800000001',wechat:'other_demo_wechat'}}:{})}});
    }
    return route.fulfill({status:404,json:{message:'Unexpected endpoint '+p}});
  });return state;
}
const shot=(page:Page,name:string,project:string)=>page.screenshot({path:path.resolve(import.meta.dirname,`../../.tools/match-simplification/${name}-${project}.png`),fullPage:true});
test('列表紧凑、筛选即生效，详情默认收起长资料',async({page},info)=>{
  await fixture(page);await page.goto('/?view=matches');await expect(page.locator('.reply-count')).toHaveText('1 条待你回应');await expect(page.locator('.match-row').first()).toContainText('等待我回应');
  const row=(await page.locator('.match-row').first().boundingBox())!;expect(row.y+row.height).toBeLessThan(page.viewportSize()!.height);await shot(page,'list',info.project.name);
  await page.getByRole('button',{name:'已拒绝',exact:true}).click();await expect(page.getByText('暂无此状态的联系记录')).toBeVisible();await expect(page).toHaveURL(/state=REJECTED/);
  await page.getByRole('button',{name:'全部',exact:true}).click();await page.locator('.match-row').first().getByRole('button').click();await expect(page.locator('.teacher-summary')).not.toBeVisible();await expect(page.locator('.invitation-summary')).toContainText('想了解周末的水彩课');await shot(page,'waiting',info.project.name);
  await page.getByText('查看发起时的老师资料',{exact:true}).click();await expect(page.locator('.teacher-summary')).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('接受弹窗居中配色一致，取消不写入，接受后直接显示联系方式',async({page},info)=>{
  const state=await fixture(page);let native=0;page.on('dialog',async d=>{native++;await d.dismiss();});await page.goto('/?view=matches&match=waiting');const accept=page.getByRole('button',{name:'接受意向',exact:true});await accept.click();const dialog=page.getByRole('dialog');await expect(dialog).toBeVisible();
  const box=(await dialog.boundingBox())!,size=page.viewportSize()!;expect(Math.abs(box.x+box.width/2-size.width/2)).toBeLessThan(3);expect(Math.abs(box.y+box.height/2-size.height/2)).toBeLessThan(3);expect(await dialog.locator('.dialog-card').evaluate(el=>getComputedStyle(el).backgroundColor)).toBe('rgb(252, 252, 248)');await expect(dialog).toContainText('my_demo_wechat');await shot(page,'accept',info.project.name);
  await page.keyboard.press('Escape');expect(state.writes).toHaveLength(0);await expect(accept).toBeFocused();await accept.click();await dialog.getByRole('button',{name:'接受意向',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toContainText('other_demo_wechat');await expect(page.getByRole('checkbox')).toHaveCount(0);await expect(page.getByRole('button',{name:'同意交换联系方式'})).toHaveCount(0);expect(state.writes).toHaveLength(1);expect(native).toBe(0);await shot(page,'mutual',info.project.name);
  await page.getByText('管理我的联系方式',{exact:true}).click();await page.getByRole('button',{name:'停止分享',exact:true}).click();await dialog.getByRole('button',{name:'取消',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toBeVisible();await page.getByRole('button',{name:'停止分享',exact:true}).click();await dialog.getByRole('button',{name:'停止分享',exact:true}).click();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);expect(state.writes).toHaveLength(2);
});
test('旧记录一次确认，缺少联系方式有补充入口，失败和空列表可恢复',async({page})=>{
  const state=await fixture(page);Object.assign(state.match,{state:'MUTUAL',canAccept:false,canReject:false,waitingForMe:false});state.emptyContacts=true;await page.goto('/?view=matches&match=waiting');await expect(page.getByRole('button',{name:'填写联系方式',exact:true})).toBeVisible();expect(state.writes).toHaveLength(0);
  state.emptyContacts=false;await page.reload();await expect(page.getByRole('button',{name:'同意交换联系方式'})).toBeVisible();await expect(page.getByRole('checkbox')).toHaveCount(0);await page.getByRole('button',{name:'同意交换联系方式'}).click();await expect(page.getByTestId('shared-contacts')).toContainText('other_demo_wechat');
  state.failRead=true;await page.getByRole('button',{name:'读取最新进度'}).click();await expect(page.getByRole('alert')).toContainText('暂时无法读取');state.failRead=false;await page.getByRole('button',{name:'读取最新进度'}).click();await expect(page.getByTestId('shared-contacts')).toBeVisible();state.empty=true;await page.getByRole('button',{name:'返回列表'}).click();await expect(page.getByText('还没有联系记录')).toBeVisible();
});
test('回应失败保留同一凭据重试，不再次弹窗',async({page})=>{
  const state=await fixture(page);state.failAccept=true;let release!:()=>void;const hold=new Promise<void>(resolve=>release=resolve);await page.route('**/api/v1/matches/waiting',async route=>{await hold;await route.fallback();});await page.goto('/?view=matches&match=waiting');await expect(page.getByText('正在读取联系记录…',{exact:true})).toBeVisible();release();await page.getByRole('button',{name:'接受意向',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'接受意向',exact:true}).click();await expect(page.getByRole('button',{name:'重试同一次回应'})).toBeVisible();await expect(page.getByRole('dialog')).not.toBeVisible();state.failAccept=false;await page.getByRole('button',{name:'重试同一次回应'}).click();await expect(page.getByTestId('shared-contacts')).toBeVisible();expect(state.writes).toHaveLength(2);expect(state.writes[0]).toEqual(state.writes[1]);
});
