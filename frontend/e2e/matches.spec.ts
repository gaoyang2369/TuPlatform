import { expectMobileFit } from "./mobile-layout";
import { sampleResume } from "./resume-fixture";
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
async function login(page:Page,u:User){await page.goto("/?view=profile");await page.getByLabel("账号",{exact:true}).fill(u.account);await page.getByLabel("密码",{exact:true}).fill(u.password);await page.getByRole("button",{name:"登录",exact:true}).click();await expect(page.getByRole("heading",{name:/本人基本资料|让合适的人认识你/})).toBeVisible();}
async function write(page:Page,u:User,url:string,body:unknown,method="POST",key=crypto.randomUUID()){
  const csrf=await(await page.request.get("/api/v1/auth/csrf")).json();
  const result=await page.request.fetch("/api/v1"+url,{method,headers:{[csrf.headerName]:csrf.token,"X-Account-Id":u.id,"Idempotency-Key":key},data:body});
  expect(result.ok(),await result.text()).toBeTruthy();return result.json();
}
async function prepare(ownerPage:Page,tutorPage:Page,operatorPage:Page){
  const owner=user("REQUESTER"),tutor=user("TUTOR"),operator=user("OPERATOR");
  await login(ownerPage,owner);await login(tutorPage,tutor);await login(operatorPage,operator);
  await write(ownerPage,owner,"/me/profile",{expectedVersion:0,displayName:"合成需求方"},"PATCH");
  const teacher=await write(tutorPage,tutor,"/me/teacher-profile",{expectedVersion:0,...sampleResume,displayName:"合成绘画老师",categoryCodes:["DRAWING"],serviceModes:["ONLINE"],areaCodes:[],introduction:"只用于联调的老师自述",priceMin:null,priceMax:null,availabilityText:""},"PUT");
  await write(tutorPage,tutor,"/me/teacher-profile/publish",{expectedVersion:teacher.version});
  const draft=await write(ownerPage,owner,"/requests",{});
  const saved=await write(ownerPage,owner,`/requests/${draft.id}/draft`,{expectedVersion:draft.version,fields:{title:"合成撮合需求",categoryCode:"DRAWING",audienceStageCode:"ADULT",areaCode:"PS_PINGSHAN",goal:"希望学习绘画基础",budgetMin:"100",budgetMax:"200",durationHours:"1.5",frequency:{mode:"NEGOTIABLE"},availableSlots:[{weekday:1,startMinute:600,endMinute:660}]}},"PATCH");
  const submitted=await write(ownerPage,owner,`/requests/${draft.id}/submit`,{expectedVersion:saved.version,publisherDeclaration:"SELF_ADULT",declarationVersion:"publisher-v1"});
  await write(operatorPage,operator,`/operations/submissions/${submitted.submissionId}/decision`,{expectedVersion:0,decision:"APPROVE"});
  return {owner,tutor,request:draft.id};
}
async function progress(page:Page){await page.getByRole("button",{name:"撮合进度",exact:true}).click();await page.locator(".match-row").getByRole("button").click();}
async function apply(page:Page){await page.getByRole("button",{name:"浏览需求",exact:true}).click();await page.getByRole("button",{name:"查看需求",exact:true}).click();await page.getByRole("button",{name:"发出意向",exact:true}).click();await expect(page.getByText("只用于联调的老师自述")).toBeVisible();await page.getByRole("button",{name:"确认投递",exact:true}).click();}
async function invite(page:Page,requestId:string){await page.getByRole("button",{name:"发现老师",exact:true}).click();await page.getByRole("button",{name:"查看老师",exact:true}).click();await expect(page.getByRole("dialog").getByText("面议",{exact:false})).toBeVisible();await page.getByRole("button",{name:"邀请这位老师",exact:true}).click();await expect(page.getByRole("button",{name:"发送邀请",exact:true})).toBeEnabled();await page.getByRole("button",{name:"发送邀请",exact:true}).click();await expect(page.getByText("等待对方回应",{exact:true})).toBeVisible();}
test.beforeAll(()=>{if(!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/))throw new Error("Dedicated _test database required");mkdirSync(path.join(root,".tools/stage4-browser"),{recursive:true});});
test.afterEach(()=>{for(const actor of actors.splice(0))fixture("cleanup",actor.id);});

