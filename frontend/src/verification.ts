import { ApiFailure } from "./account-api";

export const verificationTypes = [
  { type: "student_id", label: "本校学籍", hint: "校园卡、学生证或学信网学籍截图" },
  { type: "skill_cert", label: "技能证书", hint: "可选，可提交舞蹈、绘画、围棋或教师资格等证书" },
] as const;
export type VerificationType = typeof verificationTypes[number]["type"];
export const statusLabels = { pending: "审核中", approved: "已核验", rejected: "已驳回" } as const;
export interface Verification {
  id: string;
  user_id: string;
  verify_type: VerificationType;
  status: keyof typeof statusLabels;
  reject_reason: string | null;
  created_at: string;
  reviewed_at: string | null;
}
export function verificationError(error: unknown) {
  if (error instanceof ApiFailure) return Object.values(error.fieldErrors).join("；") || error.message;
  return error instanceof Error ? error.message : "操作未完成，请重试。";
}
