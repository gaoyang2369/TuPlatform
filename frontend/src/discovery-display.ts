import { weekdayNames, minuteText } from "./request-display.ts";

export function moneyText(value: unknown): string {
  return String(value ?? "").replace(/(\.\d*?[1-9])0+$/, "$1").replace(/\.0+$/, "");
}
export function timeSummary(fields: Record<string, unknown>): string {
  const pref = fields.availabilityPreference as { mode?: string; weekdays?: number[]; periods?: string[] } | undefined;
  const slots = fields.availableSlots as { weekday: number; startMinute: number; endMinute: number }[] | undefined;
  const periods: Record<string, string> = { MORNING: "上午", AFTERNOON: "下午", EVENING: "晚上" };
  let time = "时间可协商";
  if (pref && pref.mode !== "NEGOTIABLE" && pref.weekdays?.length) {
    const days = [...pref.weekdays].sort().join(",");
    const label = days === "6,7" ? "周末" : days === "1,2,3,4,5" ? "工作日" : pref.weekdays.map(d => weekdayNames[d - 1]).join("、");
    time = label + (pref.periods?.length ? " " + pref.periods.map(p => periods[p] || p).join("/") : " 时段不限");
  } else if (slots?.length) {
    const first = slots[0]!;
    time = `${weekdayNames[first.weekday - 1]} ${minuteText(first.startMinute)}—${minuteText(first.endMinute)}` + (slots.length > 1 ? ` 等${slots.length}个时段` : "");
  }
  const frequency = fields.frequency as { mode?: string; count?: number } | undefined;
  return [time, frequency?.mode === "PER_WEEK" ? `每周${frequency.count}次` : "频率可协商", fields.durationHours ? `每次${moneyText(fields.durationHours)}小时` : ""].filter(Boolean).join(" · ");
}
