import { test, expect } from "@playwright/test";
import path from "node:path";

for (const legacy of [false, true]) {
  test(legacy ? "既有街道和精确时间草稿仍可编辑" : "简化需求表单保存、重开、预览和提交", async ({ page }, info) => {
    const id="00000000-0000-4000-8000-000000000010";
    let saved={ id, status:"DRAFT", version:0, fields: (legacy?{ title:"既有绘画需求", categoryCode:"DRAWING", audienceStageCode:"ADULT", areaCode:"PS_SHIJING", durationHours:"8", availableSlots:[{weekday:6,startMinute:540,endMinute:600}] }:{}) as Record<string,unknown>, lastSavedAt:new Date().toISOString(), retentionReminder:false, latestSubmission:null };
    let submitted=false;
    await page.route("**/api/v1/**", async route=>{
      const request=route.request(), endpoint=new URL(request.url()).pathname.replace("/api/v1","");
      if(endpoint==="/me")return route.fulfill({json:{id:"00000000-0000-4000-8000-000000000011",account:"form_test",email:"form@example.invalid",emailVerified:true,businessIdentity:"REQUESTER",operatorGranted:false,profileVersion:0}});
      if(endpoint==="/me/profile")return route.fulfill({json:{displayName:"测试需求方",version:0,teacher:null}});
      if(endpoint==="/profile-options")return route.fulfill({json:{categoryCodes:[],serviceModes:[],areaCodes:[]}});
      if(endpoint==="/publishing-options")return route.fulfill({json:{categories:["DRAWING","EXTRACURRICULAR_STUDY"],streets:["PS_SHIJING"],audienceStages:["PRIMARY","JUNIOR","SENIOR","ADULT"],serviceModes:["ONLINE","OFFLINE"],publisherDeclarations:[{code:"GUARDIAN_OF_MINOR",version:"publisher-v1",label:"我作为监护人为未成年人发布本条需求。"},{code:"SELF_ADULT",version:"publisher-v1",label:"我为已满18岁的本人发布本条需求。"}]}});
      if(endpoint==="/auth/csrf")return route.fulfill({json:{token:"mock-csrf",headerName:"X-XSRF-TOKEN"}});
      if(endpoint==="/me/requests")return route.fulfill({json:{items:[saved],nextCursor:null}});
      if(endpoint===`/requests/${id}/draft`){
        const body=request.postDataJSON();expect(body.expectedVersion).toBe(saved.version);
        const fields={...saved.fields,...body.fields};for(const key of Object.keys(fields))if(fields[key]===null)delete fields[key];
        saved={...saved,version:saved.version+1,fields};return route.fulfill({json:saved});
      }
      if(endpoint===`/requests/${id}/submit`){submitted=true;saved={...saved,status:"PENDING_REVIEW",version:saved.version+1};return route.fulfill({json:{id,status:saved.status,version:saved.version}});}
      if(endpoint===`/requests/${id}`)return route.fulfill({json:saved});
      return route.fulfill({status:404,json:{message:"Unexpected test endpoint"}});
    });
    await page.goto("/");await page.getByRole("button",{name:"我的需求",exact:true}).click();await page.getByRole("button",{name:"继续填写",exact:true}).click();
    const location=page.getByLabel("小区或附近地标",{exact:false});
    if(legacy){
      await expect(location).toHaveValue("石井街道附近");
      await page.getByRole("button",{name:"2. 时间预算",exact:true}).click();
      await expect(page.getByRole("radio",{name:"填写精确时间",exact:true})).toBeChecked();
      await expect(page.getByLabel("开始",{exact:true})).toHaveValue("09:00");await expect(page.getByLabel("结束",{exact:true})).toHaveValue("10:00");
      await expect(page.getByLabel("单次时长（小时）",{exact:true})).toHaveValue("8");
      await page.getByRole("button",{name:"减少半小时",exact:true}).click();await page.getByRole("button",{name:"保存并预览",exact:true}).click();
      await expect(page.locator(".request-summary")).toContainText("7.5 小时");
      expect(saved.fields.areaCode).toBe("PS_SHIJING");expect(saved.fields.durationHours).toBe("7.5");expect(saved.fields.availableSlots).toEqual([{weekday:6,startMinute:540,endMinute:600}]);
      await expect(page.locator(".request-summary")).toContainText("09:00—10:00");
      await expect(page.locator(".request-summary dt").filter({hasText:/^服务对象$/})).toHaveCount(1);
      await expect(page.locator(".request-summary dt").filter({hasText:/^服务方式$/})).toHaveCount(0);
      return;
    }
    await page.getByLabel("需求标题",{exact:false}).fill("周末数学辅导");
    await page.getByLabel("服务类别",{exact:false}).selectOption("EXTRACURRICULAR_STUDY");await page.getByLabel("服务对象阶段").selectOption("SENIOR");
    await page.locator("#draft-grade").fill("高二");await page.locator("#draft-subject").fill("数学");await location.fill("坪山区 · 测试花园");
    await page.getByLabel("目标与现状",{exact:false}).fill("巩固基础，逐步提升。");await page.getByLabel("服务方式",{exact:false}).selectOption("OFFLINE");
    await page.screenshot({path:path.resolve(import.meta.dirname,`../../.tools/request-form-feedback/basic-${info.project.name}.png`),fullPage:true});
    await page.getByRole("button",{name:"保存并下一步",exact:true}).click();
    await expect(page.getByLabel("单次时长（小时）",{exact:true})).toHaveValue("2");
    await page.getByRole("button",{name:"增加半小时",exact:true}).click();await expect(page.getByLabel("单次时长（小时）",{exact:true})).toHaveValue("2.5");
    await page.getByRole("button",{name:"2 小时",exact:true}).click();
    await expect(page.getByRole("radio",{name:"时间可协商",exact:true})).toBeChecked();await expect(page.getByLabel("开始",{exact:true})).toHaveCount(0);
    await page.getByRole("radio",{name:"选择偏好时段",exact:true}).check();await page.getByRole("button",{name:"周末",exact:true}).click();await page.getByRole("button",{name:"下午",exact:true}).click();
    await page.getByLabel("预算下限").fill("90");await page.getByLabel("预算上限").fill("120");await page.getByRole("combobox",{name:"频率",exact:true}).selectOption("NEGOTIABLE");
    await page.screenshot({path:path.resolve(import.meta.dirname,`../../.tools/request-form-feedback/time-${info.project.name}.png`),fullPage:true});
    await page.getByRole("button",{name:"保存并预览",exact:true}).click();
    await expect(page.getByRole("heading",{name:"周末数学辅导",exact:true})).toBeVisible();
    expect(saved.fields.availabilityPreference).toEqual({mode:"PREFERRED",weekdays:[6,7],periods:["AFTERNOON"]});expect(saved.fields.availableSlots).toEqual([]);expect(saved.fields.grade).toBe("高二");expect(saved.fields.subject).toBe("数学");expect(saved.fields.locationText).toBe("坪山区 · 测试花园");
    const summary=page.locator(".request-summary");await expect(summary).toContainText("周末 · 下午（具体时间可协商）");await expect(summary).toContainText("高二");await expect(summary).toContainText("数学");await expect(summary).toContainText("测试花园");
    await expect(summary.locator("dt").filter({hasText:/^服务对象$/})).toHaveCount(0);
    await expect(summary.locator("dt").filter({hasText:/^年级$/})).toHaveCount(1);
    await expect(summary.locator("dt").filter({hasText:/^科目$/})).toHaveCount(1);
    for(const label of ["时间备注","期望开始","对老师的期待"]) await expect(summary.locator("dt").filter({hasText:new RegExp(`^${label}$`)})).toHaveCount(0);
    await expect(page.getByText("当前是已保存的草稿，尚未提交审核，也未公开。")).toHaveCount(0);await expect(page.locator(".draft-page > .message.success")).toHaveCount(0);await expect(page.getByRole("button",{name:"保存草稿",exact:true})).toHaveCount(0);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
    const submit=page.getByRole("button",{name:"确认提交审核",exact:true});await expect(submit).toBeDisabled();
    await page.screenshot({path:path.resolve(import.meta.dirname,`../../.tools/request-form-feedback/preview-${info.project.name}.png`),fullPage:true});
    await page.getByRole("button",{name:"上一步",exact:true}).click();
    await page.locator("#draft-availabilityNote").fill("周日下午优先");await page.locator("#draft-expectedStartDate").fill("2099-01-01");
    await page.getByRole("button",{name:"保存并预览",exact:true}).click();
    await expect(summary).toContainText("周日下午优先");await expect(summary).toContainText("2099-01-01");
    await page.getByRole("button",{name:"返回我的需求",exact:true}).first().click();await page.getByRole("button",{name:"继续填写",exact:true}).click();
    await expect(page.locator("#draft-grade")).toHaveValue("高二");await expect(location).toHaveValue("坪山区 · 测试花园");await page.getByRole("button",{name:"2. 时间预算",exact:true}).click();
    await expect(page.getByRole("radio",{name:"选择偏好时段",exact:true})).toBeChecked();await expect(page.getByRole("button",{name:"下午",exact:true})).toHaveAttribute("aria-pressed","true");
    await page.getByRole("button",{name:"保存并预览",exact:true}).click();await page.getByRole("radio",{name:"我作为监护人为未成年人发布本条需求。",exact:true}).check();await submit.click();
    await expect(page.getByText("待审核",{exact:true})).toBeVisible();expect(submitted).toBe(true);
  });
}
