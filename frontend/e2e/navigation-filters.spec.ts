import { test,expect } from "@playwright/test";
import path from "node:path";

for(const identity of ["REQUESTER","TUTOR"] as const) {
  test(identity+" 通过昵称进入资料，联系方式在资料内且离开保护仍生效",async({page},info)=>{
    let contactWrites=0;
    await page.route("**/api/v1/**",async route=>{
      const endpoint=new URL(route.request().url()).pathname.replace("/api/v1","");
      if(endpoint==="/me")return route.fulfill({json:{id:"00000000-0000-4000-8000-000000000030",account:"nav_test",email:"nav@example.invalid",emailVerified:true,businessIdentity:identity,operatorGranted:false,profileVersion:0}});
      if(endpoint==="/me/profile")return route.fulfill({json:{displayName:"昵称示例",version:0,teacher:identity==='TUTOR'?{categoryCodes:[],serviceModes:[],areaCodes:[],introduction:"",priceMin:null,priceMax:null,availabilityText:"",public:false,version:0}:null}});
      if(endpoint==="/profile-options")return route.fulfill({json:{categoryCodes:[],serviceModes:[],areaCodes:[]}});
      if(endpoint==="/publishing-options")return route.fulfill({json:{categories:[],serviceModes:[]}});
      if(endpoint==="/teachers"||endpoint==="/public/requests")return route.fulfill({json:{items:[],nextCursor:null}});
      if(endpoint==="/me/contacts"){if(route.request().method()!=="GET")contactWrites++;return route.fulfill({json:{version:0,phone:null,wechat:null,fieldVersions:{phone:0,wechat:0}}});}
      return route.fulfill({status:404,json:{message:"Unexpected test endpoint"}});
    });
    await page.goto("/");
    const nav=page.getByRole("navigation",{name:"主要任务"}),entry=page.getByRole("banner").getByRole("button",{name:"本人资料",exact:true});
    await expect(entry).toContainText("昵称示例");await expect(nav.getByRole("button",{name:"本人资料",exact:true})).toHaveCount(0);await expect(nav.getByRole("button",{name:"私密联系方式",exact:true})).toHaveCount(0);
    const target=identity==='REQUESTER'?'发现老师':'浏览需求',hidden=identity==='REQUESTER'?'浏览需求':'发现老师';
    await expect(nav.getByRole("button",{name:hidden,exact:true})).toHaveCount(0);await nav.getByRole("button",{name:target,exact:true}).click();await expect(page.getByRole("heading",{name:target,exact:true})).toBeVisible();
    await entry.click();await expect(page.locator("#field-displayName")).toHaveValue("昵称示例");
    const tabs=page.getByRole("navigation",{name:"个人资料栏目"});await tabs.getByRole("button",{name:"私密联系方式",exact:true}).click();
    await expect(page.getByRole("heading",{name:"本人私密联系方式",exact:true})).toBeVisible();await expect(entry).toHaveAttribute("aria-current","page");
    await page.screenshot({path:path.resolve(import.meta.dirname,"../../.tools/navigation-filters/contacts-"+identity+"-"+info.project.name+".png"),fullPage:true});
    await page.locator("#contact-phone").fill("13800000000");page.once("dialog",dialog=>dialog.dismiss());await nav.getByRole("button",{name:target,exact:true}).click();
    await expect(page.locator("#contact-phone")).toHaveValue("13800000000");
    page.once("dialog",dialog=>dialog.accept());await entry.click();await expect(page.locator("#field-displayName")).toBeVisible();await expect(page.locator("#contact-phone")).toHaveCount(0);
    expect(contactWrites).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  });
}

