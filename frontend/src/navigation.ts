// Only non-secret navigation context belongs in the address/history.
export const screens = ["home", "login", "register", "registered", "forgot", "verify", "reset", "identity", "profile", "requests", "public", "review", "teachers", "matches", "contacts"] as const;
export type Screen = typeof screens[number];
export function readScreen(search = location.search): Screen {
  const query = new URLSearchParams(search);
  const value = query.get("view");
  return screens.includes(value as Screen) ? value as Screen : query.has("request") ? "public" : "home";
}
let currentUrl = location.pathname + location.search;
let position = Number(history.state?.linkePosition) || 0;
history.replaceState({ ...history.state, linkePosition: position }, "");
history.scrollRestoration = "manual";
export function writeUrl(query: URLSearchParams, replace = false) {
  const url = location.pathname + (query.size ? "?" + query.toString() : "");
  if (url === currentUrl) return;
  history.replaceState({ ...history.state, scrollY: window.scrollY }, "");
  if (!replace) position++;
  history[replace ? "replaceState" : "pushState"]({ linkePosition: position, scrollY: 0 }, "", url);
  currentUrl = url;
}
export function routeTo(view: Screen, values: Record<string, string | undefined> = {}, replace = false) {
  const query = new URLSearchParams(view === "home" ? "" : "view=" + view);
  for (const [key, value] of Object.entries(values)) if (value) query.set(key, value);
  writeUrl(query, replace);
}
export function updateQuery(values: Record<string, string | undefined>, replace = false) {
  const query = new URLSearchParams(location.search);
  for (const [key, value] of Object.entries(values)) value ? query.set(key, value) : query.delete(key);
  writeUrl(query, replace);
}
export function restoreScroll() {
  requestAnimationFrame(() => window.scrollTo({ top: Number(history.state?.scrollY) || 0, behavior: "instant" }));
}
export function readPages(query = new URLSearchParams(location.search)): (string | null)[] {
  try {
    const value: unknown = JSON.parse(query.get("pages") || "[null]");
    if (Array.isArray(value) && value.length > 0 && value.length <= 100 && value[0] === null && value.every(v => v === null || typeof v === "string" && v.length <= 2048)) return value;
  } catch { /* Invalid navigation context starts at page one. */ }
  return [null];
}
// Undo a native history move before asking the existing form leave guard. A
// cancelled move therefore keeps both the form and the original history entry.
export function listenHistory(guard: (proceed: () => void) => void, apply: () => void) {
  let restoring: number | null = null;
  let approved = false;
  function accept() {
    position = Number(history.state?.linkePosition) || 0;
    currentUrl = location.pathname + location.search;
    apply();
  }
  function pop() {
    if (restoring !== null) {
      const delta = restoring; restoring = null;
      restoreScroll();
      guard(() => { approved = true; history.go(delta); });
      return;
    }
    if (approved) { approved = false; accept(); return; }
    const delta = (Number(history.state?.linkePosition) || 0) - position;
    if (!delta) { accept(); return; }
    restoring = delta; history.go(-delta);
  }
  window.addEventListener("popstate", pop);
  return () => window.removeEventListener("popstate", pop);
}
