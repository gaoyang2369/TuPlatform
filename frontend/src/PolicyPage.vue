<script setup lang="ts">
import PolicyLinks from "./PolicyLinks.vue";
const privacy = location.pathname === "/privacy";
const title = privacy ? "隐私保护政策" : "用户服务协议";
document.title = title + " · 邻课";
const sections = privacy ? [
  ["学籍与证书", "学生证件仅用于平台人工核验，绝不对外公开。只有提交者与授权审核人员可读取材料。公开资料仅显示核验结果，请在上传前遮盖证号、住址等无关信息。"],
  ["住址公开范围", "公开需求只显示街道片区。请将门牌、楼栋等详细地址填在专用私密地址栏，避免在标题和需求描述中填写个人信息。"],
  ["联系方式与撤回", "手机号、微信仅在双方互选且本轮双方授权有效后向对方显示。你可以停止分享，变更已分享字段后需重新确认。撤回后对方可能仍保有先前保存的信息。"],
  ["试点使用统计", "我们记录浏览老师、打开资料、发送意向、互选和查看联系方式的时间，用账号或匿名会话去重，供运营查看汇总。埋点不记录证件、地址、电话、微信或留言。"]
] : [
  ["平台提供的服务", "邻课是高校试点本地家教信息撮合平台。需求方与老师可浏览资料、发出邀请或投递意向，并自行决定是否联系。"],
  ["课程与费用", "平台不组织班课，不代收大额课时费，也不承诺提分或考级结果。双方自行核实信息，协商授课时间、地点、试课、费用和取消安排。"],
  ["授课安全", "请在首次见面前核实对方身份，选择安全的会面地点。涉及未成年人的需求应由监护人发布并参与安排。遇到不合适的安排可拒绝意向或停止分享联系方式。"]
];
</script>
<template>
  <main class="mx-auto max-w-3xl space-y-6 px-4 py-10">
    <a href="/" class="text-sm text-primary">返回邻课首页</a>
    <article class="space-y-6 rounded-2xl border border-border bg-white p-6 sm:p-10">
      <h1 class="text-3xl font-bold">{{ title }}</h1>
      <section v-for="[heading,body] in sections" :key="heading" class="space-y-2">
        <h2 class="text-xl font-bold">{{ heading }}</h2><p class="text-[15px] leading-7 text-secondary-foreground">{{ body }}</p>
      </section>
    </article>
    <footer class="text-sm"><PolicyLinks/></footer>
  </main>
</template>
