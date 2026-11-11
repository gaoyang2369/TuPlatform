import {test,expect} from '@playwright/test';

test('撮合待办可直达，授权区靠前且仍需主动勾选和双方同意',async({page})=>{
  const user={id:'requester',account:'requester',businessIdentity:'REQUESTER',operatorGranted:false};
  const teacher={id:'tutor',displayName:'合成老师',profileVersion:1,categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[],introduction:'水彩教学',priceMin:null,priceMax:null,availabilityText:'周末'};
  const request={id:'request',submissionId:'submission',title:'合成绘画需求',categoryCode:'DRAWING',publisherDisplayName:'合成需求方',goal:'水彩学习',budgetMin:'100',budgetMax:'120'};
  const base={requestId:'request',roundId:'round',version:1,initiatorRole:'TUTOR',myRole:'REQUESTER',requestExpired:false,teacherPublic:true,submissionSnapshot:request,teacherSnapshot:teacher,submissionChanged:false,teacherProfileChanged:false,canWithdraw:false,canInitiateAgain:false};
  let waiting={...base,id:'waiting',state:'WAITING',waitingForMe:true,canAccept:true,canReject:true};
  let myConsent=false,otherConsent=false,contactWrites=0;
  await page.route('**/api/v1/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname.replace('/api/v1','');
    if(path==='/me')return route.fulfill({json:user});
    if(path==='/me/profile')return route.fulfill({json:{displayName:'合成需求方',version:1,teacher:null}});
    if(path==='/profile-options')return route.fulfill({json:{categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[]}});
    if(path==='/auth/csrf')return route.fulfill({json:{token:'csrf',headerName:'X-XSRF-TOKEN'}});
    if(path==='/me/matches')return route.fulfill({json:{items:[{...waiting,id:'other',waitingForMe:false},waiting],nextCursor:null}});
    if(path==='/matches/waiting')return route.fulfill({json:waiting});
    if(path==='/matches/waiting/accept'){waiting={...waiting,state:'MUTUAL',waitingForMe:false,canAccept:false,canReject:false};return route.fulfill({json:waiting});}
    if(path==='/me/contacts')return route.fulfill({json:{version:1,phone:null,wechat:'my_demo_wechat',fieldVersions:{phone:0,wechat:1}}});
    if(path==='/matches/waiting/contact-consent'){
      if(req.method()==='PUT'){contactWrites++;expect(req.postDataJSON().selectedFields).toEqual(['WECHAT']);expect(req.postDataJSON().agree).toBe(true);myConsent=true;}
      if(req.method()==='DELETE'){contactWrites++;myConsent=false;}
      return route.fulfill({json:{roundId:'round',version:contactWrites,myConsent,otherConsent,mySelectedFields:myConsent?['WECHAT']:[],requiresReconfirmation:false,otherRequiresReconfirmation:false,canViewContacts:myConsent&&otherConsent,...(myConsent&&otherConsent?{mySharedContacts:{wechat:'my_demo_wechat'},otherSharedContacts:{wechat:'other_demo_wechat'}}:{})}});
    }
    return route.fulfill({status:404,json:{message:'Unexpected endpoint'}});
  });
  await page.goto('/?view=matches');await expect(page.getByRole('heading',{name:'需要我回应 · 1'})).toBeVisible();
  await page.getByRole('button',{name:'查看撮合进度'}).first().click();await expect(page.getByRole('heading',{name:'等待我回应',exact:true})).toBeVisible();await page.reload();
  page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'接受意向',exact:true}).click();await expect(page.getByRole('heading',{name:'交换联系方式',exact:true})).toBeVisible();
  const consent=await page.locator('.consent').boundingBox(),snapshot=await page.getByRole('heading',{name:'发起时的需求'}).boundingBox();expect(consent!.y).toBeLessThan(snapshot!.y);
  await expect(page.getByRole('button',{name:'预览本人分享内容'})).toBeDisabled();await page.getByRole('checkbox',{name:/分享本人微信/}).check();await page.getByRole('button',{name:'预览本人分享内容'}).click();
  const agree=page.getByRole('button',{name:'同意分享所选联系方式'});await expect(agree).toBeDisabled();expect(contactWrites).toBe(0);
  await page.getByRole('checkbox',{name:/我已核对，明确同意/}).check();await agree.click();await expect(page.getByText('你已同意分享，正在等待对方同意。')).toBeVisible();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);
  otherConsent=true;await page.getByRole('button',{name:'读取最新授权'}).click();await expect(page.getByTestId('shared-contacts')).toContainText('other_demo_wechat');
  page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'撤回本人联系方式授权'}).click();await expect(page.getByTestId('shared-contacts')).toHaveCount(0);expect(contactWrites).toBe(2);
});
