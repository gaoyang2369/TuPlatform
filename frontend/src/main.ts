import { createApp } from "vue";
import "./styles/theme.css";

const page = ["/terms", "/privacy"].includes(location.pathname)
  ? import("./PolicyPage.vue")
  : import("./AccountApp.vue");
void page.then(({ default: App }) => createApp(App).mount("#app"));
