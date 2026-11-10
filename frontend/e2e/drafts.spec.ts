import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import path from "node:path";

const root=path.resolve(import.meta.dirname,"../..");
const driverRoot=path.join(root,".tools/maven-repository/org/postgresql/postgresql");
const version=readdirSync(driverRoot).filter(v=>/^\d/.test(v)).sort().at(-1)!;
const driver=path.join(driverRoot,version,`postgresql-${version}.jar`);
const actors:{id:string;account:string;password:string}[]=[];
function fixture(action:string,value:string){return JSON.parse(execFileSync("java",["--class-path",driver,path.join(root,"backend/src/test/tools/BrowserFixture.java"),action,value],{encoding:"utf8",env:process.env}));}
async function start(page:Page){
  const actor=fixture("create","REQUESTER");actors.push(actor);
  await page.goto("/");await page.getByLabel("账号",{exact:true}).fill(actor.account);await page.getByLabel("密码",{exact:true}).fill(actor.password);await page.getByRole("button",{name:"登录",exact:true}).click();
  await expect(page.getByRole("heading",{name:"本人基本资料",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"我的需求",exact:true}).click();
  await page.getByRole("button",{name:"新建需求草稿"}).click();
  await expect(page.getByRole("heading",{name:"填写需求草稿"})).toBeVisible();
  return actor;
}
test.beforeAll(()=>{if(!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/))throw new Error("Dedicated _test database required");});
test.afterEach(()=>{for(const actor of actors.splice(0))fixture("cleanup",actor.id);});

test("三步保存、预算大于999、重开不丢第一步",async({page},info)=>{
  await start(page);
  await page.getByLabel("需求标题",{exact:false}).fill("合成绘画需求");
  await page.getByLabel("服务类别",{exact:false}).selectOption("DRAWING");
  await page.getByLabel("服务对象阶段").selectOption("ADULT");
  await page.getByLabel("坪山区街道",{exact:false}).selectOption("PS_PINGSHAN");
  await page.getByLabel("目标与现状",{exact:false}).fill("合成测试：希望学习绘画基础。");
  await page.getByRole("button",{name:"保存并下一步"}).click();
  await expect(page.getByLabel("预算下限")).toBeVisible();
  await page.getByLabel("预算下限").fill("1000.01");await page.getByLabel("预算上限").fill("1600.99");
  await page.getByLabel("单次时长").selectOption("1.5");await page.getByRole("combobox",{name:"频率",exact:true}).selectOption("PER_WEEK");await page.getByLabel("每周次数").fill("2");
  await page.getByRole("button",{name:"添加时段"}).click();await page.getByLabel("结束",{exact:true}).fill("24:00");
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.screenshot({path:path.join(root,`.tools/stage1-browser/draft-${info.project.name}.png`),fullPage:true});
  await page.getByRole("button",{name:"保存并预览"}).click();
  await expect(page.getByRole("heading",{name:"合成绘画需求",exact:true})).toBeVisible();
  await expect(page.getByText("当前为已保存内容",{exact:true})).toBeVisible();
  await expect(page.getByText("当前是已保存的草稿，尚未提交审核，也未公开。")).toBeVisible();
  await page.getByRole("button",{name:"返回我的需求",exact:true}).first().click();await page.getByRole("button",{name:"继续填写"}).click();
  await expect(page.getByLabel("需求标题",{exact:false})).toHaveValue("合成绘画需求");
  await expect(page.getByLabel("目标与现状",{exact:false})).toHaveValue("合成测试：希望学习绘画基础。");
  await page.getByRole("button",{name:"2. 时间预算"}).click();
  await expect(page.getByLabel("预算下限")).toHaveValue("1000.01");await expect(page.getByLabel("结束",{exact:true})).toHaveValue("24:00");
});