test("浏览列表每页10条、组合筛选与详情返回保留页码",async({page},info)=>{
  const data=Array.from({length:23},(_,index)=>({id:"request-"+index,submissionId:"submission-"+index,title:"分页需求 "+String(index+1).padStart(2,"0"),categoryCode:"EXTRACURRICULAR_STUDY",audienceStageCode:"SENIOR",grade:index<13?"高一":"高二",subject:index<13?"数学、物理":"英语",districtCode:"SZ_PINGSHAN",locationText:"测试花园",serviceMode:"OFFLINE",budgetMin:"100",budgetMax:"150",durationHours:"2",goal:"合成需求说明",publisherDisplayName:"合成需求方"}));
  const queries:URLSearchParams[]=[];
  await page.route("**/api/v1/**",async route=>{
    const url=new URL(route.request().url()),endpoint=url.pathname.replace("/api/v1","");
    if(endpoint==="/me")return route.fulfill({status:401,json:{code:"LOGIN_REQUIRED",message:"请先登录"}});
    if(endpoint==="/publishing-options")return route.fulfill({json:{categories:["DRAWING","EXTRACURRICULAR_STUDY"],districts:[{code:"SZ_PINGSHAN",label:"坪山区"}],serviceModes:["ONLINE","OFFLINE"]}});
    if(endpoint==="/public/requests"){
      queries.push(url.searchParams);
      const filtered=data.filter(item=>(!url.searchParams.get("grade")||item.grade===url.searchParams.get("grade"))&&(!url.searchParams.get("subject")||item.subject.includes(url.searchParams.get("subject")!))&&(!url.searchParams.get("categoryCode")||item.categoryCode===url.searchParams.get("categoryCode")));
      const start=Number(url.searchParams.get("cursor")||0);
      return route.fulfill({json:{items:filtered.slice(start,start+10),nextCursor:start+10<filtered.length?String(start+10):null}});
    }
    if(endpoint.startsWith("/public/requests/"))return route.fulfill({json:data.find(item=>item.id===endpoint.split("/").at(-1))});
    return route.fulfill({status:404,json:{message:"Unexpected test endpoint"}});
  });
  await page.goto("/");await page.getByRole("button",{name:"浏览需求",exact:true}).click();
  const cards=page.locator(".request-card"),paging=page.getByRole("navigation",{name:"需求分页"});
  await expect(cards).toHaveCount(10);await expect(paging.getByRole("button",{name:"上一页"})).toBeDisabled();await expect(page.getByRole("button",{name:"加载更多需求"})).toHaveCount(0);
  await paging.getByRole("button",{name:"下一页"}).click();await expect(paging).toContainText("第 2 页");await expect(cards.first()).toContainText("分页需求 11");await expect(page.getByRole("heading",{name:"分页需求 01",exact:true})).toHaveCount(0);
  await paging.getByRole("button",{name:"下一页"}).click();await expect(cards).toHaveCount(3);await expect(paging).toContainText("第 3 页");await expect(paging.getByRole("button",{name:"下一页"})).toBeDisabled();
  await paging.getByRole("button",{name:"上一页"}).click();await expect(cards).toHaveCount(10);
  await cards.first().getByRole("button",{name:"查看需求"}).click();await expect(page.getByRole("button",{name:"返回需求列表",exact:true})).toHaveCount(1);
  await page.getByRole("button",{name:"刷新需求",exact:true}).click();await expect(page.getByRole("heading",{name:"分页需求 11",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"返回需求列表",exact:true}).click();await expect(paging).toContainText("第 2 页");await expect(cards.first()).toContainText("分页需求 11");
  await expect(page.getByRole("button",{name:"返回需求列表",exact:true})).toHaveCount(0);
  if(await page.getByRole("button",{name:"筛选条件",exact:true}).isVisible())await page.getByRole("button",{name:"筛选条件",exact:true}).click();
  await page.getByRole("combobox",{name:"区域",exact:true}).selectOption("SZ_PINGSHAN");await page.getByRole("combobox",{name:"年级",exact:true}).fill("高一");await page.getByRole("combobox",{name:"科目",exact:true}).fill("物理");
  await page.getByRole("button",{name:"筛选需求",exact:true}).click();await expect(paging).toContainText("第 1 页");await expect(cards).toHaveCount(10);
  expect(queries.at(-1)?.get("districtCode")).toBe("SZ_PINGSHAN");expect(queries.at(-1)?.get("grade")).toBe("高一");expect(queries.at(-1)?.get("subject")).toBe("物理");
  await page.screenshot({path:path.resolve(import.meta.dirname,"../../.tools/navigation-filters/requests-"+info.project.name+".png"),fullPage:true});
  if(await page.getByRole("button",{name:"筛选条件",exact:true}).isVisible())await page.getByRole("button",{name:"筛选条件",exact:true}).click();
  await page.getByRole("combobox",{name:"年级",exact:true}).fill("高二");await paging.getByRole("button",{name:"下一页"}).click();await expect(cards).toHaveCount(3);expect(queries.at(-1)?.get("grade")).toBe("高一");
  await page.getByRole("button",{name:"筛选需求",exact:true}).click();await expect(cards).toHaveCount(0);await expect(page.getByText("暂无符合条件的公开需求，可以调整筛选条件。")).toBeVisible();
  if(await page.getByRole("button",{name:"筛选条件",exact:true}).isVisible())await page.getByRole("button",{name:"筛选条件",exact:true}).click();
  await page.getByRole("combobox",{name:"类别",exact:true}).selectOption("DRAWING");await expect(page.getByRole("combobox",{name:"年级",exact:true})).toHaveValue("");await expect(page.getByRole("combobox",{name:"科目",exact:true})).toBeDisabled();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
