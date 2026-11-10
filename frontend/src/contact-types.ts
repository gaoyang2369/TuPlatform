export type ContactField = 'PHONE' | 'WECHAT';
export interface Contacts {version:number;phone:string|null;wechat:string|null;fieldVersions:{phone:number;wechat:number}}
export interface SharedContacts {phone?:string;wechat?:string}
export interface Grant {
  roundId:string;version:number;myConsent:boolean;otherConsent:boolean;mySelectedFields:ContactField[];
  requiresReconfirmation:boolean;otherRequiresReconfirmation:boolean;canViewContacts:boolean;
  mySharedContacts?:SharedContacts;otherSharedContacts?:SharedContacts;
}
