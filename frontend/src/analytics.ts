import { api } from "./account-api";

export function track(eventName: "page_view_teachers" | "open_teacher_detail") {
  try {
    let sessionId = sessionStorage.getItem("linke.analytics");
    if (!sessionId) {
      sessionId = crypto.randomUUID();
      sessionStorage.setItem("linke.analytics", sessionId);
    }
    void api.request("/events", "POST", { eventName, sessionId }).catch(() => {});
  } catch { /* Browser storage may be unavailable. Continue the user journey. */ }
}
