// Identity correction / lifecycle design. Imported by the single contract generator.
export function extendAccountContracts(c) {
 const {schemas:s,accountSchemas:a,accountPaths:p,paths,ref,obj,text,uuid,instant,version,terminal,kinds,read,write,authWrite,csrf,key,started,pathId,cursor,limit,filter,body,response,problem,headers}=c;
 const add=(name,value)=>{s[name]=value;a[name]=value;};
 const identity={type:'string',enum:['DEMANDER','TUTOR']};
 const nonnegative={type:'integer',minimum:0,maximum:9007199254740991};
 const caseStates={type:'string',enum:['SUBMITTED','NEEDS_ACTION','READY_FOR_CONFIRMATION','REJECTED','WITHDRAWN','COMPLETED']};
 add('CorrectionBlocker',obj({code:text(80),count:nonnegative,message:text(500)},['code','message']));
 add('CorrectionImpact',obj({token:text(4096),expiresAt:{...instant,description:'5分钟影响摘要技术有效期'},draftCount:nonnegative,roleProfileCount:nonnegative}));
 add('CorrectionEligibility',obj({currentIdentity:identity,identityVersion:version,identityEpoch:version,activityVersion:version,targetIdentity:identity,route:{type:'string',enum:['SELF_SERVICE','MANUAL_REVIEW','UNAVAILABLE']},hasBusinessHistory:{type:'boolean'},selfServiceChangesUsed:{type:'integer',minimum:0,maximum:1},blockers:{type:'array',items:ref('CorrectionBlocker')},activeCaseId:uuid,impact:ref('CorrectionImpact')},['currentIdentity','identityVersion','identityEpoch','activityVersion','targetIdentity','route','hasBusinessHistory','selfServiceChangesUsed','blockers']));
 add('IdentityCorrection',obj({id:uuid,version,sourceIdentity:identity,targetIdentity:identity,sourceIdentityVersion:version,sourceIdentityEpoch:version,route:{type:'string',enum:['SELF_SERVICE','MANUAL_REVIEW']},status:caseStates,createdAt:instant,updatedAt:instant,ownerFeedback:text(2000),archiveId:uuid,completedIdentityVersion:version},['id','version','sourceIdentity','targetIdentity','sourceIdentityVersion','sourceIdentityEpoch','route','status','createdAt','updatedAt']));
 add('CorrectionRequest',obj({targetIdentity:identity,sourceIdentityVersion:version,note:text(1000)},['targetIdentity','sourceIdentityVersion']));
 const acknowledgement={type:'string',const:'identity-correction-v1'};
 add('SelfCorrectionApply',obj({targetIdentity:identity,sourceIdentityVersion:version,impactToken:text(4096),acknowledgementVersion:acknowledgement}));
 add('CorrectionCaseAction',obj({expectedCaseVersion:version}));
 add('CorrectionConfirm',obj({expectedCaseVersion:version,sourceIdentityVersion:version,impactToken:text(4096),acknowledgementVersion:acknowledgement}));
 const reviewInput={expectedCaseVersion:version,sourceIdentityVersion:version,expectedActivityVersion:version};
 add('CorrectionReviewInput',{oneOf:[obj({...reviewInput,decision:{const:'APPROVE'}}),obj({...reviewInput,decision:{type:'string',enum:['REQUEST_ACTION','REJECT']},reason:text(2000)})]});
 add('CorrectionReviewResult',obj({correction:ref('IdentityCorrection'),reviewId:uuid,decision:{type:'string',enum:['APPROVE','REQUEST_ACTION','REJECT']},reviewedActivityVersion:version}));
 add('CorrectionReviewDetail',obj({correction:ref('IdentityCorrection'),subjectAccountId:uuid,activityVersion:version,blockers:{type:'array',items:ref('CorrectionBlocker')},lastReview:ref('CorrectionReviewResult')},['correction','subjectAccountId','activityVersion','blockers']));
 add('CorrectionApplied',obj({correctionId:uuid,status:{const:'COMPLETED'},businessIdentity:identity,identityVersion:version,identityEpoch:version,archiveId:uuid,reloginRequired:{type:'boolean',const:true}}));
 add('IdentityArchive',obj({id:uuid,businessIdentity:identity,identityEpoch:version,archivedAt:instant,correctionId:uuid,resourceCounts:obj({requests:nonnegative,submissions:nonnegative,roleProfiles:nonnegative}),supportedReaders:{type:'array',items:{type:'string',enum:['REQUEST','REQUEST_SUBMISSION','ROLE_PROFILE']}}}));
 add('PrivateEmail',{...a.Email});
 add('EmailChange',obj({id:uuid,version,status:{type:'string',enum:['AWAITING_PROOFS','READY','COMPLETED','CANCELLED','EXPIRED']},sourceEmailVersion:version,oldEmailMasked:text(254),newEmail:ref('PrivateEmail'),oldEmailConfirmed:{type:'boolean'},newEmailVerified:{type:'boolean'},createdAt:instant,expiresAt:{...instant,description:'换绑证明24小时技术期限，非需求截止日'},updatedAt:instant}));
 add('EmailChangeCreate',obj({newEmail:ref('PrivateEmail'),expectedEmailVersion:version}));
 add('EmailChangeAction',obj({expectedCaseVersion:version}));
 add('EmailChangeComplete',obj({expectedCaseVersion:version,expectedEmailVersion:version}));
 add('EmailChanged',obj({changeId:uuid,status:{const:'COMPLETED'},emailVersion:version,reloginRequired:{type:'boolean',const:true}}));
 for(const [name,item]of [['CorrectionPage','IdentityCorrection'],['CorrectionReviewPage','CorrectionReviewDetail'],['ArchivePage','IdentityArchive'],['EmailChangePage','EmailChange']]) add(name,obj({items:{type:'array',maxItems:50,items:ref(item)},nextCursor:text(4096)},['items']));
 // The shared operation union must cover each new write result in both machine documents.
 for(const [kind,result]of [['IDENTITY_CORRECTION_REQUESTED','IdentityCorrection'],['IDENTITY_CORRECTION_WITHDRAWN','IdentityCorrection'],['IDENTITY_CORRECTION_RESUBMITTED','IdentityCorrection'],['IDENTITY_CORRECTION_REVIEWED','CorrectionReviewResult'],['IDENTITY_CORRECTION_APPLIED','CorrectionApplied'],['EMAIL_CHANGE_CREATED','EmailChange'],['EMAIL_CHANGE_CANCELLED','EmailChange'],['EMAIL_CHANGE_COMPLETED','EmailChanged']]){
  kinds.push(kind);s.OperationSucceeded.oneOf.push(obj({...terminal,kind:{const:kind},status:{const:'SUCCEEDED'},result:ref(result)}));
 }
 a.AccountInfo.properties={...a.AccountInfo.properties,identityEpoch:nonnegative,emailVersion:version,passwordConfirmedRecently:{type:'boolean'}};
 a.AccountInfo.required.push('identityEpoch','emailVersion','passwordConfirmedRecently');
 p['/me/profile'].get.summary='已选任一身份的本人通用展示名';
 p['/me/profile'].put.summary='替换本人通用展示名；身份专属资料另有契约';
 a.PasswordReauthentication=obj({password:{type:'string',minLength:1,maxLength:1024,writeOnly:true}});
 a.EmailChangeToken=obj({token:{type:'string',minLength:43,maxLength:43,pattern:'^[A-Za-z0-9_-]{43}$',writeOnly:true}});
 a.EmailLinkConfirmed=obj({confirmed:{type:'boolean',const:true}});
 p['/auth/reauthentication']={post:authWrite('confirmCurrentPassword','本人近期密码确认，不授运营能力','PasswordReauthentication','AuthContext',200,[csrf])};
 p['/auth/email-change-confirmations']={post:authWrite('confirmEmailChangeLink','单次旧/新邮箱证明；不自动改邮箱或登录','EmailChangeToken','EmailLinkConfirmed')};
 p['/me/identity-correction-eligibility']={get:read('getIdentityCorrectionEligibility','本人读取纠错路径和5分钟归档影响摘要','CorrectionEligibility',{parameters:[{...filter('targetIdentity',identity),required:true}]})};
 p['/me/identity-corrections']={get:read('listMyIdentityCorrections','本人纠错历史，不依赖仍持有旧角色','CorrectionPage',{parameters:[cursor,limit]}),post:write('createIdentityCorrection','登记人工申请，不冻结当前业务','CorrectionRequest','IdentityCorrection',{parameters:[csrf,key,started],responses:{201:response('已登记人工申请，未改身份','IdentityCorrection',{headers:headers.write}),202:response('处理中','OperationProcessing',{headers:headers.processing}),400:problem('MALFORMED_REQUEST'),401:problem('UNAUTHENTICATED'),403:problem('CSRF_INVALID'),409:problem('CORRECTION_CASE_CONFLICT / IDENTITY_VERSION_CONFLICT'),422:problem('FIELD_INVALID'),429:problem('RATE_LIMITED',{headers:{...headers.private,'Retry-After':{schema:{type:'integer',minimum:1},description:'允许重试的秒数'}}}),503:problem('结果须按原键恢复')}})};
 p['/me/identity-corrections/self-apply']={post:write('applySelfIdentityCorrection','本人无历史且自助次数0，原子归档/改身份/撤销会话','SelfCorrectionApply','CorrectionApplied',{parameters:[csrf,key,started]})};
 p['/me/identity-corrections/{id}']={get:read('getMyIdentityCorrection','本人纠错固定记录及当前状态','IdentityCorrection',{parameters:[pathId]})};
 for(const [suffix,operationId,input,result,summary]of [['withdraw','withdrawIdentityCorrection','CorrectionCaseAction','IdentityCorrection','本人撤回未完成申请，不改身份'],['resubmit','resubmitIdentityCorrection','CorrectionCaseAction','IdentityCorrection','本人处理NEEDS_ACTION后重新送核'],['confirm','confirmIdentityCorrection','CorrectionConfirm','CorrectionApplied','本人近期密码确认、重校业务/摘要后执行，撤销全部原会话']])p['/me/identity-corrections/{id}/'+suffix]={post:write(operationId,summary,input,result)};
 p['/ops/identity-corrections']={get:read('listIdentityCorrectionReviews','独立纠错岗位核对队列/历史','CorrectionReviewPage',{parameters:[cursor,limit,filter('status',caseStates)]})};
 p['/ops/identity-corrections/{id}']={get:read('getIdentityCorrectionReview','独立纠错岗位读取固定申请及实时阻断','CorrectionReviewDetail',{parameters:[pathId]})};
 p['/ops/identity-corrections/{id}/reviews']={post:write('reviewIdentityCorrection','独立纠错写岗位/强认证；不得核对本人申请；通过不改身份','CorrectionReviewInput','CorrectionReviewResult')};
 p['/me/identity-archives']={get:read('listMyIdentityArchives','本人跨身份只读归档元信息','ArchivePage',{parameters:[cursor,limit]})};
 p['/me/identity-archives/{id}']={get:read('getMyIdentityArchive','本人归档资源计数和可用读模型，不能恢复旧写权限','IdentityArchive',{parameters:[pathId]})};
 p['/me/identity-archives/{id}/requests']={get:read('listArchivedOwnRequests','本人旧epoch需求，只读，沿OwnerRequest投影','OwnerPage',{parameters:[pathId,cursor,limit]})};
 const requestId={name:'requestId',in:'path',required:true,schema:uuid};
 p['/me/identity-archives/{id}/requests/{requestId}']={get:read('getArchivedOwnRequest','校验归档/需求/本人三者归属，只读','OwnerRequest',{parameters:[pathId,requestId]})};
 p['/me/identity-archives/{id}/requests/{requestId}/submissions']={get:read('listArchivedOwnSubmissions','本人归档提交历史，无需求截止日/运营身份','OwnerSubmissionPage',{parameters:[pathId,requestId,cursor,limit]})};
 p['/me/email-changes']={get:read('listMyEmailChanges','本人邮箱换绑记录，私有','EmailChangePage',{parameters:[cursor,limit]}),post:write('createEmailChange','本人近期密码确认，发旧/新邮箱证明，原绑定保持','EmailChangeCreate','EmailChange',{parameters:[csrf,key,started]})};
 p['/me/email-changes/{id}']={get:read('getMyEmailChange','本人换绑证明状态；不等于已完成换绑','EmailChange',{parameters:[pathId]})};
 p['/me/email-changes/{id}/cancel']={post:write('cancelEmailChange','本人取消未完成换绑，保留当前邮箱','EmailChangeAction','EmailChange')};
 p['/me/email-changes/{id}/complete']={post:write('completeEmailChange','本人近期密码确认及双邮箱证明，唯一约束兜底，撤销全部会话','EmailChangeComplete','EmailChanged')};
 for(const path of ['/me/identity-corrections/self-apply','/me/identity-corrections/{id}/confirm','/me/email-changes','/me/email-changes/{id}/complete'])if(p[path].post){p[path].post.responses['403']=problem('CSRF_INVALID / REAUTH_REQUIRED');p[path].post.responses['409']=problem('IDENTITY_VERSION_CONFLICT / CORRECTION_CASE_CONFLICT / CORRECTION_REVIEW_STALE / ARCHIVE_SUMMARY_CHANGED / BUSINESS_ACTIVITY_PENDING / SELF_SERVICE_NOT_ALLOWED / EMAIL_UNAVAILABLE / EMAIL_CHANGE_CONFLICT');}
 p['/ops/identity-corrections/{id}/reviews'].post.responses['403']=problem('OPS_PERMISSION_REQUIRED / OPS_STEP_UP_REQUIRED / SELF_CORRECTION_REVIEW_NOT_ALLOWED / CSRF_INVALID');
 p['/auth/reauthentication'].post.responses['200'].headers={...headers.private,'Set-Cookie':{schema:text(1024),description:'轮换上下文，不存可重放Cookie结果。'}};
 paths['/me/requests/{id}'].put.description='当前DEMANDER且对象创建identityEpoch等于本人当前epoch；旧epoch只能经归档只读，改回同类身份也不复活。全量替换、省略清空。';
 paths['/me/requests'].get.description='仅本人当前DEMANDER epoch，旧epoch只经本人归档接口读取，不能混入可编辑当前列表。';
 paths['/me/requests/{id}'].get.description='当前DEMANDER且对象epoch为当前epoch；本人旧epoch对象走归档读模型，不恢复旧业务权限。';
 paths['/me/requests/{id}/submissions'].get.description='当前epoch需求的提交历史；旧epoch历史改走归档提交接口，仍不得带具体截止日或运营身份。';
 paths['/me/requests/{id}/submit'].post.description='同一事务锁账号守卫并校验当前identityEpoch，成功提交置hasBusinessHistory=true并增加activityVersion，与纠错执行序列化。';
}
