export function codePointLength(value: string): number { return [...value].length; }
export function passwordError(password: string): string {
  const length = codePointLength(password);
  return length < 15 || length > 128 ? "密码须为15—128个字符，允许空格和粘贴，不自动删减。" : "";
}
export function moneyError(min: string, max: string): string {
  if (!min && !max) return "";
  const valid = (v: string) => /^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(v) && Number(v) > 0;
  if (!valid(min) || !valid(max)) return "报价须同时填写上下限，金额大于0且最多两位小数。";
  // Integer cents preserve exact comparison at the database's numeric(14,2) capacity.
  const cents = (v: string) => {
    const [whole, fraction = ""] = v.split(".");
    return BigInt(whole!) * 100n + BigInt(fraction.padEnd(2, "0"));
  };
  return cents(max) < cents(min) ? "报价上限不能低于下限。" : "";
}
