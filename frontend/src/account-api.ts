export type Identity = "REQUESTER" | "TUTOR";
export interface Me {
  id: string; account: string; email: string; emailVerified: boolean;
  businessIdentity: Identity | null; operatorGranted: boolean; profileVersion: number;
}
export interface Teacher {
  categoryCodes: string[]; serviceModes: string[]; areaCodes: string[];
  introduction: string; priceMin: string | null; priceMax: string | null;
  availabilityText: string; public: boolean; version: number;
}
export interface Profile { displayName: string; version: number; teacher: Teacher | null }
export interface ProfileOptions { categoryCodes: string[]; serviceModes: string[]; areaCodes: string[] }

export class ApiFailure extends Error {
  status: number;
  code: string;
  fieldErrors: Record<string, string>;
  constructor(status: number, code: string, message: string, fieldErrors: Record<string, string> = {}) {
    super(message); this.status = status; this.code = code; this.fieldErrors = fieldErrors;
  }
}

// Keep cookies same-origin. Development proxies /api; deployment serves the API at the same origin.
// Passwords, mail link tokens and session data are never placed in localStorage.
export class AccountApi {
  private fetcher: typeof fetch;
  private accountId: string | null = null;
  private csrf: Promise<{ token: string; headerName: string }> | null = null;
  constructor(fetcher: typeof fetch = fetch) { this.fetcher = fetcher; }
  private async csrfToken() {
    if (!this.csrf) {
      this.csrf = this.request<{ token: string; headerName: string }>("/auth/csrf");
      this.csrf.catch(() => { this.csrf = null; });
    }
    return this.csrf;
  }
  async request<T>(path: string, method = "GET", body?: unknown, key?: string, retryCsrf = true): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (this.accountId && (path.startsWith("/me/") || path.startsWith("/teachers/"))) headers["X-Account-Id"] = this.accountId;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (key) headers["Idempotency-Key"] = key;
    if (method !== "GET") {
      const csrf = await this.csrfToken(); headers[csrf.headerName] = csrf.token;
    }
    let response: Response;
    try {
      response = await this.fetcher("/api/v1" + path, {
        method, headers, credentials: "same-origin", cache: "no-store",
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch {
      throw new ApiFailure(0, "NETWORK_ERROR", "暂时无法连接服务，请检查连接后重试。当前输入仍保留；操作结果不确定时请先读取最新状态。");
    }
    if (response.status === 204) return undefined as T;
    let payload: unknown;
    try { payload = await response.json(); }
    catch { throw new ApiFailure(response.status, "INVALID_RESPONSE", "服务未返回有效数据，请稍后重试。当前输入仍保留。"); }
    if (!response.ok) {
      const error = payload as { code?: string; message?: string; fieldErrors?: Record<string, string> };
      if (error.code === "CSRF_INVALID" && retryCsrf && method !== "GET") {
        this.csrf = null; return this.request<T>(path, method, body, key, false);
      }
      throw new ApiFailure(response.status, error.code ?? "REQUEST_FAILED", error.message ?? "操作未完成，请稍后重试", error.fieldErrors);
    }
    if ((path === "/me" || path === "/auth/login") && payload && typeof payload === "object" && "id" in payload) this.accountId = String(payload.id);
    if (path === "/auth/reset-password") this.accountId = null;
    return payload as T;
  }
}

export const api = new AccountApi();