test("老师发现与投递、双方快照、接受后互选，未填写联系方式可补充",async({page,browser},info)=>{
  const teacherContext=await browser.newContext(),operatorContext=await browser.newContext();
  try{const teacherPage=await teacherContext.newPage(),operatorPage=await operatorContext.newPage();page.on("dialog",d=>d.accept());teacherPage.on("dialog",d=>d.accept());
    await prepare(page,teacherPage,operatorPage);await apply(teacherPage);await expect(teacherPage.getByText("等待对方回应",{exact:true})).toBeVisible();
    await progress(page);await expect(page.getByText("等待我回应",{exact:true})).toBeVisible();
    await page.screenshot({path:path.join(root,`.tools/stage4-browser/waiting-${info.project.name}.png`),fullPage:true});
    await page.getByRole("button",{name:"接受意向",exact:true}).click();await page.getByRole("dialog").getByRole("button",{name:"接受意向",exact:true}).click();await expect(page.getByText("已互选",{exact:true})).toBeVisible();await expect(page.getByText("补充手机号或微信，让对方能联系到你。")).toBeVisible();
    await teacherPage.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(teacherPage.getByText("已互选",{exact:true})).toBeVisible();await expect(teacherPage.getByRole("button",{name:"撤回意向"})).toHaveCount(0);
    await page.screenshot({path:path.join(root,`.tools/stage4-browser/mutual-${info.project.name}.png`),fullPage:true});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  }finally{await teacherContext.close();await operatorContext.close();}
});
test("直接邀请撤回后重发，老师拒绝后不能重复邀请",async({page,browser})=>{
  const tc=await browser.newContext(),oc=await browser.newContext();try{const tp=await tc.newPage(),op=await oc.newPage();page.on("dialog",d=>d.accept());tp.on("dialog",d=>d.accept());const data=await prepare(page,tp,op);
    await invite(page,data.request);
    const tutorMatches=await(await tp.request.get('/api/v1/me/matches')).json();
    const ownerMatches=await(await page.request.get('/api/v1/me/matches')).json();
    expect(tutorMatches.items.map((m:{id:string})=>m.id)).toContain(ownerMatches.items[0].id);
    await page.getByRole("button",{name:"撤回意向",exact:true}).click();await page.getByRole("dialog").getByRole("button",{name:"撤回意向",exact:true}).click();await expect(page.getByText("已撤回",{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"邀请这位老师",exact:true}).click();await page.getByRole("button",{name:"发送邀请",exact:true}).click();await expect(page.getByText("等待对方回应",{exact:true})).toBeVisible();
    await progress(tp);await tp.getByRole("button",{name:"婉拒",exact:true}).click();await tp.getByRole("dialog").getByRole("button",{name:"婉拒意向",exact:true}).click();await expect(tp.getByText("已拒绝",{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(page.getByRole("button",{name:"邀请这位老师",exact:true})).toHaveCount(0);
    await expect(tp.getByRole("button",{name:"我对这个需求感兴趣",exact:true})).toHaveCount(0);
  }finally{await tc.close();await oc.close();}
});
test("投递响应丢失同键重试、到期停止公开后已有意向可回应",async({page,browser})=>{
  const tc=await browser.newContext(),oc=await browser.newContext();try{const tp=await tc.newPage(),op=await oc.newPage();page.on("dialog",d=>d.accept());tp.on("dialog",d=>d.accept());const data=await prepare(page,tp,op);
    let lost=false;await tp.route("**/api/v1/matches",async route=>{if(route.request().method()==="POST"&&!lost){lost=true;await route.fetch();await route.fulfill({status:200,contentType:'application/json',body:'{"match":'});}else await route.continue();});
    await apply(tp);await expect(tp.getByRole("button",{name:"重试同一次意向",exact:true})).toBeVisible();await tp.getByRole("button",{name:"重试同一次意向",exact:true}).click();await expect(tp.getByText("等待对方回应",{exact:true})).toBeVisible();
    fixture("expire",data.request);const profile=await(await tp.request.get("/api/v1/me/profile")).json();await write(tp,data.tutor,"/me/teacher-profile/unpublish",{expectedVersion:profile.version});
    await progress(page);await expect(page.locator(".record-note")).toContainText("原需求已到期。老师已停止公开。");await page.getByRole("button",{name:"接受意向",exact:true}).click();await page.getByRole("dialog").getByRole("button",{name:"接受意向",exact:true}).click();await expect(page.getByText("已互选",{exact:true})).toBeVisible();
  }finally{await tc.close();await oc.close();}
});
test("接受响应丢失可同键恢复，旧页面撤回冲突须读取已互选结果",async({page,browser})=>{
  const tc=await browser.newContext(),oc=await browser.newContext();try{
    const tp=await tc.newPage(),op=await oc.newPage();page.on("dialog",d=>d.accept());tp.on("dialog",d=>d.accept());
    await prepare(page,tp,op);await apply(tp);await expect(tp.getByText("等待对方回应",{exact:true})).toBeVisible();await progress(page);
    const keys:string[]=[];page.on('request',request=>{if(request.method()==='POST'&&request.url().endsWith('/accept'))keys.push(request.headers()['idempotency-key']);});
    let lost=false;await page.route("**/api/v1/matches/*/accept",async route=>{if(!lost){lost=true;await route.fetch();await route.abort("failed");}else await route.continue();});
    await page.getByRole("button",{name:"接受意向",exact:true}).click();await page.getByRole("dialog").getByRole("button",{name:"接受意向",exact:true}).click();await expect(page.getByRole("button",{name:"重试同一次回应",exact:true})).toBeVisible();
    await tp.getByRole("button",{name:"撤回意向",exact:true}).click();await tp.getByRole("dialog").getByRole("button",{name:"撤回意向",exact:true}).click();await expect(tp.getByRole("alert")).toContainText("意向已变化");
    await tp.getByRole("button",{name:"读取最新进度",exact:true}).click();await expect(tp.getByText("已互选",{exact:true})).toBeVisible();
    let readLost=false;await page.route('**/api/v1/matches/*',async route=>{if(route.request().method()==='GET'&&!readLost){readLost=true;await route.abort('failed');}else await route.continue();});
    await page.getByRole("button",{name:"重试同一次回应",exact:true}).click();await expect(page.getByText('回应已保存，最新进度暂未更新。',{exact:false})).toBeVisible();
    await page.getByRole("button",{name:"重试同一次回应",exact:true}).click();await expect(page.getByText("已互选",{exact:true})).toBeVisible();
    expect(keys).toHaveLength(3);expect(new Set(keys).size).toBe(1);
  }finally{await tc.close();await oc.close();}
});

test("裁剪上传头像后，发现、抽屉和双方撮合展示对应账号", async ({ page, browser }) => {
  const teacherContext = await browser.newContext({ viewport: page.viewportSize() ?? undefined });
  const adminContext = await browser.newContext();
  const tp = await teacherContext.newPage(), admin = await adminContext.newPage();
  try {
    const { owner, tutor } = await prepare(page, tp, admin);
    const image = await page.evaluate(() => {
      const c = document.createElement("canvas"); c.width = 600; c.height = 300;
      const g = c.getContext("2d")!;
      g.fillStyle = "#2563eb"; g.fillRect(0, 0, 300, 300);
      g.fillStyle = "#ea580c"; g.fillRect(300, 0, 300, 300);
      return c.toDataURL("image/png").split(",")[1]!;
    });
    async function upload(actorPage: Page, isTeacher: boolean) {
      await actorPage.goto("/?view=profile");
      const panel = actorPage.getByRole("region", { name: "个人头像" });
      await expect(panel).toContainText(isTeacher ? "上传清晰的本人正面生活照或证件照" : "头像选填");
      const picker = panel.locator('input[type=file]');
      await picker.setInputFiles({ name: "large.png", mimeType: "image/png", buffer: Buffer.alloc(2 * 1024 * 1024 + 1) });
      await expect(panel.getByRole("alert")).toContainText("不能超过 2 MB");
      await picker.setInputFiles({ name: "mock.png", mimeType: "image/png", buffer: Buffer.from("invalid") });
      await expect(panel.getByRole("alert")).toContainText("无法打开");
      await actorPage.locator("#field-displayName").fill("尚未保存的展示名");
      await picker.setInputFiles({ name: "sample.png", mimeType: "image/png", buffer: Buffer.from(image, "base64") });
      const crop = actorPage.viewportSize()!.width < 768 ? actorPage.getByRole("dialog", { name: "裁剪头像" }) : panel;
      const canvas = crop.locator("canvas");
      await expect(canvas).toBeVisible();
      await expectMobileFit(actorPage);
      await crop.getByRole("slider").fill("2");
      await expect(crop).toContainText("200%");
      await canvas.scrollIntoViewIfNeeded();
      const box = (await canvas.boundingBox())!;
      expect(Math.abs(box.width - box.height)).toBeLessThan(1);
      expect(await crop.evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
      await actorPage.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await actorPage.mouse.down();
      await actorPage.mouse.move(box.x + box.width * 0.8, box.y + box.height / 2);
      await actorPage.mouse.up();
      const pixel = await canvas.evaluate(c => [...c.getContext("2d")!.getImageData(170, 150, 1, 1).data]);
      expect(pixel[2]).toBeGreaterThan(pixel[0]!);
      await crop.getByRole("button", { name: "保存头像" }).click();
      await expect(panel.getByRole("status")).toHaveText("头像已更新。");
      await expect(actorPage.locator("#field-displayName")).toHaveValue("尚未保存的展示名");
      const navImage = actorPage.getByRole("button", { name: "本人资料", exact: true }).locator("img");
      await expect.poll(() => navImage.evaluate(el => el.naturalWidth)).toBe(300);
      const url = (await navImage.getAttribute("src"))!;
      expect((await (await actorPage.request.get("/api/v1/me")).json()).avatar_url).toBe(url);
      await actorPage.reload();
      await expect(actorPage.locator("#field-displayName")).not.toHaveValue("尚未保存的展示名");
      await expect(navImage).toHaveAttribute("src", url);
      return url;
    }
    const teacherUrl = await upload(tp, true), ownerUrl = await upload(page, false);
    expect((await tp.request.get(ownerUrl)).status()).toBe(404);
    const csrf = await (await page.request.get("/api/v1/auth/csrf")).json();
    const multipart = { file: { name: "test.png", mimeType: "image/png", buffer: Buffer.from(image, "base64") } };
    const endpoint = "/api/v1/upload/avatar";
    expect((await page.request.post(endpoint, { multipart, headers: { "X-Account-Id": owner.id } })).status()).toBe(403);
    expect((await page.request.post(endpoint, { multipart, headers: { [csrf.headerName]: csrf.token, "X-Account-Id": tutor.id } })).status()).toBe(409);
    const headers = { [csrf.headerName]: csrf.token, "X-Account-Id": owner.id };
    const oversized = { file: { ...multipart.file, buffer: Buffer.alloc(2 * 1024 * 1024 + 1) } };
    expect((await page.request.post(endpoint, { headers, multipart: oversized })).status()).toBe(413);
    const wrongType = { file: { ...multipart.file, mimeType: "image/webp" } };
    expect((await page.request.post(endpoint, { headers, multipart: wrongType })).status()).toBe(422);
    expect((await (await page.request.get("/api/v1/me/profile")).json()).avatar_url).toBe(ownerUrl);
    await page.getByRole("button", { name: "发现老师", exact: true }).click();
    const card = page.locator(".teacher-card").filter({ hasText: "合成绘画老师" });
    await expect(card.locator("img")).toHaveAttribute("src", teacherUrl);
    await expect.poll(() => card.locator("img").evaluate(el => el.naturalWidth)).toBe(300);
    await card.getByRole("button", { name: "查看老师" }).click();
    const drawer = page.getByRole("dialog");
    await expect(drawer.locator("img").first()).toHaveAttribute("src", teacherUrl);
    await drawer.getByRole("button", { name: "邀请这位老师" }).click();
    await page.getByRole("button", { name: "发送邀请", exact: true }).click();
    await expect(page.getByText("等待对方回应", { exact: true })).toBeVisible();
    const updated = await page.request.post(endpoint, { headers, multipart });
    expect(updated.ok()).toBeTruthy();
    const updatedOwnerUrl = (await updated.json()).avatar_url;
    expect(updatedOwnerUrl).not.toBe(ownerUrl);
    expect((await page.request.get(ownerUrl)).status()).toBe(404);
    for (const [viewer, url] of [[page, teacherUrl], [tp, updatedOwnerUrl]] as const) {
      await viewer.goto("/?view=matches");
      const avatar = viewer.locator(".match-row img");
      await expect(avatar).toHaveAttribute("src", url);
      await expect.poll(() => avatar.evaluate(el => el.naturalWidth)).toBe(300);
      await viewer.route("**" + url, route => route.abort());
      await viewer.reload();
      await expect(viewer.locator(".match-row span[aria-hidden=true]").first()).toHaveText("合");
    }
  } finally {
    await teacherContext.close(); await adminContext.close();
  }
});
