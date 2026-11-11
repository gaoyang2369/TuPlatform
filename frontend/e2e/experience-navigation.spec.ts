import {test,expect,type Page} from '@playwright/test';

async function fixture(page:Page,options:{identity?:'REQUESTER'|'TUTOR';loggedIn?:boolean;published?:boolean}={}) {
  let loggedIn=options.loggedIn??false;
  const identity=options.identity??'TUTOR';
  const me={id:'ux-user',account:'ux_user',emailVerified:true,businessIdentity:identity,operatorGranted:false};
  const teacher={id:'teacher-1',profileVersion:1,displayName:'示例老师',categoryCodes:['DRAWING'],serviceModes:['ONLINE'],areaCodes:[],introduction:'方向：水彩入门',availabilityText:'周末下午可协商',priceMin:'100.00',priceMax:'150.50',public:options.published??true,version:1};
  let profile={displayName:'示例用户',version:1,teacher:identity==='TUTOR'?teacher:null};
  const requests=Array.from({length:13},(_,i)=>({id:'request-'+i,submissionId:'submission-'+i,title:'水彩需求 '+i,publisherDisplayName:'示例需求方',categoryCode:'DRAWING',audienceStageCode:'ADULT',serviceMode:'ONLINE',budgetMin:'80.00',budgetMax:'120.50',durationHours:'1.5',frequency:{mode:'PER_WEEK',count:1},availabilityPreference:{mode:'PREFERRED',weekdays:[6,7],periods:['AFTERNOON']},goal:'学习基础水彩'}));
  const writes:string[]=[];
  await page.route('**/api/v1/**',async route=>{
    const req=route.request(),url=new URL(req.url()),path=url.pathname.replace('/api/v1','');
    if(req.method()!=='GET')writes.push(path);
    if(path==='/auth/csrf')return route.fulfill({json:{token:'csrf',headerName:'X-XSRF-TOKEN'}});
    if(path==='/auth/login'){loggedIn=true;return route.fulfill({json:me});}
    if(path==='/auth/logout'){loggedIn=false;return route.fulfill({status:204});}
    if(path==='/me')return route.fulfill(loggedIn?{json:me}:{status:401,json:{code:'LOGIN_REQUIRED',message:'请先登录'}});
    if(path==='/me/profile')return route.fulfill({json:profile});
    if(path==='/me/teacher-profile'){profile={...profile,displayName:req.postDataJSON().displayName,version:2};return route.fulfill({json:profile});}
    if(path==='/profile-options')return route.fulfill({json:{categoryCodes:['DRAWING'],serviceModes:['ONLINE','OFFLINE'],areaCodes:[]}});
    if(path==='/publishing-options')return route.fulfill({json:{categories:['DRAWING'],serviceModes:['ONLINE','OFFLINE'],districts:[{code:'SZ_PINGSHAN',label:'坪山区'}]}});
    if(path==='/public/requests'){const start=Number(url.searchParams.get('cursor')||0);return route.fulfill({json:{items:requests.slice(start,start+10),nextCursor:start===0?'10':null}});}
    if(path.startsWith('/public/requests/'))return route.fulfill({json:requests.find(r=>r.id===path.split('/').at(-1))});
    if(path==='/teachers')return route.fulfill({json:{items:[teacher],nextCursor:null}});
    if(path.startsWith('/teachers/'))return route.fulfill({json:teacher});
    if(path==='/me/requests')return route.fulfill({json:{items:[],nextCursor:null}});
    if(path==='/me/matches')return route.fulfill({json:{items:[],nextCursor:null}});
    return route.fulfill({status:404,json:{message:'Unexpected endpoint '+path}});
  });
  return {writes};
}
async function expandFilters(page:Page){const toggle=page.getByRole('button',{name:'筛选条件',exact:true});if(await toggle.isVisible())await toggle.click();}