test("两标签页冲突先核对最新内容，保留输入后再保存",async({page,context})=>{
  await start(page);
  await page.getByLabel("需求标题",{exact:false}).fill("初次保存的标题");
  await page.getByRole("button",{name:"保存草稿",exact:true}).click();
  await expect(page.getByText("当前为已保存内容",{exact:true})).toBeVisible();
  const second=await context.newPage();
  try {
    await second.goto("/");await second.getByRole("button",{name:"我的需求",exact:true}).click();
    await second.getByRole("button",{name:"继续填写"}).click();
    await second.getByLabel("需求标题",{exact:false}).fill("另一标签页保存的标题");
    await second.getByRole("button",{name:"保存草稿",exact:true}).click();
    await expect(second.getByText("当前为已保存内容",{exact:true})).toBeVisible();
    await page.getByLabel("需求标题",{exact:false}).fill("本页保留的输入");
    await page.getByRole("button",{name:"保存草稿",exact:true}).click();
    await expect(page.getByRole("alert")).toContainText("草稿已在其他页面修改");
    await expect(page.getByLabel("需求标题",{exact:false})).toHaveValue("本页保留的输入");
    await page.getByRole("button",{name:"读取最新草稿",exact:true}).click();
    await page.getByRole("button",{name:"已核对，保留输入",exact:true}).click();
    await page.getByRole("button",{name:"保存草稿",exact:true}).click();
    await expect(page.getByText("当前为已保存内容",{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"返回我的需求",exact:true}).click();
    await expect(page.getByRole("heading",{name:"本页保留的输入",exact:true})).toBeVisible();
  } finally { await second.close(); }
});

test("保存失败不进入下一步，保留输入并可重试",async({page})=>{
  await start(page);await page.getByLabel("需求标题",{exact:false}).fill("网络失败时保留的标题");
  await page.route("**/api/v1/requests/*/draft",route=>route.abort("failed"),{times:1});
  await page.getByRole("button",{name:"保存并下一步"}).click();
  await expect(page.getByRole("alert")).toContainText("暂时无法连接服务");
  await expect(page.getByLabel("需求标题",{exact:false})).toHaveValue("网络失败时保留的标题");
  await expect(page.getByLabel("预算下限")).toHaveCount(0);
  await page.getByRole("button",{name:"保存并下一步"}).click();await expect(page.getByLabel("预算下限")).toBeVisible();
  await page.getByLabel("预算下限").fill("1.001");await page.getByRole("button",{name:"保存并预览"}).click();
  await expect(page.getByRole("alert")).toContainText("两位小数");await expect(page.getByLabel("预算下限")).toHaveValue("1.001");
  await page.getByLabel("预算下限").fill("100");
  await page.getByRole("combobox",{name:"频率",exact:true}).selectOption("PER_WEEK");await page.getByLabel("每周次数").fill("abc");
  await page.getByRole("button",{name:"保存并预览"}).click();await expect(page.getByRole("alert")).toContainText("整数");await expect(page.getByLabel("每周次数")).toHaveValue("abc");
  await page.getByLabel("每周次数").fill("2");await page.getByRole("button",{name:"保存并预览"}).click();
  await expect(page.getByRole("heading",{name:"网络失败时保留的标题",exact:true})).toBeVisible();
});

test("未保存离开的继续、放弃、保存选项有效",async({page})=>{
  await start(page);await page.getByLabel("需求标题",{exact:false}).fill("尚未保存的标题");
  await page.getByRole("button",{name:"返回我的需求",exact:true}).click();await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByRole("button",{name:"继续编辑",exact:true}).click();await expect(page.getByLabel("需求标题",{exact:false})).toHaveValue("尚未保存的标题");
  await page.getByRole("button",{name:"返回我的需求",exact:true}).click();await page.getByRole("button",{name:"放弃修改并离开"}).click();
  await expect(page.getByRole("heading",{name:"未命名草稿"})).toBeVisible();await page.getByRole("button",{name:"继续填写"}).click();
  await expect(page.getByLabel("需求标题",{exact:false})).toHaveValue("");await page.getByLabel("需求标题",{exact:false}).fill("保存并离开的标题");
  await page.getByRole("button",{name:"本人资料",exact:true}).click();await page.getByRole("button",{name:"保存并离开"}).click();
  await expect(page.getByRole("heading",{name:"本人基本资料",exact:true})).toBeVisible();
  await page.getByRole("button",{name:"我的需求",exact:true}).click();await expect(page.getByRole("heading",{name:"保存并离开的标题"})).toBeVisible();
});
