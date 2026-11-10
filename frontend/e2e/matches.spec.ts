import {test,expect} from "@playwright/test";
import type {Page} from "@playwright/test";
import {execFileSync} from "node:child_process";
import {readdirSync,mkdirSync} from "node:fs";
import path from "node:path";

const root=path.resolve(import.meta.dirname,"../..");
const driverRoot=path.join(root,".tools/maven-repository/org/postgresql/postgresql");
const version=readdirSync(driverRoot).filter(v=>/^\d/.test(v)).sort().at(-1)!;
const driver=path.join(driverRoot,version,`postgresql-${version}.jar`);
type User={id:string;account:string;password:string};
const actors:User[]=[];
function fixture(action:string,value:string){return JSON.parse(execFileSync("java",["--class-path",driver,path.join(root,"backend/src/test/tools/BrowserFixture.java"),action,value],{encoding:"utf8",env:process.env}));}
function user(role:string):User{const result=fixture("create",role);actors.push(result);return result;}
async function login(page:Page,u:User){await page.goto("/");await page.getByLabel("账号",{exact:true}).fill(u.account);await page.getByLabel("密码",{exact:true}).fill(u.password);await page.getByRole("button",{name:"登录",exact:true}).click();await expect(page.getByRole("heading",{name:/本人基本资料|让合适的人认识你/})).toBeVisible();}
async function write(page:Page,u:User,url:string,body:unknown,method="POST",key=crypto.randomUUID()){
  const csrf=await(await page.request.get("/api/v1/auth/csrf")).json();
  const result=await page.request.fetch("/api/v1"+url,{method,headers:{[csrf.headerName]:csrf.token,"X-Account-Id":u.id,"Idempotency-Key":key},data:body});
  expect(result.ok(),await result.text()).toBeTruthy();return result.json();
}
async function prepare(ownerPage:Page,tutorPage:Page,operatorPage:Page){
  const owner=user("REQUESTER"),tutor=user("TUTOR"),operator=user("OPERATOR");
  await login(ownerPage,owner);await login(tutorPage,tutor);await login(operatorPage,operator);
  await write(ownerPage,owner,"/me/profile",{expectedVersion:0,displayName:"合成需求方"},"PATCH");
  const teacher=await write(tutorPage,tutor,"/me/teacher-profile",{expectedVersion:0,displayName:"合成绘画老师",categoryCodes:["DRAWING"],serviceModes:["ONLINE"],areaCodes:[],introduction:"只用于联调的老师自述",priceMin:null,priceMax:null,availabilityText:""},"PUT");
  await write(tutorPage,tutor,"/me/teacher-profile/publish",{expectedVersion:teacher.version});
  const draft=await write(ownerPage,owner,"/requests",{});
  const saved=await write(ownerPage,owner,`/requests/${draft.id}/draft`,{expectedVersion:draft.version,fields:{title:"合成撮合需求",categoryCode:"DRAWING",audienceStageCode:"ADULT",areaCode:"PS_PINGSHAN",goal:"希望学习绘画基础",budgetMin:"100",budgetMax:"200",durationHours:"1.5",frequency:{mode:"NEGOTIABLE"},availableSlots:[{weekday:1,startMinute:600,endMinute:660}]}},"PATCH");
  const submitted=await write(ownerPage,owner,`/requests/${draft.id}/submit`,{expectedVersion:saved.version,publisherDeclaration:"SELF_ADULT",declarationVersion:"publisher-v1"});
  await write(operatorPage,operator,`/operations/submissions/${submitted.submissionId}/decision`,{expectedVersion:0,decision:"APPROVE"});
  return {owner,tutor,request:draft.id};
}
async function progress(page:Page){await page.getByRole("button",{name:"撮合进度",exact:true}).click();await page.getByRole("button",{name:"查看撮合进度",exact:true}).click();}
async function apply(page:Page){await page.getByRole("button",{name:"浏览需求",exact:true}).click();await page.getByRole("button",{name:"查看需求",exact:true}).click();await page.getByRole("button",{name:"准备投递",exact:true}).click();await expect(page.getByText("只用于联调的老师自述")).toBeVisible();await page.getByRole("button",{name:"确认投递",exact:true}).click();}
async function invite(page:Page,requestId:string){await page.getByRole("button",{name:"发现老师",exact:true}).click();await page.getByRole("button",{name:"查看老师",exact:true}).click();await expect(page.getByText("面议",{exact:false})).toBeVisible();await page.getByRole("button",{name:"选择需求邀请",exact:true}).click();await page.getByLabel("选择本人招募中的需求").selectOption(requestId);await expect(page.getByRole("button",{name:"确认邀请",exact:true})).toBeEnabled();await page.getByRole("button",{name:"确认邀请",exact:true}).click();await expect(page.getByRole("heading",{name:"等待对方回应",exact:true})).toBeVisible();}
test.beforeAll(()=>{if(!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/))throw new Error("Dedicated _test database required");mkdirSync(path.join(root,".tools/stage4-browser"),{recursive:true});});
test.afterEach(()=>{for(const actor of actors.splice(0))fixture("cleanup",actor.id);});

