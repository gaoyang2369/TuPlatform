import {test,expect} from "@playwright/test";
import path from "node:path";

test("老师卡片展示教学方向，十位分页、失败重试及详情返回保留状态",async({page},info)=>{
  const teachers=Array.from({length:23},(_,i)=>({
    id:`teacher-${i}`,profileVersion:1,displayName:`合成老师 ${String(i+1).padStart(2,"0")}`,
    categoryCodes:[i<13?"EXTRACURRICULAR_STUDY":"DANCE"],serviceModes:["ONLINE"],areaCodes:[],
    introduction:i===1?"面向小学三至六年级，擅长数学思维和应用题，重视理解与表达。":i<13?"背景：合成教学经历。\n方向：初中数学与物理\n年级范围：初一至初三\n科目：数学、物理\n教学方式：通过例题讲解与复盘练习。":"方向：成人现代舞入门与节奏训练。\n教学方式：分解动作，循序练习。",
    priceMin:i===2?null:"100.00",priceMax:i===2?null:"150.00",availabilityText:"周末可协商"
  }));
  const queries:URLSearchParams[]=[],detailCalls:string[]=[];let failNext=false;
  await page.route("**/api/v1/**",async route=>{
    const url=new URL(route.request().url()),endpoint=url.pathname.replace("/api/v1","");
    if(endpoint==="/me")return route.fulfill({json:{id:"requester",account:"requester",email:"test@example.invalid",emailVerified:true,businessIdentity:"REQUESTER",operatorGranted:false,profileVersion:0}});
    if(endpoint==="/me/profile")return route.fulfill({json:{displayName:"合成需求方",version:0,teacher:null}});
    if(endpoint==="/profile-options")return route.fulfill({json:{categoryCodes:["DANCE","EXTRACURRICULAR_STUDY"],serviceModes:["ONLINE","OFFLINE"],areaCodes:["PS_PINGSHAN"]}});
    if(endpoint==="/teachers"){
      queries.push(url.searchParams);
      if(failNext){failNext=false;return route.fulfill({status:503,json:{message:"暂时无法读取，请重试。"}});}
      const selected=teachers.filter(t=>(!url.searchParams.get("categoryCode")||t.categoryCodes.includes(url.searchParams.get("categoryCode")!))&&(!url.searchParams.get("serviceMode")||t.serviceModes.includes(url.searchParams.get("serviceMode")!))&&(!url.searchParams.get("areaCode")||t.areaCodes.includes(url.searchParams.get("areaCode")!)));
      const offset=Number(url.searchParams.get("cursor")||0),items=selected.slice(offset,offset+10);
      return route.fulfill({json:{items,nextCursor:offset+10<selected.length?String(offset+10):null}});
    }
    if(endpoint.startsWith("/teachers/")){const id=endpoint.split("/").at(-1)!;detailCalls.push(id);return route.fulfill({json:teachers.find(t=>t.id===id)});}
    return route.fulfill({status:404,json:{message:"Unexpected test endpoint"}});
  });
  await page.goto("/");await page.getByRole("navigation",{name:"主要任务"}).getByRole("button",{name:"发现老师",exact:true}).click();
  const cards=page.locator(".teacher-card"),paging=page.getByRole("navigation",{name:"老师分页"});
  await expect(cards).toHaveCount(10);await expect(paging.getByRole("button",{name:"上一页"})).toBeDisabled();
  await expect(page.getByRole("button",{name:"加载更多老师"})).toHaveCount(0);expect(detailCalls).toHaveLength(0);
  await expect(cards.first().locator(".teacher-introduction")).toContainText("初一至初三");await expect(cards.first().locator(".teacher-introduction")).toContainText("数学、物理");await expect(cards.first()).not.toContainText("合成教学经历");
  await expect(cards.nth(1)).toContainText("小学三至六年级");await expect(cards.nth(2)).toContainText("面议");
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.screenshot({path:path.resolve(import.meta.dirname,`../../.tools/teacher-discovery/cards-${info.project.name}.png`),fullPage:false});
  failNext=true;await paging.getByRole("button",{name:"下一页"}).click();await expect(page.getByRole("alert")).toContainText("暂时无法读取");await expect(paging).toContainText("第 1 页");await expect(cards.first()).toContainText("合成老师 01");
  await page.getByRole("button",{name:"重新读取",exact:true}).click();await expect(paging).toContainText("第 2 页");await expect(cards).toHaveCount(10);await expect(cards.first()).toContainText("合成老师 11");
  await paging.getByRole("button",{name:"下一页"}).click();await expect(cards).toHaveCount(3);await expect(paging).toContainText("第 3 页");await expect(paging.getByRole("button",{name:"下一页"})).toBeDisabled();
  await paging.getByRole("button",{name:"上一页"}).click();await expect(cards.first()).toContainText("合成老师 11");
  await cards.first().getByRole("button",{name:"查看老师"}).click();await expect(page.getByRole("heading",{name:"合成老师 11",exact:true})).toBeVisible();await expect(page.getByRole("button",{name:"邀请这位老师",exact:true})).toBeVisible();
  const reads=queries.length;await page.getByRole("button",{name:"返回老师列表",exact:true}).click();await expect(paging).toContainText("第 2 页");await expect(cards).toHaveCount(10);expect(queries.length).toBeGreaterThanOrEqual(reads);expect(detailCalls).toEqual(["teacher-10"]);
  if(await page.getByRole("button",{name:"筛选条件",exact:true}).isVisible())await page.getByRole("button",{name:"筛选条件",exact:true}).click();
  await page.getByRole("combobox",{name:"类别",exact:true}).selectOption("EXTRACURRICULAR_STUDY");await page.getByRole("button",{name:"筛选老师",exact:true}).click();await expect(paging).toContainText("第 1 页");
  if(await page.getByRole("button",{name:"筛选条件",exact:true}).isVisible())await page.getByRole("button",{name:"筛选条件",exact:true}).click();
  await page.getByRole("combobox",{name:"方式",exact:true}).selectOption("OFFLINE");await paging.getByRole("button",{name:"下一页"}).click();await expect(cards).toHaveCount(3);expect(queries.at(-1)?.get("serviceMode")).toBeNull();expect(queries.at(-1)?.get("categoryCode")).toBe("EXTRACURRICULAR_STUDY");
  await page.getByRole("button",{name:"筛选老师",exact:true}).click();await expect(cards).toHaveCount(0);await expect(page.getByText("暂无符合条件的公开老师，可以稍后再来查看。")).toBeVisible();await expect(paging).toHaveCount(0);
  if(await page.getByRole("button",{name:"筛选条件",exact:true}).isVisible())await page.getByRole("button",{name:"筛选条件",exact:true}).click();
  await page.getByRole("combobox",{name:"方式",exact:true}).selectOption("ONLINE");await page.getByRole("combobox",{name:"类别",exact:true}).selectOption("DANCE");await page.getByRole("button",{name:"筛选老师",exact:true}).click();await expect(cards).toHaveCount(10);await expect(cards.first()).toContainText("成人现代舞入门");await expect(paging.getByRole("button",{name:"下一页"})).toBeDisabled();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
