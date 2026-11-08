// Design artifacts only: no server, credentials, migration or dependencies.
import {mkdir,writeFile} from 'node:fs/promises';
import {extendAccountContracts} from './account-contract-extensions.mjs';
import {extendRetentionContracts} from './retention-contract-extensions.mjs';
const ref=n=>({$ref:'#/components/schemas/'+n});
const obj=(properties,required=Object.keys(properties))=>({type:'object',additionalProperties:false,properties,...(required.length?{required}:{})});
const text=(maxLength,minLength=1)=>({type:'string',minLength,maxLength});
const uuid={type:'string',format:'uuid'}, instant={type:'string',format:'date-time'};
const version={type:'integer',minimum:1,maximum:9007199254740991};
const status={type:'string',enum:['DRAFT','PENDING_REVIEW','REJECTED','OPEN','EXPIRED']};
const code={...text(64),pattern:'^[A-Z0-9][A-Z0-9_-]*$'};
const start={type:'string',pattern:'^([01][0-9]|2[0-3]):[0-5][0-9]$'};
const end={type:'string',pattern:'^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$'};
const schemas={
 Money:{type:'string',pattern:'^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?$',description:'元/小时字符串，业务>0、max>=min；numeric(14,2)技术边界999999999999.99，无999元业务上限；响应两位小数。'},
 DurationHours:{type:'string',enum:['0.5','1','1.5','2','2.5','3','3.5','4','4.5','5','5.5','6','6.5','7','7.5','8'],description:'小时0.5—8、步长0.5，请求采用此规范表示。'},
 Slot:obj({weekday:{type:'integer',minimum:1,maximum:7},start,end}),
 Frequency:{oneOf:[obj({kind:{const:'NEGOTIABLE'}}),obj({kind:{const:'WEEKLY'},times:{type:'integer',minimum:1,maximum:14}})]},
};
const fields={title:text(60),categoryCode:code,audienceStageCode:{type:'string',enum:['PRIMARY','JUNIOR','SENIOR','ADULT','OTHER']},areaCode:code,publisherDeclaration:obj({kind:{type:'string',enum:['SELF_ADULT','GUARDIAN_OF_MINOR']},statementVersion:{type:'string',const:'publisher-v1'}}),serviceMode:{type:'string',enum:['ONLINE','OFFLINE']},goal:text(2000),budgetMin:ref('Money'),budgetMax:ref('Money'),durationHours:ref('DurationHours'),frequency:ref('Frequency'),availableSlots:{type:'array',maxItems:21,items:ref('Slot'),description:'Asia/Shanghai；end>start；同日重复/重叠拒绝、相邻允许、跨午夜拆分；不生成预约。'},availabilityNote:text(300),expectedStartDate:{type:'string',format:'date'},tutorExpectations:text(500)};
const mandatory=['title','categoryCode','audienceStageCode','areaCode','publisherDeclaration','goal','budgetMin','budgetMax','durationHours','frequency','availableSlots'];
schemas.DraftContent=obj(fields,[]);
schemas.SubmissionContent=obj({...fields,availableSlots:{...fields.availableSlots,minItems:1}},mandatory);
const publicFields=Object.fromEntries(Object.entries(fields).filter(([name])=>name!=='publisherDeclaration'));
schemas.PublicContent=obj({...publicFields,availableSlots:{...fields.availableSlots,minItems:1}},mandatory.filter(name=>name!=='publisherDeclaration'));
schemas.Feedback=obj({submissionId:uuid,submittedVersion:version,resolution:{type:'string',enum:['APPROVED','REJECTED','EXPIRED_BEFORE_REVIEW']},resolvedAt:instant,reason:text(2000)},['submissionId','submittedVersion','resolution','resolvedAt']);
schemas.OwnerRequest=obj({id:uuid,version,status,revisionNumber:version,content:ref('DraftContent'),lastSubmissionId:uuid,submittedAt:instant,updatedAt:instant,feedback:{type:'array',maxItems:1,items:ref('Feedback'),description:'最近一次终结反馈；完整历史用submissions分页。'}},['id','version','status','revisionNumber','content','updatedAt','feedback']);
schemas.OwnerSubmission=obj({id:uuid,requestId:uuid,submittedVersion:version,revisionNumber:version,submittedAt:instant,content:ref('SubmissionContent'),resolution:ref('Feedback')},['id','requestId','submittedVersion','revisionNumber','submittedAt','content']);
schemas.Revision=obj({sourceVersion:version});schemas.Submit=obj({expectedVersion:version});
schemas.ReviewDecision={oneOf:[obj({expectedVersion:version,decision:{const:'APPROVE'}}),obj({expectedVersion:version,decision:{const:'REJECT'},reason:text(2000)})],description:'驳回理由非空白；2000码点技术容量；仅本人/授权运营可见。'};
schemas.PublicRequest=obj({id:uuid,version,status:{const:'OPEN'},content:ref('PublicContent'),publishedAt:instant,updatedAt:instant});
const reviewBase={submissionId:uuid,requestId:uuid,submittedVersion:version,requestVersionAfter:version,resolvedAt:instant};
schemas.ReviewOutcome={oneOf:[obj({...reviewBase,statusAfter:{const:'OPEN'},resolution:{const:'APPROVED'},reviewDecisionId:uuid}),obj({...reviewBase,statusAfter:{const:'REJECTED'},resolution:{const:'REJECTED'},reviewDecisionId:uuid,reason:text(2000)}),obj({...reviewBase,statusAfter:{const:'EXPIRED'},resolution:{const:'EXPIRED_BEFORE_REVIEW'}})],description:'原动作当时结果；不代表当前仍公开。系统到期不伪造人工决定。'};
const reviewState={type:'string',enum:['PENDING_REVIEW','APPROVED','REJECTED','EXPIRED_BEFORE_REVIEW']};
schemas.ReviewRecord=obj({submissionId:uuid,requestId:uuid,submittedVersion:version,requestVersion:version,reviewState,requestStatus:status,isCurrentSubmission:{type:'boolean'},submittedAt:instant,expiresAt:instant,content:ref('SubmissionContent'),outcome:ref('ReviewOutcome')},['submissionId','requestId','submittedVersion','requestVersion','reviewState','requestStatus','isCurrentSubmission','submittedAt','expiresAt','content']);
schemas.PublishingOption=obj({code,label:text(80),allowSubmit:{type:'boolean'}});
schemas.PublishingOptions=obj({configRevision:version,categories:{type:'array',items:ref('PublishingOption')},areas:{type:'array',items:ref('PublishingOption')},audienceStages:{type:'array',items:ref('PublishingOption')},declarationVersion:{type:'string',const:'publisher-v1'},declarations:{type:'array',items:obj({kind:{type:'string',enum:['SELF_ADULT','GUARDIAN_OF_MINOR']},text:text(500)})}});
schemas.Problem=obj({code:text(80),message:text(2000),traceId:text(128),fields:{type:'array',items:obj({path:text(256),code:text(80),message:text(500)})}},['code','message','traceId']);
schemas.IdentityResult=obj({businessIdentity:{type:'string',enum:['DEMANDER','TUTOR']},identityVersion:version});
schemas.ProfileResult=obj({version,displayName:text(30),missingRequirements:{type:'array',items:{type:'string',enum:['DISPLAY_NAME']}}},['version','missingRequirements']);
const kinds=['REQUEST_CREATED','REQUEST_SAVED','REQUEST_REVISED','REQUEST_SUBMITTED','REVIEW_DECIDED','IDENTITY_BOUND','PROFILE_REPLACED'];
const operationBase={id:uuid,kind:{type:'string',enum:kinds},acceptedAt:instant};
const terminal={...operationBase,completedAt:instant,expiresAt:{...instant,description:'技术回执恢复期限，不是隐藏的需求截止日期。'},originalHttpStatus:{type:'integer',minimum:200,maximum:599}};
schemas.OperationProcessing=obj({...operationBase,status:{const:'PROCESSING'}});
schemas.OperationSucceeded={oneOf:[...['REQUEST_CREATED','REQUEST_SAVED','REQUEST_REVISED','REQUEST_SUBMITTED'].map(kind=>obj({...terminal,kind:{const:kind},status:{const:'SUCCEEDED'},result:ref('OwnerRequest')})),obj({...terminal,kind:{const:'REVIEW_DECIDED'},status:{const:'SUCCEEDED'},result:ref('ReviewOutcome')}),obj({...terminal,kind:{const:'IDENTITY_BOUND'},status:{const:'SUCCEEDED'},result:ref('IdentityResult')}),obj({...terminal,kind:{const:'PROFILE_REPLACED'},status:{const:'SUCCEEDED'},result:ref('ProfileResult')})]};
schemas.OperationFailed=obj({...terminal,status:{const:'FAILED'},error:ref('Problem'),reviewOutcome:ref('ReviewOutcome')},[...Object.keys(terminal),'status','error']);
schemas.Operation={oneOf:[ref('OperationProcessing'),ref('OperationSucceeded'),ref('OperationFailed')],description:'原账号且当前有对象权限；运营kind不返回普通账号。FAILED查询200不等于原动作成功。'};
for(const [name,item]of [['OwnerPage','OwnerRequest'],['OwnerSubmissionPage','OwnerSubmission'],['PublicPage','PublicRequest'],['ReviewPage','ReviewRecord']])schemas[name]=obj({items:{type:'array',maxItems:50,items:ref(item)},nextCursor:text(4096)},['items']);
const header=(schema,description)=>({schema,description});
const noStore=header({type:'string',const:'no-store'},'禁用HTTP缓存，不代替实时鉴权。');
const headers={private:{'Cache-Control':noStore},etag:{'Cache-Control':noStore,ETag:header(text(160),'当前聚合强ETag；历史回执不代表最新版本。')},write:{'Cache-Control':noStore,'Operation-Id':header(uuid,'等于客户端Idempotency-Key。'),'Operation-Expires-At':header(instant,'完成起72小时恢复窗口。')},processing:{'Cache-Control':noStore,Location:header(text(256),'查询同一操作。'),'Retry-After':header({type:'integer',minimum:1},'秒数，默认2。')}};
const response=(description,name,extra={})=>({description,headers:headers.private,...(name?{content:{'application/json':{schema:ref(name)}}}:{}),...extra});
const problem=(description,extra={})=>response(description,'Problem',extra);
const errors={400:problem('MALFORMED_REQUEST / CURSOR_INVALID / CURSOR_EXPIRED'),401:problem('UNAUTHENTICATED'),403:problem('CSRF_INVALID / ROLE_NOT_ALLOWED / OPS_PERMISSION_REQUIRED / OPS_STEP_UP_REQUIRED'),404:problem('NOT_FOUND；无权/不存在统一'),409:problem('VERSION_CONFLICT / STATE_CONFLICT / IDEMPOTENCY_MISMATCH / OPERATION_WINDOW_EXPIRED'),410:problem('REQUEST_EXPIRED / TOKEN_UNUSABLE；只在已获权上下文'),413:problem('PAYLOAD_TOO_LARGE；JSON技术上限64KiB'),422:problem('FIELD_INVALID / CATEGORY_NOT_ALLOWED / PROFILE_INCOMPLETE'),429:problem('RATE_LIMITED',{headers:{...headers.private,'Retry-After':header({type:'integer',minimum:1},'可重试秒数')}}),503:problem('TEMPORARILY_UNAVAILABLE；单凭响应不能确认未提交')};
const pathId={name:'id',in:'path',required:true,schema:uuid};
const csrf={name:'X-CSRF-Token',in:'header',required:true,schema:text(256),description:'同步CSRF令牌，写请求另校验Origin。'};
const key={name:'Idempotency-Key',in:'header',required:true,schema:uuid,description:'账号+UUID唯一；同键跨路径/不同指纹409。id即Operation.id。'};
const started={name:'Operation-Started-At',in:'header',required:true,schema:instant,description:'首次发送时间，重试不更新；首次接收允许过去72h/未来5min；不决定业务日期。'};
const ifMatch={name:'If-Match',in:'header',required:true,schema:text(160),description:'单一强ETag "request:{id}:v{version}"；禁止*或弱值；缺428、旧412。'};
const cursor={name:'cursor',in:'query',schema:text(4096),description:'签名游标绑定主体/条件/顺序，15分钟有效；非快照。'};
const limit={name:'limit',in:'query',schema:{type:'integer',minimum:1,maximum:50,default:20}};
const filter=(name,schema)=>({name,in:'query',schema});
const body=schema=>({required:true,content:{'application/json':{schema:ref(schema)}}});
const readErrors=Object.fromEntries(Object.entries(errors).filter(([http])=>['400','401','403','404','429','503'].includes(http)));
const read=(operationId,summary,result,extra={})=>({operationId,summary,responses:{200:response('成功',result),...readErrors},...extra});
const write=(operationId,summary,schema,result='OwnerRequest',extra={})=>({operationId,summary,parameters:[pathId,csrf,key,started],requestBody:body(schema),responses:{200:response('原动作结果；随后读取当前对象',result,{headers:headers.write}),202:response('处理中；不是成功','OperationProcessing',{headers:headers.processing}),...errors},...extra});
const ownerDetailHeaders={...headers.write,ETag:headers.etag.ETag};
const paths={
 '/me/requests':{get:read('listOwnRequests','需求方本人当前列表','OwnerPage',{parameters:[cursor,limit,filter('status',status)]}),post:write('createRequestDraft','创建本人不完整草稿','DraftContent','OwnerRequest',{parameters:[csrf,key,started],responses:{201:response('草稿创建成功','OwnerRequest',{headers:ownerDetailHeaders}),202:response('处理中','OperationProcessing',{headers:headers.processing}),...errors}})},
 '/me/requests/{id}':{get:read('getOwnRequest','本人当前需求，无截止日期','OwnerRequest',{parameters:[pathId],responses:{200:response('本人当前聚合','OwnerRequest',{headers:headers.etag}),...errors}}),put:write('replaceRequestDraft','全量替换DRAFT，省略即清空','DraftContent','OwnerRequest',{parameters:[pathId,csrf,key,started,ifMatch],responses:{200:response('保存成功','OwnerRequest',{headers:ownerDetailHeaders}),202:response('处理中','OperationProcessing',{headers:headers.processing}),412:problem('VERSION_CONFLICT'),428:problem('VERSION_REQUIRED'),...errors}})},
 '/me/requests/{id}/revisions':{post:write('startRequestRevision','REJECTED/EXPIRED主动修订','Revision')},
 '/me/requests/{id}/submit':{post:write('submitRequestForReview','冻结完整草稿，提交待审','Submit')},
 '/me/requests/{id}/submissions':{get:read('listOwnRequestSubmissions','本人历史快照/反馈，无截止日期/运营身份','OwnerSubmissionPage',{parameters:[pathId,cursor,limit]})},
 '/operations/{id}':{get:read('getOwnOperation','原账号及当前对象权限，分类型历史结果','Operation',{parameters:[pathId],responses:{200:response('操作状态；FAILED不等于原动作成功','Operation',{headers:{...headers.private,'Retry-After':headers.processing['Retry-After']}}),...errors}})},
 '/request-publishing-options':{get:read('getRequestPublishingOptions','需求方本人读取当前选项、准入版本和声明文案','PublishingOptions')},
 '/ops/request-reviews':{get:read('listRequestReviews','独立运营待审队列/固定提交的终结历史','ReviewPage',{parameters:[cursor,limit,filter('reviewState',{...reviewState,default:'PENDING_REVIEW'}),filter('categoryCode',code),filter('areaCode',code)]})},
 '/ops/request-reviews/{id}':{get:read('getRequestReview','id为submissionId，处理后仍可查','ReviewRecord',{parameters:[pathId]})},
 '/ops/request-reviews/{id}/decisions':{post:write('decideRequestReview','独立岗位/强认证审核固定submissionId','ReviewDecision','ReviewOutcome',{description:'expectedVersion匹配主对象requestVersion及当前submissionId；到期原子结束EXPIRED，410及FAILED回执，禁止APPROVE；历史按原submissionId恢复。'})},
 '/requests':{get:read('listPublicRequests','匿名有效公开需求列表','PublicPage',{security:[],parameters:[cursor,limit,filter('categoryCode',code),filter('areaCode',code)]})},
 '/requests/{id}':{get:read('getPublicRequest','匿名已批准、准入允许、实时未到期OPEN','PublicRequest',{security:[],parameters:[pathId]})},
};
const accountSchemas={...schemas};
accountSchemas.AccountName={type:'string',pattern:'^[A-Za-z][A-Za-z0-9_]{3,31}$',minLength:4,maxLength:32};
accountSchemas.Password={type:'string',minLength:15,maxLength:128,writeOnly:true,description:'不trim/截断/规范化；允许空格/粘贴、不强制组合。'};
accountSchemas.Email={type:'string',format:'email',maxLength:254,description:'一个邮箱一个账号；唯一键大小写不区分，域名IDNA规范化，点号/+保留；国际邮箱解析兼容性待验证。'};
accountSchemas.Register=obj({accountName:ref('AccountName'),email:ref('Email'),password:ref('Password'),adultDeclaration:{type:'boolean',const:true},adultDeclarationVersion:{type:'string',const:'adult-18-v1'}});
accountSchemas.EmailRequest=obj({email:ref('Email')});
accountSchemas.VerificationRequest=obj({email:ref('Email'),receiptId:uuid},['email']);
const token={type:'string',minLength:43,maxLength:43,pattern:'^[A-Za-z0-9_-]{43}$',writeOnly:true};
accountSchemas.TokenAction=obj({token,password:{type:'string',minLength:1,maxLength:1024,writeOnly:true,description:'缺原注册上下文证明时必须核对注册密码，防止激活他人预设密码的账号。'}},['token']);accountSchemas.ResetPassword=obj({token,newPassword:ref('Password')});
accountSchemas.Login=obj({accountName:ref('AccountName'),password:{type:'string',minLength:1,maxLength:1024,writeOnly:true,description:'错误输入统一认证失败，不用注册规则差异探测账号。'}});
accountSchemas.AuthReceipt=obj({receiptId:uuid,status:{type:'string',enum:['ACCEPTED','COMPLETED','FAILED']},message:text(500)},['receiptId','status']);
accountSchemas.AccountInfo=obj({id:uuid,accountName:ref('AccountName'),emailVerified:{type:'boolean'},businessIdentity:{type:['string','null'],enum:['DEMANDER','TUTOR',null]},identityVersion:{type:'integer',minimum:0,maximum:9007199254740991},profileVersion:{type:'integer',minimum:0},missingRequirements:{type:'array',items:{type:'string'}},opsCapabilities:{type:'array',items:{type:'string'}},opsStepUpRequired:{type:'boolean'}});
accountSchemas.AuthContext={oneOf:[obj({authenticated:{const:false},csrfToken:text(256)}),obj({authenticated:{const:true},csrfToken:text(256),account:ref('AccountInfo')})]};
accountSchemas.IdentityBinding=obj({businessIdentity:{type:'string',enum:['DEMANDER','TUTOR']},expectedIdentityVersion:{type:'integer',minimum:0,maximum:9007199254740991}});
accountSchemas.ProfileContent=obj({displayName:text(30)},[]);
accountSchemas.EmailVerified=obj({verified:{const:true}});accountSchemas.PasswordReset=obj({reset:{const:true}});
accountSchemas.OpsStepUp=obj({password:{type:'string',minLength:1,maxLength:1024,writeOnly:true},totpCode:{type:'string',pattern:'^[0-9]{6}$',writeOnly:true}});
const requestKey={name:'Request-Key',in:'header',required:true,schema:uuid,description:'原匿名上下文+UUID唯一；id等于receiptId，首响应丢失仍可查；动作和正文参与指纹。'};
const authWrite=(operationId,summary,schema,result,http=200,parameters=[csrf,requestKey])=>({operationId,summary,parameters,requestBody:body(schema),responses:{[http]:response('安全结果；邮件接收不等于送达',result),...errors}});
const accountPaths={
 '/operations/{id}':paths['/operations/{id}'],
 '/ops/auth/step-up':{post:authWrite('stepUpOperations','独立岗位密码+TOTP强认证，响应丢失读context','OpsStepUp','AuthContext',200,[csrf])},
 '/auth/context':{get:read('getAuthContext','匿名或本人上下文/CSRF','AuthContext',{security:[]})},
 '/auth/registrations':{post:authWrite('registerAccount','开放注册，格式合法统一202','Register','AuthReceipt',202)},
 '/auth/receipts/{id}':{get:read('getAuthReceipt','发起本次动作的原匿名上下文，隐藏账号存在性','AuthReceipt',{parameters:[pathId]})},
 '/auth/email-verification-requests':{post:authWrite('resendVerification','受限重发，统一202','VerificationRequest','AuthReceipt',202)},
 '/auth/email-verifications':{post:authWrite('confirmEmailVerification','确认POST消费，不自动登录','TokenAction','EmailVerified')},
 '/auth/sessions':{post:authWrite('loginWithPassword','账号密码登录，失败统一','Login','AccountInfo',200,[csrf])},
 '/auth/password-reset-requests':{post:authWrite('requestPasswordReset','邮件找回，统一202','EmailRequest','AuthReceipt',202)},
 '/auth/password-resets':{post:authWrite('resetPassword','一次性重置，撤销全部会话，不自动登录','ResetPassword','PasswordReset')},
 '/auth/session/logout':{post:{operationId:'logoutSession',summary:'撤销当前会话，关联退出上下文重复安全204',parameters:[csrf],responses:{204:response('当前会话已撤销'),...errors}}},
 '/me/business-identity':{post:write('bindBusinessIdentity','首次单一身份绑定，非纠错/提权','IdentityBinding','IdentityResult',{parameters:[csrf,key,started]})},
 '/me/profile':{get:read('getMyProfile','本人最小资料','ProfileResult',{responses:{200:response('本人资料','ProfileResult',{headers:headers.etag}),...errors}}),put:write('replaceMyProfile','替换需求方最小资料，联系方式另行定义','ProfileContent','ProfileResult',{parameters:[csrf,key,started,{...ifMatch,description:'本人资料强ETag "profile:{accountId}:v{version}"；缺428、旧412。'}],responses:{200:response('新资料','ProfileResult',{headers:ownerDetailHeaders}),202:response('处理中','OperationProcessing',{headers:headers.processing}),412:problem('VERSION_CONFLICT'),428:problem('VERSION_REQUIRED'),...errors}})},
};
for(const path of ['/requests','/requests/{id}'])paths[path].get.responses={200:response('当前有效公开投影',path==='/requests'?'PublicPage':'PublicRequest'),400:errors[400],404:errors[404],429:errors[429],503:errors[503]};
accountPaths['/auth/sessions'].post.responses['401']=problem('INVALID_CREDENTIALS；账号不存在/密码错误统一');
accountPaths['/auth/sessions'].post.responses['403']=problem('CSRF_INVALID / EMAIL_NOT_VERIFIED；验证状态只在密码正确后提示');
accountPaths['/auth/email-verifications'].post.responses['403']=problem('CSRF_INVALID / REGISTRATION_PROOF_REQUIRED；需原注册上下文或注册密码');
accountPaths['/ops/auth/step-up'].post.responses['403']=problem('OPS_PERMISSION_REQUIRED / OPS_STEP_UP_FAILED / CSRF_INVALID');
paths['/ops/request-reviews/{id}/decisions'].post.responses['403']=problem('OPS_PERMISSION_REQUIRED / OPS_STEP_UP_REQUIRED / SELF_REVIEW_NOT_ALLOWED / CSRF_INVALID');
for(const path of ['/auth/context','/auth/sessions','/ops/auth/step-up']){
 const method=path==='/auth/context'?'get':'post';
 accountPaths[path][method].responses['200'].headers={...accountPaths[path][method].responses['200'].headers,'Set-Cookie':header(text(1024),'建立/轮换不透明会话Cookie；Secure/HttpOnly/SameSite=Lax/Path=/，不包含角色正文。')};
}
accountPaths['/auth/session/logout'].post.responses['204'].headers={...headers.private,'Set-Cookie':header(text(1024),'清除当前Cookie；服务端同时撤销原会话。')};
extendAccountContracts({schemas,accountSchemas,accountPaths,paths,ref,obj,text,uuid,instant,version,terminal,kinds,read,write,authWrite,csrf,key,started,pathId,cursor,limit,filter,body,response,problem,headers});
for(const branch of schemas.OperationSucceeded.oneOf)branch.properties.originalHttpStatus={type:'integer',minimum:200,maximum:299};
const failedBase={...terminal,originalHttpStatus:{type:'integer',minimum:400,maximum:599},status:{const:'FAILED'},error:ref('Problem')};
schemas.OperationFailed={oneOf:[obj({...failedBase,kind:{type:'string',enum:kinds.filter(kind=>kind!=='REVIEW_DECIDED')}}),obj({...failedBase,kind:{const:'REVIEW_DECIDED'},reviewOutcome:ref('ReviewOutcome')},Object.keys(failedBase))]};
accountSchemas.OperationFailed=schemas.OperationFailed;
extendRetentionContracts({schemas,accountSchemas,paths,accountPaths,obj,ref,text,problem});
const pending=['实际类别/主体准入及网络交易保留义务适用性核验','服务条款/隐私文本及版本','人工恢复受限方案及待补充30天结案已确认；预定机器契约、安全许可回执和独立人员仍缺','老师及关系的归档读模型和Q-03等既有关系处置','注销/唯一键释放及分类保留的生产适用依据','运营强认证登记/恢复运行验证','Figma和只读样例仅定向同步，不代表全部流程已验证'];
const make=(title,paths,schemas,description)=>({openapi:'3.1.1',info:{title,version:'0.3.1-design-draft',description},servers:[{url:'/api/v1'}],security:[{Session:[]}],'x-design-status':'design-draft-with-business-blockers','x-pending':pending,paths,components:{securitySchemes:{Session:{type:'apiKey',in:'cookie',name:'__Host-linke_session',description:'服务端同源会话，含受限匿名上下文；Cookie存在不等于登录。Secure/HttpOnly/SameSite=Lax/Path=/；写入CSRF+Origin。'}},schemas}});
const requestSpec=make('邻课：需求草稿—提交—审核—公开浏览',paths,schemas,'v0.2设计稿；权限/事务/恢复见request-flow-contract-v0.2.md。无真实实现；规范验证与业务验收分别记录，见最新交付记录。');
const accountSpec=make('邻课：账号与权限前置',accountPaths,accountSchemas,'配套account-access-contract-v0.1.md；匿名AuthReceipt与已登录Operation隔离；分类保留已确认；受限人工恢复预定操作尚未进入本机器稿，不能用本稿验证报告证明恢复协议完整。');
await mkdir(new URL('../../contracts/',import.meta.url),{recursive:true});
for(const [file,spec]of [['request-flow.openapi.json',requestSpec],['account-access.openapi.json',accountSpec]])await writeFile(new URL('../../contracts/'+file,import.meta.url),JSON.stringify(spec,null,2)+'\n');
console.log(JSON.stringify({generated:[requestSpec,accountSpec].map(s=>({title:s.info.title,paths:Object.keys(s.paths).length,operations:Object.values(s.paths).reduce((n,p)=>n+Object.keys(p).length,0),schemas:Object.keys(s.components.schemas).length})),implementation:false}));
