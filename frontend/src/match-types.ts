import type { Teacher } from "./account-api.ts";
export type PublicRequest=Record<string,unknown>&{id:string;submissionId:string;title:string;publisherDisplayName:string};
export type PublicTeacher=Omit<Teacher,"public"|"version">&{id:string;profileVersion:number;avatar_url?:string|null;displayName:string};
export interface Match {
  id:string;requestId:string|null;version:number;roundId:string;state:"WAITING"|"MUTUAL"|"REJECTED"|"WITHDRAWN"|"in_cooperation"|"completed"|"closed_no_deal";
  contactsExchanged:boolean;cooperationRequestedBy:string|null;completionRequestedBy:string|null;
  completedAt:string|null;reviewDeadlineAt:string|null;reviewEligible:boolean;
  avatar_url:string|null;
  initiatorRole:string;myRole:string;waitingForMe:boolean;requestExpired:boolean;teacherPublic:boolean;
  canAccept:boolean;canReject:boolean;canWithdraw:boolean;canInitiateAgain:boolean;
  submissionSnapshot:Record<string,unknown>&{title:string;publisherDisplayName:string;introduction?:string};teacherSnapshot:PublicTeacher;submissionChanged:boolean;teacherProfileChanged:boolean;
}
export function matchLabel(match:Match) {
  if (match.state === "in_cooperation") return "合作中";
  if (match.state === "completed") return "已完结";
  if (match.state === "closed_no_deal") return "暂未合作";
  return match.state==="WAITING"?(match.waitingForMe?"等待我回应":"等待对方回应"):
    match.state==="MUTUAL"?"已互选":match.state==="REJECTED"?"已拒绝":"已撤回";
}

export const progressActions = {
  "request-cooperation": { label: "已约试课 / 开始合作", description: "请在约好试课或开始合作后发起。对方会收到确认提醒。" },
  "confirm-cooperation": { label: "确认对方发起的合作", description: "确认后，双方的撮合进度将更新为合作中。" },
  "close-no-deal": { label: "沟通后暂未合作", description: "本轮联系将结束，联系方式停止展示，双方不会进入互评。" },
  "request-completion": { label: "标记阶段完结", description: "请在试课或本阶段合作结束后发起。对方会收到确认提醒。" },
  "confirm-completion": { label: "确认阶段完结", description: "确认后，双方将获得 14 天的互评资格。" },
  "continue-cooperation": { label: "仍在合作", description: "本次完结请求将取消。等本阶段结束后，双方都可以重新发起。" },
} as const;
export type ProgressAction = keyof typeof progressActions;
