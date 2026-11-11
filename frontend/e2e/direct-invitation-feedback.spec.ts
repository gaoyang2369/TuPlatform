import {test,expect,type Page} from '@playwright/test';
import path from 'node:path';

async function fixture(page:Page) {
  const teacher={id:'teacher',profileVersion:2,displayName:'合成绘画老师',categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[],introduction:'周末水彩入门',priceMin:'100',priceMax:'150',availabilityText:'周末下午'};
  const match={id:'direct',requestId:null,roundId:'round',version:0,state:'WAITING',initiatorRole:'REQUESTER',myRole:'REQUESTER',waitingForMe:false,requestExpired:false,teacherPublic:true,canAccept:false,canReject:false,canWithdraw:true,canInitiateAgain:false,submissionSnapshot:{title:'直接邀请',publisherDisplayName:'合成需求方',introduction:'周末想学习水彩'},teacherSnapshot:teacher,submissionChanged:false,teacherProfileChanged:false};
  const state={contactWrites:0,invites:[] as {body:any;key:string|undefined}[],contacts:{version:0,phone:null as string|null,wechat:null as string|null,fieldVersions:{phone:0,wechat:0}},failSave:false,failInvite:false,requestReads:0};
  await page.route('**/api/v1/**',async route=>{
    const req=route.request(),endpoint=new URL(req.url()).pathname.replace('/api/v1','');
    if(endpoint==='/me')return route.fulfill({json:{id:'owner',account:'synthetic',businessIdentity:'REQUESTER',emailVerified:true}});
    if(endpoint==='/me/profile')return route.fulfill({json:{displayName:'合成需求方',version:0,teacher:null}});
    if(endpoint==='/profile-options')return route.fulfill({json:{categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[]}});
    if(endpoint==='/auth/csrf')return route.fulfill({json:{token:'csrf',headerName:'X-XSRF-TOKEN'}});
    if(endpoint==='/me/matches')return route.fulfill({json:{items:[],nextCursor:null}});
    if(endpoint==='/teachers/teacher')return route.fulfill({json:teacher});
    if(endpoint==='/teachers')return route.fulfill({json:{items:[teacher],nextCursor:null}});
    if(endpoint==='/me/requests'){state.requestReads++;return route.fulfill({json:{items:[],nextCursor:null}});}
    if(endpoint==='/matches'){
      state.invites.push({body:req.postDataJSON(),key:req.headers()['idempotency-key']});
      if(state.failInvite)return route.fulfill({status:503,json:{message:'暂时无法确认发送结果'}});
      return route.fulfill({json:{match,outcome:'CREATED'}});
    }
    if(endpoint==='/matches/direct')return route.fulfill({json:match});
    if(endpoint==='/me/contacts'){
      if(req.method()==='PUT'){
        state.contactWrites++;
        if(state.failSave)return route.fulfill({status:503,json:{message:'暂时无法保存，请重试'}});
        state.contacts={...state.contacts,...req.postDataJSON(),version:1};return route.fulfill({json:{contacts:state.contacts,affectedGrantCount:0}});
      }
      return route.fulfill({json:state.contacts});
    }
    return route.fulfill({status:404,json:{message:'Unexpected '+endpoint}});
  });
  return state;
}

