export const categoryNames: Record<string,string> = { DRAWING:"绘画",DANCE:"舞蹈",OTHER_SKILL:"其他课外技能",EXTRACURRICULAR_STUDY:"课外学习" };
export const areaNames: Record<string,string> = { PS_PINGSHAN:"坪山",PS_KENGZI:"坑梓",PS_LONGTIAN:"龙田",PS_SHIJING:"石井",PS_MALUAN:"马峦",PS_BILING:"碧岭" };
export const stageNames: Record<string,string> = { PRIMARY:"小学",JUNIOR:"初中",SENIOR:"高中",ADULT:"成年",OTHER:"其他" };
export const modeNames: Record<string,string> = { ONLINE:"线上",OFFLINE:"线下" };
export const statusNames: Record<string,string> = { DRAFT:"草稿",PENDING_REVIEW:"待审核",REJECTED:"已驳回",OPEN:"招募中",EXPIRED:"已到期",APPROVED:"已通过" };
export const declarationNames: Record<string,string> = { SELF_ADULT:"我为已满18岁的本人发布本条需求。",GUARDIAN_OF_MINOR:"我作为监护人为未成年人发布本条需求。" };
export const weekdayNames=["周一","周二","周三","周四","周五","周六","周日"];
export function minuteText(value:number) { return `${Math.floor(value/60)}`.padStart(2,"0")+":"+`${value%60}`.padStart(2,"0"); }
export interface SubmissionSnapshot { id:string; fields:Record<string,unknown>; publisherDisplayName:string; publisherDeclaration:string; declarationVersion:string; submittedAt:string; reviewFeedback:string|null }
export interface SavedRequest { id:string; status:string; version:number; fields:Record<string,unknown>; lastSavedAt:string; retentionReminder:boolean; latestSubmission:SubmissionSnapshot|null }
