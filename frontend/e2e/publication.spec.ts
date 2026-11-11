import {test,expect} from "@playwright/test";
import type {Page,BrowserContext} from "@playwright/test";
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
function user(role:string):User{const actor=fixture("create",role);actors.push(actor);return actor;}
async function login(page:Page,actor:User){await page.goto("/?view=profile");await page.getByLabel("账号",{exact:true}).fill(actor.account);await page.getByLabel("密码",{exact:true}).fill(actor.password);await page.getByRole("button",{name:"登录",exact:true}).click();await expect(page.getByRole("button",{name:"本人资料",exact:true})).toBeVisible();}
async function prepared(page:Page){
  const owner=user("REQUESTER");await login(page,owner);
  await page.locator("#field-displayName").fill("合成需求方展示名");await page.getByRole("button",{name:"保存资料",exact:true}).click();await expect(page.locator(".feedback-toast").getByRole("status")).toContainText("资料已保存");
  await page.getByRole("button",{name:"我的需求",exact:true}).click();await page.getByRole("button",{name:"新建需求草稿"}).click();
  await page.getByLabel("需求标题",{exact:false}).fill("合成绘画公开需求");await page.getByLabel("服务类别",{exact:false}).selectOption("DRAWING");await page.getByLabel("服务对象阶段").selectOption("ADULT");await page.getByLabel("小区或附近地标",{exact:false}).fill("坪山区 · 合成花园");await page.getByLabel("目标与现状",{exact:false}).fill("希望从绘画基础开始，纯合成资料。");
  await page.getByRole("button",{name:"保存并下一步"}).click();await page.getByLabel("预算下限").fill("1000.01");await page.getByLabel("预算上限").fill("1800.99");await page.getByLabel("单次时长（小时）",{exact:true}).fill("1.5");await page.getByRole("combobox",{name:"频率",exact:true}).selectOption("NEGOTIABLE");await page.getByRole("radio",{name:"填写精确时间",exact:true}).check();await page.getByRole("button",{name:"添加时段"}).click();await page.getByRole("button",{name:"保存并预览"}).click();
  await expect(page.getByRole("button",{name:"确认提交审核",exact:true})).toBeDisabled();
  return owner;
}
async function submit(page:Page){await page.getByRole("radio",{name:"我为已满18岁的本人发布本条需求。",exact:true}).check();await page.getByRole("button",{name:"确认提交审核",exact:true}).click();await expect(page.getByRole("heading",{name:"需求进度",exact:true})).toBeVisible();await expect(page.getByText("待审核",{exact:true})).toBeVisible();}
async function csrfHeaders(page:Page,actor:User){const csrf=await(await page.request.get("/api/v1/auth/csrf")).json();return {[csrf.headerName]:csrf.token,"X-Account-Id":actor.id};}
async function latestRequest(page:Page){const result=await(await page.request.get("/api/v1/me/requests")).json();return result.items[0];}
async function operatorPage(context:BrowserContext,actor:User){const page=await context.newPage();await login(page,actor);await page.getByRole("button",{name:"需求审核",exact:true}).click();await page.getByRole("button",{name:"核对提交",exact:true}).click();await expect(page.getByRole("heading",{name:"合成绘画公开需求",exact:true})).toBeVisible();return page;}
test.beforeAll(()=>{if(!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/))throw new Error("Dedicated _test database required");mkdirSync(path.join(root,".tools/stage3-browser"),{recursive:true});});
test.afterEach(()=>{for(const actor of actors.splice(0))fixture("cleanup",actor.id);});

