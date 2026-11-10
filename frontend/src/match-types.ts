import type { Teacher } from "./account-api.ts";
export type PublicRequest=Record<string,unknown>&{id:string;submissionId:string;title:string;publisherDisplayName:string};
export type PublicTeacher=Omit<Teacher,"public"|"version">&{id:string;profileVersion:number;displayName:string};
export interface Match {
  id:string;requestId:string;version:number;roundId:string;state:"WAITING"|"MUTUAL"|"REJECTED"|"WITHDRAWN";
  initiatorRole:string;myRole:string;waitingForMe:boolean;requestExpired:boolean;teacherPublic:boolean;
  canAccept:boolean;canReject:boolean;canWithdraw:boolean;canInitiateAgain:boolean;
  submissionSnapshot:PublicRequest;teacherSnapshot:PublicTeacher;submissionChanged:boolean;teacherProfileChanged:boolean;
}
export function matchLabel(match:Match) {
  return match.state==="WAITING"?(match.waitingForMe?"等待我回应":"等待对方回应"):
    match.state==="MUTUAL"?"已互选":match.state==="REJECTED"?"已拒绝":"已撤回";
}
