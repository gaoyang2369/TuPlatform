import { expect, type APIRequestContext } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../..");
const actors: string[] = [];
function fixture(action: string, value: string) {
  const drivers = path.join(root, ".tools/maven-repository/org/postgresql/postgresql");
  const version = readdirSync(drivers).filter(v => /^\d/.test(v)).sort().at(-1)!;
  const driver = path.join(drivers, version, "postgresql-" + version + ".jar");
  return JSON.parse(execFileSync("java", ["-cp", driver, path.join(root, "backend/src/test/tools/BrowserFixture.java"),
    action, value], { encoding: "utf8" }));
}
export async function write(client: APIRequestContext, endpoint: string, data: unknown, id?: string, method = "POST") {
  const csrf = await (await client.get("/api/v1/auth/csrf")).json();
  const response = await client.fetch("/api/v1" + endpoint, { method, data, headers: {
    [csrf.headerName]: csrf.token, ...(id ? { "X-Account-Id": id, "Idempotency-Key": crypto.randomUUID() } : {}),
  } });
  expect(response.ok(), endpoint + " " + response.status()).toBe(true);
  return response.json();
}
export async function login(client: APIRequestContext, role: string) {
  const user = fixture("create", role);
  actors.push(user.id);
  await write(client, "/auth/login", {account: user.account, password: user.password});
  return user.id as string;
}
export function cleanActors() { for (const id of actors.splice(0)) fixture("cleanup", id); }