test("提交、驳回修改重提、审核公开、匿名浏览及到期不可见",async({page,browser},info)=>{
  await prepared(page);await submit(page);const original=await latestRequest(page);
  const anonymous=await browser.newContext(),operations=await browser.newContext();
  try {
    const visitor=await anonymous.newPage();await visitor.goto("/");await visitor.getByRole("button",{name:"浏览需求",exact:true}).click();await expect(visitor.getByText("暂无符合条件的公开需求，可以调整筛选条件。")).toBeVisible();
    const reviewer=await operatorPage(operations,user("OPERATOR"));
    await reviewer.getByRole("radio",{name:"驳回并说明原因",exact:true}).check();await reviewer.getByRole("button",{name:"核对并确认审核"}).click();await expect(reviewer.getByRole("alert")).toContainText("驳回前请填写理由");
    await reviewer.getByLabel("驳回理由",{exact:true}).fill("请补充学习目标，再次提交。");await reviewer.getByRole("button",{name:"核对并确认审核"}).click();await reviewer.getByRole("button",{name:"确认驳回",exact:true}).click();await expect(reviewer.getByText("已驳回",{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"刷新进度",exact:true}).click();await expect(page.getByText("驳回理由：请补充学习目标，再次提交。",{exact:true})).toBeVisible();await page.getByRole("button",{name:"修改后重新提交",exact:true}).click();await page.getByLabel("目标与现状",{exact:false}).fill("已补充：希望能够独立完成基础静物绘画。");await page.getByRole("button",{name:"保存并下一步"}).click();await page.getByRole("button",{name:"保存并预览"}).click();await expect(page.getByRole("button",{name:"确认提交审核",exact:true})).toBeDisabled();await submit(page);
    const second=await latestRequest(page);expect(second.latestSubmission.id).not.toBe(original.latestSubmission.id);
    await reviewer.getByRole("button",{name:"返回审核队列",exact:true}).click();await reviewer.getByRole("button",{name:"核对提交",exact:true}).click();await expect(reviewer.getByText("上次驳回理由：请补充学习目标，再次提交。",{exact:true})).toBeVisible();await expect(reviewer.getByText("已补充：希望能够独立完成基础静物绘画。",{exact:true})).toBeVisible();
    expect(await reviewer.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await reviewer.screenshot({path:path.join(root,`.tools/stage3-browser/review-${info.project.name}.png`),fullPage:true});
    await reviewer.getByRole("radio",{name:"通过审核并公开",exact:true}).check();await reviewer.getByRole("button",{name:"核对并确认审核"}).click();await reviewer.getByRole("button",{name:"确认通过并公开",exact:true}).click();await expect(reviewer.getByText("已通过",{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"刷新进度",exact:true}).click();await expect(page.getByText("招募中",{exact:true})).toBeVisible();await expect(page.getByLabel("需求标题",{exact:false})).toHaveCount(0);
    await visitor.reload();await visitor.getByRole("button",{name:"浏览需求",exact:true}).click();await visitor.getByRole("button",{name:"查看需求",exact:true}).click();await expect(visitor.getByRole("heading",{name:"合成绘画公开需求",exact:true})).toBeVisible();await expect(visitor.getByText("发布者：合成需求方展示名",{exact:true})).toBeVisible();await expect(visitor.getByText("本次私有发布声明",{exact:true})).toHaveCount(0);await expect(visitor.getByText("审核有效期限",{exact:false})).toHaveCount(0);
    const publicJson=await(await visitor.request.get("/api/v1/public/requests/"+second.id)).json();for(const name of ["publisherDeclaration","declarationVersion","expiresAt","submittedAt","reviewFeedback","email","phone","wechat","ownerId"])expect(publicJson).not.toHaveProperty(name);
    expect(await visitor.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await visitor.screenshot({path:path.join(root,`.tools/stage3-browser/public-${info.project.name}.png`),fullPage:true});
    await visitor.reload();await expect(visitor.getByRole("heading",{name:"合成绘画公开需求",exact:true})).toBeVisible();
    fixture("expire",second.id);await visitor.getByRole("button",{name:"刷新需求",exact:true}).click();await expect(visitor.getByRole("alert")).toContainText("需求不存在或当前不可查看");await expect(visitor.getByRole("heading",{name:"合成绘画公开需求",exact:true})).toHaveCount(0);
    await page.getByRole("button",{name:"刷新进度",exact:true}).click();await expect(page.getByText("已到期",{exact:true})).toBeVisible();
  }finally{await operations.close();await anonymous.close();}
});

test("提交响应丢失可同键重试，过期审核页面冲突不覆盖已决定结果",async({page,browser})=>{
  await prepared(page);let sent=0;
  await page.route("**/api/v1/requests/*/submit",async route=>{const response=await route.fetch();expect(response.status()).toBe(200);sent++;await route.abort("failed");},{times:1});
  await page.getByRole("radio",{name:"我为已满18岁的本人发布本条需求。",exact:true}).check();await page.getByRole("button",{name:"确认提交审核",exact:true}).click();await expect(page.getByRole("alert")).toContainText("暂时无法连接服务");
  await page.getByRole("button",{name:"确认提交审核",exact:true}).click();await expect(page.getByText("待审核",{exact:true})).toBeVisible();expect(sent).toBe(1);
  const contexts=[await browser.newContext(),await browser.newContext()];
  try {
    const first=await operatorPage(contexts[0]!,user("OPERATOR")),second=await operatorPage(contexts[1]!,user("OPERATOR"));
    await first.getByRole("radio",{name:"通过审核并公开",exact:true}).check();await first.getByRole("button",{name:"核对并确认审核"}).click();await first.getByRole("button",{name:"确认通过并公开",exact:true}).click();await expect(first.getByText("已通过",{exact:true})).toBeVisible();
    await second.getByRole("radio",{name:"驳回并说明原因",exact:true}).check();await second.getByLabel("驳回理由",{exact:true}).fill("旧页审核意见保留");await second.getByRole("button",{name:"核对并确认审核"}).click();await second.getByRole("button",{name:"确认驳回",exact:true}).click();await expect(second.getByRole("alert")).toContainText("内容已变化");
    await second.getByRole("button",{name:"返回核对",exact:true}).click();await expect(second.getByLabel("驳回理由",{exact:true})).toHaveValue("旧页审核意见保留");await second.getByRole("button",{name:"读取最新审核状态",exact:true}).click();await expect(second.getByText("已通过",{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"刷新进度",exact:true}).click();await expect(page.getByText("招募中",{exact:true})).toBeVisible();
  }finally{for(const context of contexts)await context.close();}
});

test("匿名与跨账号权限边界及显式提交的CSRF保护",async({page,browser})=>{
  const owner=await prepared(page);await submit(page);const own=await latestRequest(page);
  const otherContext=await browser.newContext(),anonymous=await browser.newContext();
  try {
    const other=await otherContext.newPage(),actor=user("REQUESTER");await login(other,actor);await expect(other.getByRole("button",{name:"需求审核",exact:true})).toHaveCount(0);
    expect((await other.request.get("/api/v1/requests/"+own.id)).status()).toBe(404);
    expect((await other.request.get("/api/v1/operations/submissions")).status()).toBe(403);
    const headers=await csrfHeaders(other,actor);expect((await other.request.post(`/api/v1/requests/${own.id}/submit`,{headers:{...headers,"Idempotency-Key":crypto.randomUUID()},data:{expectedVersion:own.version,publisherDeclaration:"SELF_ADULT",declarationVersion:"publisher-v1"}})).status()).toBe(404);
    expect((await page.request.post(`/api/v1/requests/${own.id}/submit`,{data:{expectedVersion:own.version,publisherDeclaration:"SELF_ADULT",declarationVersion:"publisher-v1"}})).status()).toBe(403);
    const visitor=await anonymous.newPage();await visitor.goto("/");expect((await visitor.request.get("/api/v1/operations/submissions")).status()).toBe(401);expect((await visitor.request.get("/api/v1/public/requests/"+own.id)).status()).toBe(404);
    const pending=await page.request.get("/api/v1/requests/"+own.id);expect((await pending.json()).status).toBe("PENDING_REVIEW");expect(owner.id).not.toBe(actor.id);
  }finally{await otherContext.close();await anonymous.close();}
});

test("提交缺项能定位返回填写，保存后重新主动确认声明",async({page})=>{
  await prepared(page);
  await page.getByRole("button",{name:"1. 基本信息",exact:true}).click();
  await page.getByLabel("目标与现状",{exact:false}).fill("");
  await page.getByRole("button",{name:"保存并下一步"}).click();await page.getByRole("button",{name:"保存并预览"}).click();
  await page.getByRole("radio",{name:"我为已满18岁的本人发布本条需求。",exact:true}).check();
  await page.getByRole("button",{name:"确认提交审核",exact:true}).click();
  await expect(page.getByRole("alert")).toContainText("目标与现状");
  await page.getByRole("button",{name:/目标与现状：.*返回填写/}).click();
  await expect(page.getByLabel("目标与现状",{exact:false})).toBeFocused();
  await page.getByLabel("目标与现状",{exact:false}).fill("已补齐的合成学习目标");
  await page.getByRole("button",{name:"保存并下一步"}).click();await page.getByRole("button",{name:"保存并预览"}).click();
  await expect(page.getByRole("button",{name:"确认提交审核",exact:true})).toBeDisabled();
  await submit(page);
});

test("另一页面提交后旧草稿留输入并可明确读取只读状态",async({page,context})=>{
  const owner=await prepared(page);const own=await latestRequest(page);
  await page.getByRole("button",{name:"1. 基本信息",exact:true}).click();
  await page.getByLabel("需求标题",{exact:false}).fill("尚未保存的旧页面输入");
  const second=await context.newPage();
  try{
    await second.goto("/");const headers=await csrfHeaders(second,owner);
    const submitted=await second.request.post(`/api/v1/requests/${own.id}/submit`,{headers:{...headers,"Idempotency-Key":crypto.randomUUID()},data:{expectedVersion:own.version,publisherDeclaration:"SELF_ADULT",declarationVersion:"publisher-v1"}});expect(submitted.status()).toBe(200);
    await page.getByRole("button",{name:"保存草稿",exact:true}).click();await expect(page.getByRole("alert")).toContainText("当前需求不可修改");
    await expect(page.getByLabel("需求标题",{exact:false})).toHaveValue("尚未保存的旧页面输入");
    await page.getByRole("button",{name:"读取最新草稿",exact:true}).click();
    await expect(page.getByRole("button",{name:"已核对，保留输入",exact:true})).toHaveCount(0);
    await page.getByRole("button",{name:"查看已提交状态，放弃当前输入",exact:true}).click();
    await expect(page.getByText("待审核",{exact:true})).toBeVisible();await expect(page.getByLabel("需求标题",{exact:false})).toHaveCount(0);
  }finally{await second.close();}
});

test("首次账号检查结束后再开放导航，匿名浏览不被跳回登录",async({page})=>{
  let release!:()=>void;const pending=new Promise<void>(resolve=>{release=resolve;});
  await page.route("**/api/v1/me",async route=>{await pending;await route.continue();},{times:1});
  try{
    await page.goto("/");await expect(page.getByRole("button",{name:"浏览需求",exact:true})).toHaveCount(0);
    release();await expect(page.getByRole("button",{name:"浏览需求",exact:true})).toBeEnabled();
    await page.getByRole("button",{name:"浏览需求",exact:true}).click();
    await expect(page.getByRole("heading",{name:"浏览需求",exact:true})).toBeVisible();
  }finally{release();}
});
