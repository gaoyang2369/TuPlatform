import { test, expect, type Page } from "@playwright/test";
import type { SavedRequest } from "../src/request-display.ts";

function draft(id:string,status="DRAFT"):SavedRequest { return {id,status,version:0,fields:{title:status==='DRAFT'?"可删除的草稿":"已提交的需求"},lastSavedAt:new Date().toISOString(),retentionReminder:false,latestSubmission:null}; }
async function setup(page:Page, initial:SavedRequest[]=[]) {
  const state={items:initial,creates:0,patches:0,deletes:0,keys:[] as string[],rejectCreate:false,loseCreate:false,failDelete:false};
  const receipts=new Map<string,string>();
  await page.route("**/api/v1/**",async route=>{
    const request=route.request(),endpoint=new URL(request.url()).pathname.replace("/api/v1","");
    if(endpoint==="/me")return route.fulfill({json:{id:"00000000-0000-4000-8000-000000000020",account:"draft_test",email:"draft@example.invalid",emailVerified:true,businessIdentity:"REQUESTER",operatorGranted:false,profileVersion:0}});
    if(endpoint==="/me/profile")return route.fulfill({json:{displayName:"测试需求方",version:0,teacher:null}});
    if(endpoint==="/profile-options")return route.fulfill({json:{categoryCodes:[],serviceModes:[],areaCodes:[]}});
    if(endpoint==="/publishing-options")return route.fulfill({json:{categories:["DRAWING"],streets:[],audienceStages:["ADULT"],serviceModes:["ONLINE","OFFLINE"]}});
    if(endpoint==="/auth/csrf")return route.fulfill({json:{token:"mock-csrf",headerName:"X-XSRF-TOKEN"}});
    if(endpoint==="/me/requests")return route.fulfill({json:{items:state.items,nextCursor:null}});
    if(endpoint==="/requests"&&request.method()==="POST"){
      state.creates++;const key=request.headers()["idempotency-key"]!;state.keys.push(key);
      if(state.rejectCreate){state.rejectCreate=false;return route.fulfill({status:422,json:{code:"INVALID_INPUT",message:"请修改标题",fieldErrors:{title:"标题需要修改"}}});}
      let saved=state.items.find(item=>item.id===receipts.get(key));
      if(!saved){saved=draft("00000000-0000-4000-8000-000000000021");saved.fields=request.postDataJSON().fields;state.items.push(saved);receipts.set(key,saved.id);}
      if(state.loseCreate){state.loseCreate=false;return route.abort("failed");}
      return route.fulfill({status:201,json:saved});
    }
    const id=endpoint.split('/')[2],saved=state.items.find(item=>item.id===id);
    if(endpoint.endsWith("/draft")&&request.method()==="PATCH"&&saved){state.patches++;const body=request.postDataJSON();expect(body.expectedVersion).toBe(saved.version);saved.fields=body.fields;saved.version++;return route.fulfill({json:saved});}
    if(endpoint.startsWith("/requests/")&&request.method()==="DELETE"){
      state.deletes++;
      if(state.failDelete){state.failDelete=false;return route.fulfill({status:503,json:{code:"UNAVAILABLE",message:"暂时无法删除，请重试"}});}
      expect(saved?.status).toBe("DRAFT");expect(request.postDataJSON().expectedVersion).toBe(saved?.version);
      state.items=state.items.filter(item=>item.id!==id);return route.fulfill({status:204,body:""});
    }
    if(endpoint.startsWith("/requests/")&&saved)return route.fulfill({json:saved});
    return route.fulfill({status:404,json:{code:"NOT_FOUND",message:"不存在"}});
  });
  await page.goto("/");await page.getByRole("button",{name:"我的需求",exact:true}).click();
  return state;
}

test("误点新建或放弃输入不创建，主动保存才创建且校验失败可修正",async({page})=>{
  const state=await setup(page);
  await page.getByRole("button",{name:"新建需求草稿"}).click();
  await expect(page.getByText("尚未保存，点击保存后才会生成草稿",{exact:true})).toBeVisible();
  await page.getByRole("button",{name:"返回我的需求",exact:true}).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);expect(state.creates).toBe(0);
  await page.getByRole("button",{name:"新建需求草稿"}).click();await page.locator("#draft-title").fill("放弃的输入");
  await page.getByRole("button",{name:"返回我的需求",exact:true}).click();await page.getByRole("button",{name:"放弃修改并离开"}).click();
  await expect(page.getByRole("heading",{name:"放弃的输入",exact:true})).toHaveCount(0);expect(state.creates).toBe(0);
  await page.getByRole("button",{name:"新建需求草稿"}).click();await page.locator("#draft-title").fill("待修正标题");state.rejectCreate=true;
  await page.getByRole("button",{name:"保存草稿",exact:true}).click();await expect(page.getByRole("alert")).toContainText("请修改标题");expect(state.items).toHaveLength(0);
  await page.locator("#draft-title").fill("首次手动保存");await page.getByRole("button",{name:"保存草稿",exact:true}).click();
  await expect(page.getByText("当前为已保存内容",{exact:true})).toBeVisible();expect(state.items).toHaveLength(1);expect(state.items[0].fields.title).toBe("首次手动保存");expect(state.keys[0]).not.toBe(state.keys[1]);
  await page.getByRole("button",{name:"保存草稿",exact:true}).click();await expect.poll(()=>state.patches).toBe(1);expect(state.items).toHaveLength(1);
});

test("首次保存响应丢失后重试使用同一份草稿并保留后续修改",async({page})=>{
  const state=await setup(page);state.loseCreate=true;
  await page.getByRole("button",{name:"新建需求草稿"}).click();await page.locator("#draft-title").fill("首次保存内容");
  await page.getByRole("button",{name:"保存草稿",exact:true}).click();await expect(page.getByRole("alert")).toContainText("暂时无法连接");
  await page.locator("#draft-title").fill("重试时的最新修改");await page.getByRole("button",{name:"保存草稿",exact:true}).click();await expect(page.getByText("当前为已保存内容",{exact:true})).toBeVisible();
  expect(state.creates).toBe(2);expect(state.keys[0]).toBe(state.keys[1]);expect(state.patches).toBe(1);expect(state.items).toHaveLength(1);expect(state.items[0].fields.title).toBe("重试时的最新修改");
});

test("未提交草稿删除需确认，取消不删除，失败可重试且已提交需求无删除入口",async({page})=>{
  const state=await setup(page,[draft("00000000-0000-4000-8000-000000000022"),draft("00000000-0000-4000-8000-000000000023","PENDING_REVIEW")]);
  await expect(page.getByRole("button",{name:"删除草稿",exact:true})).toHaveCount(1);
  await page.getByRole("button",{name:"删除草稿",exact:true}).click();await page.getByRole("dialog").getByRole("button",{name:"取消",exact:true}).click();expect(state.deletes).toBe(0);
  state.failDelete=true;await page.getByRole("button",{name:"删除草稿",exact:true}).click();await page.getByRole("button",{name:"确认删除草稿",exact:true}).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("暂时无法删除");expect(state.items).toHaveLength(2);
  await page.getByRole("button",{name:"确认删除草稿",exact:true}).click();await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("heading",{name:"可删除的草稿",exact:true})).toHaveCount(0);await expect(page.getByRole("heading",{name:"已提交的需求",exact:true})).toBeVisible();expect(state.items).toHaveLength(1);
});
