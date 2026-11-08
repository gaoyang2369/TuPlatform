import test from "node:test";
import assert from "node:assert/strict";
import { codePointLength, passwordError, moneyError } from "../src/account-form.ts";

test("密码按Unicode码点计长，保留空格和长密码", () => {
  assert.equal(codePointLength("🙂".repeat(15)), 15);
  assert.equal(passwordError("🙂".repeat(15)), "");
  assert.equal(passwordError(" ".repeat(15)), "");
  assert.equal(passwordError("密".repeat(128)), "");
  assert.ok(passwordError("密".repeat(129)));
  assert.ok(passwordError("short"));
});
test("报价双端及精确金额，不沿用999元上限或自动舍入", () => {
  assert.equal(moneyError("", ""), "");
  assert.equal(moneyError("1000", "999999999999.99"), "");
  assert.equal(moneyError("999999999999.98", "999999999999.99"), "");
  for (const [min, max] of [["1", ""], ["", "1"], ["0", "1"], ["1.001", "2"], ["1e2", "200"], ["2", "1"], ["1", "1000000000000"]]) assert.ok(moneyError(min, max));
});