test("老师发现与投递、双方快照、明确接受后互选且不分享联系方式",async({page,browser},info)=>{
  const teacherContext=await browser.newContext(),operatorContext=await browser.newContext();
  try{const teacherPage=await teacherContext.newPage(),operatorPage=await operatorContext.newPage();page.on("dialog",d=>d.accept());teacherPage.on("dialog",d=>d.accept());
    await prepare(page,teacherPage,operatorPage);await apply(teacherPage);await expect(teacherPage.getByRole("heading",{name:"等待对方回应",exact:true})).toBeVisible();
    await progress(page);await expect(page.getByRole("heading",{name:"等待我回应",exact:true})).toBeVisible();
    await page.screenshot({path:path.join(root,`.tools/stage4-browser/waiting-${info.project.name}.png`),fullPage:true});
    await page.getByRole("button",{name:"接受意向",exact:true}).click();await expect(page.getByRole("heading",{name:"已互选",exact:true})).toBeVisible();await expect(page.getByText("双方已明确互选，联系方式须经下方本轮授权后分享。")).toBeVisible();
    await teacherPage.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(teacherPage.getByRole("heading",{name:"已互选",exact:true})).toBeVisible();await expect(teacherPage.getByRole("button",{name:"撤回本人意向"})).toHaveCount(0);
    await page.screenshot({path:path.join(root,`.tools/stage4-browser/mutual-${info.project.name}.png`),fullPage:true});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  }finally{await teacherContext.close();await operatorContext.close();}
});
test("需求方邀请、老师拒绝后主动重发与撤回，不继承旧回应",async({page,browser})=>{
  const tc=await browser.newContext(),oc=await browser.newContext();try{const tp=await tc.newPage(),op=await oc.newPage();page.on("dialog",d=>d.accept());tp.on("dialog",d=>d.accept());const data=await prepare(page,tp,op);
    await invite(page,data.request);
    const tutorMatches=await(await tp.request.get('/api/v1/me/matches')).json();
    const ownerMatches=await(await page.request.get('/api/v1/me/matches')).json();
    expect(tutorMatches.items.map((m:{id:string})=>m.id)).toContain(ownerMatches.items[0].id);
    await progress(tp);await tp.getByRole("button",{name:"拒绝意向",exact:true}).click();await expect(tp.getByRole("heading",{name:"已拒绝",exact:true})).toBeVisible();
    await page.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(page.getByRole("button",{name:"选择需求邀请",exact:true})).toHaveCount(0);
    await tp.getByRole("button",{name:"准备投递",exact:true}).click();await tp.getByRole("button",{name:"确认投递",exact:true}).click();await expect(tp.getByRole("heading",{name:"等待对方回应",exact:true})).toBeVisible();await tp.getByRole("button",{name:"撤回本人意向",exact:true}).click();await expect(tp.getByRole("heading",{name:"已撤回",exact:true})).toBeVisible();
    await page.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(page.getByRole("heading",{name:"已撤回",exact:true})).toBeVisible();await expect(page.getByRole("button",{name:"接受意向",exact:true})).toHaveCount(0);
  }finally{await tc.close();await oc.close();}
});
test("投递响应丢失同键重试、到期停止公开后已有意向可回应",async({page,browser})=>{
  const tc=await browser.newContext(),oc=await browser.newContext();try{const tp=await tc.newPage(),op=await oc.newPage();page.on("dialog",d=>d.accept());tp.on("dialog",d=>d.accept());const data=await prepare(page,tp,op);
    let lost=false;await tp.route("**/api/v1/matches",async route=>{if(route.request().method()==="POST"&&!lost){lost=true;await route.fetch();await route.fulfill({status:200,contentType:'application/json',body:'{"match":'});}else await route.continue();});
    await apply(tp);await expect(tp.getByRole("button",{name:"重试同一次意向",exact:true})).toBeVisible();await tp.getByRole("button",{name:"重试同一次意向",exact:true}).click();await expect(tp.getByRole("heading",{name:"等待对方回应",exact:true})).toBeVisible();
    fixture("expire",data.request);const profile=await(await tp.request.get("/api/v1/me/profile")).json();await write(tp,data.tutor,"/me/teacher-profile/unpublish",{expectedVersion:profile.version});
    await progress(page);await expect(page.getByText("发起时的这版需求已到期，不再接收新意向。本次待回应意向仍可处理。")).toBeVisible();await expect(page.getByText("老师已停止公开，本次关系与当时资料保留。")).toBeVisible();await page.getByRole("button",{name:"接受意向",exact:true}).click();await expect(page.getByRole("heading",{name:"已互选",exact:true})).toBeVisible();
  }finally{await tc.close();await oc.close();}
});
test("接受响应丢失可同键恢复，旧页面撤回冲突须读取已互选结果",async({page,browser})=>{
  const tc=await browser.newContext(),oc=await browser.newContext();try{
    const tp=await tc.newPage(),op=await oc.newPage();page.on("dialog",d=>d.accept());tp.on("dialog",d=>d.accept());
    await prepare(page,tp,op);await apply(tp);await expect(tp.getByRole("heading",{name:"等待对方回应",exact:true})).toBeVisible();await progress(page);
    const keys:string[]=[];page.on('request',request=>{if(request.method()==='POST'&&request.url().endsWith('/accept'))keys.push(request.headers()['idempotency-key']);});
    let lost=false;await page.route("**/api/v1/matches/*/accept",async route=>{if(!lost){lost=true;await route.fetch();await route.abort("failed");}else await route.continue();});
    await page.getByRole("button",{name:"接受意向",exact:true}).click();await expect(page.getByRole("button",{name:"重试同一次回应",exact:true})).toBeVisible();
    await tp.getByRole("button",{name:"撤回本人意向",exact:true}).click();await expect(tp.getByRole("alert")).toContainText("意向已变化");
    await tp.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(tp.getByRole("heading",{name:"已互选",exact:true})).toBeVisible();
    let readLost=false;await page.route('**/api/v1/matches/*',async route=>{if(route.request().method()==='GET'&&!readLost){readLost=true;await route.abort('failed');}else await route.continue();});
    await page.getByRole("button",{name:"重试同一次回应",exact:true}).click();await expect(page.getByText('本次回应已成功，但最新进度未能刷新，可同键重试或重新读取。')).toBeVisible();
    await page.getByRole("button",{name:"重试同一次回应",exact:true}).click();await expect(page.getByRole("heading",{name:"已互选",exact:true})).toBeVisible();
    expect(keys).toHaveLength(3);expect(new Set(keys).size).toBe(1);
  }finally{await tc.close();await oc.close();}
});
