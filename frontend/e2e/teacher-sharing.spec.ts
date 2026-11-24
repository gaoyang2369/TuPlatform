import { expect, test, type Page, type TestInfo } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { cleanActors, login, write } from "./browser-accounts";
import { sampleResume } from "./resume-fixture";

test.afterEach(cleanActors);

async function exportCard(page: Page, info: TestInfo, label:string) {
  await page.getByRole("button",{name:"生成分享名片",exact:true}).click();
  const dialog=page.getByRole("dialog",{name:"老师分享名片"});
  await expect(dialog.getByRole("img")).toBeVisible();
  const downloading=page.waitForEvent("download");
  await dialog.getByRole("link",{name:"保存名片图片（PNG）"}).click();
  const file=info.outputPath(label+".png");
  await (await downloading).saveAs(file);
  const bytes=await readFile(file);
  expect(bytes.subarray(1,4).toString()).toBe("PNG");
  await page.addScriptTag({path:createRequire(import.meta.url).resolve("jsqr")});
  const data=await page.evaluate(async base64=>{
    const img=new Image(); img.src="data:image/png;base64,"+base64; await img.decode();
    const canvas=document.createElement("canvas");
    canvas.width=img.width/2; canvas.height=img.height/2;
    const c=canvas.getContext("2d")!; c.drawImage(img,0,0,canvas.width,canvas.height);
    const pixels=c.getImageData(0,0,canvas.width,canvas.height);
    const decode=(window as unknown as {jsQR:typeof import("jsqr").default}).jsQR;
    const qr=decode(pixels.data,pixels.width,pixels.height);
    const avatar=c.getImageData(87,209,72,72).data;
    let blueInk=0, photoGreen=0;
    for(let i=0;i<avatar.length;i+=4) {
      if(avatar[i]===37 && avatar[i+1]===99 && avatar[i+2]===235) blueInk++;
      if(avatar[i]!<80 && avatar[i+1]!>130 && avatar[i+2]!<120) photoGreen++;
    }
    return {width:img.width,height:img.height,url:qr?.data,blueInk,photoGreen};
  },bytes.toString("base64"));
  expect(data.width).toBe(1500); expect(data.height).toBeGreaterThanOrEqual(2000);
  expect(data.url).toBeTruthy();
  await dialog.getByRole("button",{name:"关闭",exact:true}).click();
  return data;
}

test("自推与家长分享的高清 PNG 可扫码直达，停止公开后链接与头像失效",async({page,browser,request},info)=>{
  test.setTimeout(90000);
  const {baseURL,viewport,isMobile,hasTouch}=info.project.use;
  const parent=await browser.newContext({baseURL,viewport,isMobile,hasTouch});
  const guest=await browser.newContext({baseURL,viewport,isMobile,hasTouch});
  try {
    const id=await login(page.request,"TUTOR"); await login(parent.request,"REQUESTER");
    await page.goto("/?view=profile");
    await expect(page.getByRole("button",{name:"生成分享名片"})).toBeDisabled();
    const profile=await write(page.request,"/me/teacher-profile",{...sampleResume,expectedVersion:0,
      displayName:"林老师",categoryCodes:["DRAWING"],areaCodes:["PS_PINGSHAN"],serviceModes:["OFFLINE"],
      teachGrades:["初一","初二","初三"],teachSubjects:["数学","绘画"],teachingStyleTags:["耐心细致","习惯培养"],
      introduction:"用生活里的例子讲清知识点，陪孩子掌握自己的解题方法。",availabilityText:"周末下午",
      priceMin:"100",priceMax:"150"},id,"PUT");
    const published=await write(page.request,"/me/teacher-profile/publish",{expectedVersion:profile.version},id);
    await page.reload(); await page.locator("#field-displayName").fill("未保存的名字");
    const initial=await exportCard(page,info,"initial");
    expect(initial.blueInk).toBeGreaterThan(50);
    const url=baseURL+"/?view=teachers&teacher="+id;
    expect(initial.url).toBe(url);
    const visitor=await guest.newPage();await visitor.goto(initial.url!);
    const sheet=visitor.getByRole("dialog",{name:"老师资料"});
    await expect(sheet).toContainText("林老师");await expect(sheet).toContainText("周末下午");
    await expect(sheet.getByRole("button",{name:"登录后邀请老师"})).toBeVisible();

    // A known bitmap color distinguishes an uploaded image from the drawn initial in the exported file.
    const bitmap=await page.evaluate(()=>{
      const canvas=document.createElement("canvas");canvas.width=canvas.height=100;
      const c=canvas.getContext("2d")!;c.fillStyle="#22aa50";c.fillRect(0,0,100,100);
      return canvas.toDataURL().split(",")[1]!;
    });
    const csrf=await (await page.request.get("/api/v1/auth/csrf")).json();
    const upload=await page.request.post("/api/v1/upload/avatar",{headers:{[csrf.headerName]:csrf.token,"X-Account-Id":id},
      multipart:{file:{name:"test.png",mimeType:"image/png",buffer:Buffer.from(bitmap,"base64")}}});
    expect(upload.ok()).toBe(true);
    const parentPage=await parent.newPage();await parentPage.goto(url.replace("teacher=","teacherId="));
    const photo=await exportCard(parentPage,info,"photo");
    expect(photo.url).toBe(url);expect(photo.photoGreen).toBeGreaterThan(4000);
    await expect(parentPage).toHaveURL(url);
    await parentPage.getByRole("button",{name:"生成分享名片"}).click();
    await parent.grantPermissions(["clipboard-read","clipboard-write"]);
    await parentPage.getByRole("button",{name:"复制链接"}).click();
    expect(await parentPage.evaluate(()=>navigator.clipboard.readText())).toBe(photo.url);
    await parentPage.getByRole("dialog",{name:"老师分享名片"}).getByRole("button",{name:"关闭"}).click();
    await parentPage.route("**/public/teachers/*/avatar",r=>r.abort());
    const failedPhoto=await exportCard(parentPage,info,"failed-avatar");
    expect(failedPhoto.blueInk).toBeGreaterThan(50);expect(failedPhoto.url).toBe(url);
    await visitor.reload();await expect(sheet.getByRole("heading",{name:"林老师"})).toBeVisible();
    const avatar="/api/v1/public/teachers/"+id+"/avatar";
    expect((await request.get(avatar)).status()).toBe(200);
    await write(page.request,"/me/teacher-profile/unpublish",{expectedVersion:published.version},id);
    await visitor.reload();await expect(sheet.getByRole("alert")).toContainText("不可查看");
    expect((await request.get(avatar)).status()).toBe(404);
  } finally {await guest.close();await parent.close();}
});