test('保存弹窗在视口中央、配色一致；取消与 Escape 不写入，确认后状态更新',async({page},info)=>{
  const state=await fixture(page);let nativeDialogs=0;page.on('dialog',async d=>{nativeDialogs++;await d.dismiss();});
  await page.goto('/?view=contacts');const save=page.getByRole('button',{name:'保存私密联系方式',exact:true});await expect(save).toBeDisabled();
  await page.locator('#contact-phone').fill('13800000000');await save.click();const dialog=page.getByRole('dialog');await expect(dialog).toBeVisible();
  const bounds=(await dialog.boundingBox())!,viewport=page.viewportSize()!;
  expect(Math.abs(bounds.x+bounds.width/2-viewport.width/2)).toBeLessThan(3);expect(Math.abs(bounds.y+bounds.height/2-viewport.height/2)).toBeLessThan(3);
  expect(await dialog.locator('.dialog-card').evaluate(el=>getComputedStyle(el).backgroundColor)).toBe('rgb(252, 252, 248)');
  await expect(dialog.getByRole('button',{name:'取消',exact:true})).toBeFocused();
  await page.screenshot({path:path.resolve(import.meta.dirname,'../../.tools/direct-invitations/confirm-'+info.project.name+'.png')});
  await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();await expect(save).toBeFocused();expect(state.contactWrites).toBe(0);
  await save.click();await dialog.getByRole('button',{name:'取消',exact:true}).click();expect(state.contactWrites).toBe(0);
  await save.click();await dialog.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByText('所有修改已保存',{exact:true})).toBeVisible();await expect(save).toBeDisabled();expect(state.contactWrites).toBe(1);expect(nativeDialogs).toBe(0);
  const status=(await page.locator('.contact-save-bar .save-state').boundingBox())!,button=(await save.boundingBox())!;
  if(viewport.width<520)expect(button.y-status.y-status.height).toBeGreaterThanOrEqual(15);else expect(button.x-status.x-status.width).toBeGreaterThanOrEqual(23);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('保存失败保留输入；离开确认可取消且不会意外保存',async({page})=>{
  const state=await fixture(page);state.failSave=true;await page.goto('/?view=contacts');await page.locator('#contact-wechat').fill('synthetic_wechat');await page.getByRole('button',{name:'保存私密联系方式'}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByRole('alert')).toContainText('暂时无法保存');await expect(page.locator('#contact-wechat')).toHaveValue('synthetic_wechat');
  await page.getByRole('button',{name:'基本资料',exact:true}).click();await expect(page.getByRole('dialog')).toContainText('放弃尚未保存的修改');await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click();await expect(page.locator('#contact-wechat')).toHaveValue('synthetic_wechat');
  state.failSave=false;await page.getByRole('button',{name:'保存私密联系方式'}).click();await page.getByRole('button',{name:'确认保存',exact:true}).click();await expect(page.getByText('所有修改已保存',{exact:true})).toBeVisible();expect(state.contactWrites).toBe(2);
});

test('没有需求也能直接邀请，留言选填，不调用需求接口且不弹原生确认',async({page},info)=>{
  const state=await fixture(page);let nativeDialogs=0;page.on('dialog',async d=>{nativeDialogs++;await d.dismiss();});
  await page.goto('/?view=teachers&teacher=teacher');await page.getByRole('button',{name:'邀请这位老师'}).click();await expect(page.getByRole('button',{name:'发送邀请',exact:true})).toBeEnabled();await expect(page.getByRole('combobox')).toHaveCount(0);
  await page.locator('#invitation-introduction').fill('周末想学习水彩');await page.screenshot({path:path.resolve(import.meta.dirname,'../../.tools/direct-invitations/invite-'+info.project.name+'.png'),fullPage:true});
  await page.getByRole('button',{name:'发送邀请',exact:true}).click();await expect(page.getByRole('heading',{name:'等待对方回应',exact:true})).toBeVisible();await expect(page.locator('.invitation-summary')).toContainText('周末想学习水彩');await expect(page.getByRole('heading',{name:'发起时的需求'})).toHaveCount(0);
  expect(state.invites).toHaveLength(1);expect(state.invites[0].body).toEqual({kind:'DIRECT_INVITATION',tutorUserId:'teacher',expectedTeacherProfileVersion:2,introduction:'周末想学习水彩'});expect(state.requestReads).toBe(0);expect(nativeDialogs).toBe(0);
});

test('邀请响应不确定时锁定留言，重试复用相同凭据与内容',async({page})=>{
  const state=await fixture(page);state.failInvite=true;await page.goto('/?view=teachers&teacher=teacher');await page.getByRole('button',{name:'邀请这位老师'}).click();await page.getByRole('button',{name:'发送邀请',exact:true}).click();await expect(page.getByText('上次结果尚未确认。请用同一次操作重试，或到撮合进度查看。')).toBeVisible();await expect(page.locator('#invitation-introduction')).toBeDisabled();
  state.failInvite=false;await page.getByRole('button',{name:'重试同一次意向'}).click();await expect(page.getByRole('heading',{name:'等待对方回应',exact:true})).toBeVisible();expect(state.invites).toHaveLength(2);expect(state.invites[0]).toEqual(state.invites[1]);expect(state.invites[0].body.introduction).toBe('');
});