test('双入口：首页找老师须登录，找需求可直接浏览且手机首屏有完整卡片',async({page})=>{
  await fixture(page);await page.goto('/');
  await expect(page.getByRole('button',{name:'我想找老师',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'我想找老师',exact:true}).click();await expect(page.getByRole('heading',{name:'登录邻课'})).toBeVisible();expect(page.url()).toContain('returnTo=');
  await page.getByRole('link',{name:'邻课首页'}).click();await page.getByRole('button',{name:'我是老师，找需求'}).click();
  await expect(page.locator('.request-card')).toHaveCount(10);await expect(page.locator('.request-card').first()).toContainText('周末 下午 · 每周1次 · 每次1.5小时');
  if(page.viewportSize()!.width<650)expect((await page.locator('.request-card').first().boundingBox())!.y+(await page.locator('.request-card').first().boundingBox())!.height).toBeLessThan(844);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('详情登录后回到原需求，刷新仍在详情且不自动投递',async({page})=>{
  const {writes}=await fixture(page);await page.goto('/?view=public&request=request-3');
  await page.getByRole('button',{name:'登录后以老师身份投递'}).click();await page.reload();
  await page.locator('#field-account').fill('ux_user');await page.locator('#field-password').fill('test-password');await page.getByRole('button',{name:'登录',exact:true}).click();
  await expect(page.getByRole('heading',{name:'水彩需求 3',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'我对这个需求感兴趣'})).toBeVisible();
  await page.reload();await expect(page.getByRole('heading',{name:'水彩需求 3',exact:true})).toBeVisible();expect(writes).toEqual(['/auth/login']);
});

test('筛选与分页在刷新、详情返回、浏览器前进后退中恢复',async({page})=>{
  await fixture(page);await page.goto('/?view=public');await expect(page.locator('.request-card')).toHaveCount(10);await expandFilters(page);
  await page.getByRole('combobox',{name:'类别',exact:true}).selectOption('DRAWING');await page.getByRole('button',{name:'筛选需求',exact:true}).click();
  await page.getByRole('button',{name:'下一页',exact:true}).click();await expect(page.locator('.pagination')).toContainText('第 2 页');await page.reload();
  await expect(page.locator('.request-card')).toHaveCount(3);await expect(page.locator('.filter-toolbar')).toContainText('绘画');
  await page.locator('.request-card').first().getByRole('button').click();await expect(page.getByRole('heading',{name:'水彩需求 10',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'返回需求列表'}).click();await expect(page.locator('.pagination')).toContainText('第 2 页');
  await page.evaluate(()=>history.forward());await expect(page.getByRole('button',{name:'返回需求列表'})).toBeVisible();
  await page.evaluate(()=>history.back());await expect(page.locator('.request-card')).toHaveCount(3);
});

test('老师详情可刷新，筛选可重置，直接邀请等待老师资料加载',async({page})=>{
  await fixture(page,{identity:'REQUESTER',loggedIn:true});await page.goto('/');await expect(page.locator('.teacher-card')).toHaveCount(1);
  await expandFilters(page);await page.getByRole('combobox',{name:'类别',exact:true}).selectOption('DRAWING');await page.getByRole('button',{name:'筛选老师',exact:true}).click();
  await page.getByRole('button',{name:'查看老师',exact:true}).click();await page.reload();await expect(page.getByRole('heading',{name:'示例老师',exact:true})).toBeVisible();
  let release!:()=>void;const hold=new Promise<void>(resolve=>release=resolve);
  await page.route('**/api/v1/teachers/teacher-1',async route=>{await hold;await route.fallback();});
  await page.getByRole('button',{name:'邀请这位老师'}).click();await expect(page.getByText('正在读取可用资料…')).toBeVisible();await expect(page.getByText(/还没有可邀请/)).toHaveCount(0);release();
  await expect(page.getByRole('button',{name:'发送邀请',exact:true})).toBeEnabled();await page.getByRole('button',{name:'返回老师列表'}).click();await expect(page.locator('.teacher-card')).toHaveCount(1);
  await expandFilters(page);await page.getByRole('button',{name:'重置筛选',exact:true}).click();await expect(page).not.toHaveURL(/category=/);
});

test('未公开老师保存后仍可公开，浏览器后退取消时保留未保存输入',async({page})=>{
  const {writes}=await fixture(page,{loggedIn:true,published:false});await page.goto('/?view=public');await expect(page.locator('.request-card')).toHaveCount(10);
  await page.getByRole('button',{name:'本人资料',exact:true}).click();await page.locator('#field-displayName').fill('尚未保存');
  page.once('dialog',d=>d.dismiss());await page.evaluate(()=>history.back());await expect(page.locator('#field-displayName')).toHaveValue('尚未保存');await expect(page).toHaveURL(/view=profile/);
  page.once('dialog',d=>d.accept());await page.evaluate(()=>history.back());await expect(page.locator('.request-card')).toHaveCount(10);
  await page.getByRole('button',{name:'本人资料',exact:true}).click();await page.locator('#field-displayName').fill('保存后的名字');await page.getByRole('button',{name:'保存资料',exact:true}).click();
  await expect(page.getByRole('button',{name:'预览并公开',exact:true})).toBeEnabled();await expect(page.locator('#field-displayName')).toHaveValue('保存后的名字');expect(writes).toEqual(['/me/teacher-profile']);
});

test('返回目标不跳转外部网站，身份不符不会读取老师资料',async({page})=>{
  await fixture(page,{loggedIn:true});let teacherReads=0;page.on('request',req=>{if(req.url().includes('/api/v1/teachers'))teacherReads++;});
  await page.goto('/?view=teachers&teacher=teacher-1');await expect(page.locator('.request-card')).toHaveCount(10);expect(teacherReads).toBe(0);
  await page.goto('/?view=login&returnTo=https%3A%2F%2Fexample.com');await page.locator('#field-account').fill('ux_user');await page.locator('#field-password').fill('test-password');await page.getByRole('button',{name:'登录',exact:true}).click();
  await expect(page.locator('.request-card')).toHaveCount(10);expect(new URL(page.url()).hostname).toBe('127.0.0.1');
});
