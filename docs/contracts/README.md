# 首版必要接口与数据设计

2026-11-07依据[主规格](../mvp-spec.md)和[核心页面](../design/page-inventory.md)整理。2026-11-10已完成五段首版闭环，包含本人私密联系方式和双方逐项授权/撤回，两库迁移至V5并完成真实数据库及Chrome验收。本页记录首版端点、权限和当前结构差异；不是旧0.3.1全部端点的延续。

当前实现路径：/auth/*、/me、/me/business-identity、/me/profile、/me/teacher-profile及其publish/unpublish、/teachers/{id}；草稿GET /publishing-options、POST /requests、GET /me/requests、GET /requests/{id}、PATCH /requests/{id}/draft；以及POST /requests/{id}/submit、POST /requests/{id}/revisions、GET /public/requests及/{id}、GET /operations/submissions及/{id}、POST /operations/submissions/{id}/decision。GET /auth/csrf返回{token,headerName}供同源SPA写入；GET /profile-options返回categoryCodes、serviceModes、areaCodes。第四段已实现并真实联调GET /teachers、GET /me/matches、GET /matches/{id}、POST /matches以及accept/reject/withdraw-intent；V4已获准在两库执行。第五段已实现并联调GET/PUT /me/contacts和GET/PUT/DELETE /matches/{id}/contact-consent。未生成当前段OpenAPI机器稿，已实现路由及字段以本文和Controller/record代码核对。

本人需求当前返回SavedDraft：{id,status,version,fields,lastSavedAt,retentionReminder,latestSubmission}；latestSubmission为null或{id,fields,publisherDisplayName,publisherDeclaration,declarationVersion,submittedAt,reviewFeedback}。尚不包含未来撮合计数。status按读取时刻返回EXPIRED，不向普通用户返回expiresAt。本人列表只支持cursor、固定每页20项，返回{items,nextCursor}，暂不支持status筛选。发布选项返回代码数组{categories,streets,audienceStages,serviceModes}和publisherDeclarations:[{code,version,label}]；REQUEST_CATEGORIES为空时categories为空，页面解释可先保存其他字段。草稿写入检查X-Account-Id和CSRF；创建使用Idempotency-Key，相同键返回同一需求当前状态，原草稿清理后该键返回DRAFT_REMOVED/410，不盲目重建。保存仅允许DRAFT，null清空、部分更新，旧版本VERSION_CONFLICT/409。最后保存83天起返回retentionReminder，90天起读接口即不可见；每小时清理过期DRAFT，提醒不发送邮件。

提交、修订、审核当前返回{id,submissionId,status,version}，version为需求版本；审核请求的expectedVersion则来自运营读取的提交版本。三类动作分别保存本账号的幂等回执，相同键与内容返回原动作结果，同键不同内容返回IDEMPOTENCY_CONFLICT/409；读取最新对象确认当前状态。审核写入也检查X-Account-Id与CSRF，独立核对operator_granted，不能审核本人。运营列表支持cursor/status，状态为PENDING_REVIEW（默认）、APPROVED、REJECTED、EXPIRED，每页20项；目前返回与运营详情相同的固定提交结构，并增加publisherDisplayName、declarationVersion、reviewFeedback、priorReviewFeedback。驳回理由最多1000码点且不可仅空白。审核结果由本人进度页读取，当前不发送审核邮件。

公开列表支持cursor/categoryCode/areaCode/serviceMode，返回{items,nextCursor}；详情和列表只映射PublicRequest白名单，展示名来自提交时快照。审核通过、最新提交、需求OPEN、未到期且类别当前允许才可见；任一条件不符详情404，列表过滤，不等待定时任务。自由文本以Vue文本插值显示，运营审核页面提示核对私密信息，不用自动字符串过滤代替人工审核。

## 共用约定

- 基础路径 `/api/v1`，JSON字段使用下文名称。ID为不透明字符串；写入时的`expectedVersion`为对象读取时返回的整数版本。返回成功只表示对应动作已在服务端完成，不把网络超时当成未发生。
- Web使用服务端会话及HttpOnly Cookie；业务写入检查CSRF。账号未验证邮箱或未选业务身份时，仅开放完成相应步骤及正常退出/找回所需动作。运营权限独立检查，不接受客户端传入运营身份。
- 第一段会话Cookie为LK_SESSION，7天到期、可服务端撤销；数据库只存凭据摘要。注册上下文使用独立HttpOnly Cookie LK_REGISTRATION，registrationFlowId自身不足以重发或确认。邮件凭据通过URL片段进入前端并立即从浏览器地址移除，确认和重置均以POST明确提交。
- 本人路径写入须带X-Account-Id，值来自当前GET /me；服务端只用它与实际会话比较，不能用来指定代操作对象。不同返回SESSION_CHANGED/409，避免跨标签页更换账号后把旧表单写入新账号。资料和老师详情读取若带该头也须匹配。CSRF失效使用CSRF_INVALID/403，前端只对此类已被拦截的请求更新CSRF并重试一次，不自动重试其他业务失败。
- 创建、提交、回应、审核、公开与授权动作携带`Idempotency-Key`；相同账号、动作、键和内容返回原动作结果，同键不同内容返回409。按具体业务对象解决重复与冲突，不建设通用操作恢复平台。结果未知时查询对应对象；新建结果未知时重试同键，不换键盲建。
- 本人列表与业务列表使用`items`、`nextCursor`，默认20项，单次最多50项；不返回完整历史大包。各列表的服务端范围由当前登录者决定，客户端不能通过传他人`userId`越权。
- 错误体为`{code,message,fieldErrors?,currentVersion?}`；`fieldErrors`按字段路径给出可读提示。401需重新登录；403身份/动作不允许；私有对象不属于本人时按404处理。409表示已有结果或版本变化；422为输入/业务条件不满足。错误不附私密对象、密码、令牌或对方联系方式。
- 账号、关系、本人资料与授权响应不缓存；公开需求使用明确的公开DTO。禁止先返回完整私有对象再由前端隐藏字段。时间判断与权限每次请求执行，不依赖界面状态或定时清理。

## 请求与返回的最小结构

所有必填、条件必填、长度及业务约束沿用主规格；下表确定接口字段和表达方式，不另建第二套规则。

| 结构 | 字段 |
| --- | --- |
| 当前账号 `Me` | `id, account, email, emailVerified, businessIdentity, operatorGranted, profileVersion`；仅本人可读，不返回密码或散列 |
| 本人资料 `Profile` | `displayName, version`；老师另有`teacher:{categoryCodes[], serviceModes[], areaCodes[], introduction, priceMin?, priceMax?, availabilityText?, public, version}`；本人读取允许未完成字段为空 |
| 老师资料写入 | `expectedVersion, displayName, categoryCodes[], serviceModes[], areaCodes[], introduction, priceMin?, priceMax?, availabilityText?`；不能通过本次保存直接写`public=true`；仅老师本人 |
| 私密联系方式 `Contacts` | `version, phone?, wechat?, fieldVersions:{phone,wechat}`；仅本人读写，手机号地区固定`+86` |
| 草稿字段 `RequestFields` | `title, categoryCode, audienceStageCode, areaCode, goal, serviceMode?, budgetMin, budgetMax, durationHours, frequency, availableSlots[], availabilityNote?, expectedStartDate?, tutorExpectations?`；草稿可缺必填字段 |
| 频率与时段 | `frequency:{mode:PER_WEEK,count:1..14}`或`{mode:NEGOTIABLE,count:null}`；`availableSlots:[{weekday:1..7,startMinute,endMinute}]`，周一为1，开始0—1439、结束1—1440，开始小于结束，1440只表示结束24:00；同日不得重叠 |
| 提交请求 | `expectedVersion, publisherDeclaration:SELF_ADULT或GUARDIAN_OF_MINOR, declarationVersion:publisher-v1`；不从草稿复制旧勾选 |
| 本人需求 `MyRequest` | `id,status,version,draft?,latestSubmission?,reviewFeedback?,activeMatchCounts`；本人可读当时提交、声明和审核反馈；无用户填写/查看的具体截止日 |
| 公开需求 `PublicRequest` | `id, submissionId, publisherDisplayName, title, categoryCode, audienceStageCode, areaCode, goal, serviceMode?, budgetMin,budgetMax,durationHours,frequency,availableSlots,availabilityNote?,expectedStartDate?,tutorExpectations?`；不含联系方式、邮箱、精确地址、发布声明、具体截止日或内部审核信息 |
| 公开老师 `PublicTeacher` | `id, profileVersion, displayName, categoryCodes,serviceModes,areaCodes,introduction,priceMin?,priceMax?,availabilityText?`；资料明确为本人自述，不返回核验/认证标识、联系方式和邮箱 |
| 撮合 `Match` | `id,requestId,version,roundId,state:WAITING或MUTUAL或REJECTED或WITHDRAWN,initiatorRole,myRole,waitingForMe,requestExpired,teacherPublic,canAccept,canReject,canWithdraw,canInitiateAgain,submissionSnapshot,teacherSnapshot`；快照仅含本次沟通所需需求/老师业务字段，不含发布声明、具体截止日、审核内部信息或私密联系方式 |
| 授权进度 `Grant` | `version,myConsent,otherConsent,mySelectedFields[],requiresReconfirmation,canViewContacts`；仅双方当前有效同意且选中值仍匹配时，另返回`mySharedContacts`和`otherSharedContacts`，其中仅含选中手机号/微信；其余状态完全省略这两个值对象 |

金额用十进制字符串，不用浮点数、科学记数法、千分位或货币符号；预算与老师报价至多两位小数，时长用如`"0.5"`、`"1"`的小时字符串。数据库金额可沿用`numeric(14,2)`作为技术容量；超出可存储范围明确422提示，不舍入、不截断，也不将容量解释为指导价或999元上限。日期为`YYYY-MM-DD`，提交当天按Asia/Shanghai校验。

## 账号与本人资料

| 方法与路径 | 请求 → 返回 | 权限、条件及主要错误 |
| --- | --- | --- |
| `POST /auth/register` | `{account,password,email,adultConfirmed:true}` → `{registrationFlowId,emailVerificationRequired:true,mailAccepted}` | 主动成年声明；按主规格检查账号/密码/邮箱；注册不自动登录；422字段错误，发信请求失败清楚提示 |
| `POST /auth/email-verification/inspect` | `{token}` → `{valid,requiresRegistrationPassword}` | 仅查看链接状态，不消费；原注册上下文缺失则需核对注册密码；不返回令牌、邮箱或账号详情 |
| `POST /auth/email-verification/confirm` | `{token,registrationPassword?}` → `{verified:true,loginRequired:true}` | 明确提交才消费一次性链接；缺上下文时密码必需；失效/已用/核对失败清楚提示，不自动登录 |
| `POST /auth/email-verification/resend` | `{registrationFlowId}` → `{accepted:true}` | 限原注册流程；重发不延长未验证注册首次登记7天期限；失败不称已送达 |
| `POST /auth/login` | `{account,password}` → `Me`并设置会话Cookie | 不区分账号不存在与密码错误；未验证不能进入业务写入；错误不返回密码 |
| `POST /auth/logout` | 无业务字段 → 204 | 撤销当前会话，客户端清除当前账号私有视图 |
| `POST /auth/forgot-password` | `{email}` → 202 `{accepted:true}` | 不暴露邮箱存在性；回复表示受理，不保证已送达；不可受理时给出稍后重试提示 |
| `POST /auth/reset-password` | `{token,newPassword}` → `{reset:true,loginRequired:true}` | 明确提交才消费链接，安全散列保存并撤销全部旧会话；邮件页面GET不执行此动作 |
| `GET /me` | 无 → `Me` | 当前账号；无会话401 |
| `PUT /me/business-identity` | `{identity:REQUESTER或TUTOR}` → `Me` | 首次绑定；同身份重复返回原结果，竞争选择不同身份409；不支持改身份或授予运营 |
| `GET /me/profile` | 无 → `Profile` | 已验证且选身份的本人 |
| `PATCH /me/profile` | `{expectedVersion,displayName}` → `{displayName,version}` | 需求方本人基础资料；老师展示名与老师资料一起保存，避免绕过公开资料更新条件 |
| `PUT /me/teacher-profile` | 老师资料写入结构 → `Profile` | 老师本人；未公开可保存不完整资料，已填值合法；已公开须仍满足全部必填要求，成功即更新公开展示；不改历史撮合快照；409版本冲突、422输入错误 |
| `POST /me/teacher-profile/publish` | `{expectedVersion}` → `Profile` | 老师本人主动公开；全部必填合法；不设人工资料审核；重复返回当前公开结果 |
| `POST /me/teacher-profile/unpublish` | `{expectedVersion}` → `Profile` | 老师本人停止公开，立刻停止新增撮合；已有关系继续，不撤销授权、不删除快照 |
| `GET /me/contacts` | 无 → `Contacts` | 本人；没有填写返回空字段，不作为发布/投递门槛 |
| `PUT /me/contacts` | `{expectedVersion,phone?,wechat?}` → `{contacts:Contacts,affectedGrantCount}` | 本人；明确空值为删除；修改已分享字段时，受影响关系停止展示并要求双方重新确认；未分享字段修改不影响其他授权 |

`mailAccepted`仅表示已受理发信请求，不能用作“已送达”的证明。密码确认只在表单核对，不再落库。未验证注册与草稿保留/提醒按主规格处理，不引入恢复、换绑或历史管理接口。

## 需求、审核与发现

| 方法与路径 | 请求 → 返回 | 权限、条件及主要错误 |
| --- | --- | --- |
| `GET /publishing-options` | 无 → `{categories,streets,audienceStages,serviceModes,publisherDeclarations}` | 只返回当前允许的组合；无开放类别时返回空类别及可读说明，不默认开放历史代码 |
| `POST /requests` | 无业务字段 → `{id,status:DRAFT,version,fields:{}}` | 需求方本人，新建一次草稿；同幂等键返回同一草稿 |
| `GET /me/requests` | `cursor?,status?` → `MyRequest`摘要列表 | 仅本人；不以角色切换模拟跨账号 |
| `GET /requests/{id}` | 无 → `MyRequest` | 仅需求本人；他人404；按当前时间计算有效状态 |
| `PATCH /requests/{id}/draft` | `{expectedVersion,fields:部分RequestFields}` → `{id,status:DRAFT,version,fields}` | 本人可编辑草稿；只更新传入字段，第二步不清空第一步；显式null清空，草稿可不完整；已填值非法422；待审/公开不可写409 |
| `POST /requests/{id}/submit` | 提交请求结构 → `{id,submissionId,status:PENDING_REVIEW,version}` | 本人需求方、本人展示名、完整字段及主动声明均满足；固定提交快照、开启本次期限；重复不产生新提交；422缺项或非法日期/声明，409旧版本 |
| `POST /requests/{id}/revisions` | `{expectedVersion}` → `{id,status:DRAFT,version,fields}` | 仅本人已驳回或有效状态已到期；从上次内容生成可修订草稿，旧提交/反馈不覆盖；新提交重新确认声明与审核，不复制旧关系 |
| `GET /public/requests` | `cursor?,categoryCode?,areaCode?,serviceMode?` → `PublicRequest`摘要列表 | 匿名可读，仅审核通过且当前有效的需求；到期立即过滤，不等清理任务 |
| `GET /public/requests/{id}` | 无 → `PublicRequest` | 匿名可读；未公开、待审、到期或不准入均404，不回私有原因 |
| `GET /teachers` | `cursor?,categoryCode?,areaCode?,serviceMode?` → `PublicTeacher`摘要列表 | 已登录需求方；只返回老师主动公开的当前资料 |
| `GET /teachers/{id}` | 无 → `PublicTeacher` | 已登录需求方，目标资料当前公开；未公开404；关系内的当时快照从关系接口读取 |
| `GET /operations/submissions` | `cursor?,status?` → `{id,requestId,status,submittedAt,summary}`列表 | 独立运营权限；审核队列不列本人账号的需求 |
| `GET /operations/submissions/{id}` | 无 → `{id,requestId,status,version,submittedAt,expiresAt,fields,publisherDeclaration,priorReviewFeedback?}` | 运营读取固定提交内容；内部期限用于防止过期批准，不进入普通用户公开响应；本人需求不得进入可审核界面 |
| `POST /operations/submissions/{id}/decision` | `{expectedVersion,decision:APPROVE或REJECT,reason?}` → `{submissionId,status,reviewedAt,version}` | 运营且非本人、当前仍待审且未到期；驳回理由非空白；只能一个合法结果；409已决定/过期/旧版本；不改写用户正文 |

提交时存`submittedAt`，有效期边界按北京时间提交日期加15天的00:00计算，例如11月6日提交，11月21日00:00到期；这等价于提交日期加14天对应日结束。批准不重置期限。`expiresAt`仅作服务端判定及运营内部必要字段，不向普通用户返回。审核已提交成功后，通知失败不回滚决定。

## 投递、邀请、回应与授权

| 方法与路径 | 请求 → 返回 | 权限、条件及主要错误 |
| --- | --- | --- |
| `GET /me/matches` | `cursor?,state?,requestId?` → `Match`摘要列表 | 当前关系参与方本人；需求方按本人需求筛选，老师只看本人的投递/邀请 |
| `GET /matches/{id}` | 无 → `Match` | 仅当前关系双方；到期或老师停止公开仍可读本次必要快照，无关人404 |
| `POST /matches` | `{requestId,expectedSubmissionId,kind:APPLICATION或INVITATION,tutorUserId?,expectedTeacherProfileVersion}` → `{match:Match,outcome:CREATED或EXISTING}` | APPLICATION仅老师本人，不接受代投老师ID；INVITATION仅该需求本人且必须指定老师；新增/重发要求需求有效公开、老师资料公开；已有进行中关系返回EXISTING，不改为互选；409内容版本变化，422需求到期/资料未公开/同版被拒绝不可重发 |
| `POST /matches/{id}/accept` | `{roundId,expectedVersion}` → `Match` | 仅待回应方；当前轮次仍WAITING；到期/停止公开不阻断已有回应；接受后MUTUAL，不产生授权；409已撤回/已回应/旧轮次 |
| `POST /matches/{id}/reject` | `{roundId,expectedVersion}` → `Match` | 仅待回应方，当前轮次WAITING；无需理由；记录谁拒绝了谁以及本次提交版本，用于阻止被拒绝方同版重发 |
| `POST /matches/{id}/withdraw-intent` | `{roundId,expectedVersion}` → `Match` | 仅本轮发起方且仍WAITING；接受已先成功则409并重读，不撤销互选或联系方式授权 |
| `GET /matches/{id}/contact-consent` | 无 → `Grant` | 仅双方；读取当下双方意愿、选中字段版本及本轮互选状态后决定是否附值；不凭浏览器旧状态展示 |
| `PUT /matches/{id}/contact-consent` | `{roundId,expectedGrantVersion,selectedFields:[PHONE或WECHAT],expectedContactFieldVersions,agree:true}` → `Grant` | 仅本人的授权；本轮已互选；至少一个本人非空字段；字段变化或对方授权状态冲突409，重读并核对；不接受邮箱、对方值或代对方同意 |
| `DELETE /matches/{id}/contact-consent` | `{roundId,expectedGrantVersion}` → `Grant` | 仅撤回本人的授权；立即停止本次关系双方后续联系方式访问；保留对方独立意愿，不影响其他关系；旧页再次读取也不能得到撤回后的值 |

同一需求与同一老师一条关系、每次重新发起一轮意向；同一进行中关系不因需求到期重提而复制。新一轮不继承回应或授权。拒绝方可在新增条件满足时主动重发；被拒绝方不能对同一提交版本重发。撤回后满足新增条件可重发。到期禁止任何新轮次，但不阻断既有轮次的回应/互选/授权；接口返回相应`can*`和提示，服务端仍独立校验。

主要业务错误：`PROFILE_INCOMPLETE`、`PROFILE_NOT_PUBLIC`、`REQUEST_NOT_OPEN`、`REQUEST_EXPIRED`、`SUBMISSION_CHANGED`、`TEACHER_PROFILE_CHANGED`、`REAPPLY_BLOCKED`、`MATCH_STATE_CHANGED`、`NOT_MUTUAL`、`CONTACT_FIELD_CHANGED`、`CONTACT_FIELD_EMPTY`、`VERSION_CONFLICT`。错误码只表达当前动作失败原因，不向无权读取者证明私有对象存在。

## 所需核心实体

下表是首版PostgreSQL实体设计。获准的tuplatform和tuplatform_test已执行V1账号/资料、V2草稿、V3提交审核、V4撮合及V5联系方式/授权迁移。V3新增request_submissions、review_decisions、request_action_receipts，并在service_requests增加latest_submission_id；V4新增关系、轮次与撮合回执三表。固定快照与决定分开存储，提交版本和需求版本分别检查。V5独立保存本人联系方式、本轮双方授权及不含私密值的动作回执。字段列只列协作需要的主数据，不建设通用平台表。

| 实体 | 主要内容与约束 |
| --- | --- |
| `Account` | 账号规范值唯一、邮箱规范值唯一、原始显示值、密码安全散列、邮箱验证状态、成年声明记录、单一业务身份；运营权限独立字段/授权记录，普通请求不可写 |
| `AuthSession` / `EmailToken` | 会话撤销及到期；验证/重置用途、不可逆令牌摘要、一次消费、原注册上下文绑定；重置密码撤销全部旧会话；不保存明文密码/邮件令牌 |
| `BaseProfile` / `TeacherProfile` | 本人展示名；老师类别/方式/街道集合、介绍、选填报价/时间、公开标志及版本；公开时资料完整；无认证材料或人工资料审核表 |
| `PrivateContacts` | 本人手机号/微信及各字段版本；私密存储，不混入公开DTO、提交/撮合快照或普通日志 |
| `ServiceRequest` / `RequestDraft` | 需求归属、当前草稿、最新提交引用、可写版本与最后成功保存时间；部分保存只改传入字段；草稿未提交不启动有效期 |
| `RequestSubmission` | 不可变提交内容、提交时需求方展示名、当次私有声明、提交时刻、到期边界、审核状态；旧提交不被修订覆盖 |
| `ReviewDecision` | 提交引用、运营、决定、驳回理由、时间；一提交最多一个最终决定，运营不是需求本人；不依赖通知成功 |
| `MatchRelationship` / `MatchRound` | `(requestId,tutorUserId)`唯一关系，当前轮次与版本；每轮提交引用、发起人、当时老师业务资料快照、状态、回应/结束方及时间；只保留本次流程所需记录，拒绝限制按当次提交版本检查 |
| `ContactConsent` | 关系/轮次/本人、选中字段及版本、明确同意、撤回与当前授权版本；修改已分享字段时使受影响关系双方重新确认；读取时两边都有效才返回值，不依赖异步清理 |

跨实体动作在同一业务事务内完成：提交与快照/有效期、审核唯一决定、创建或重用同一撮合关系、回应与撤回竞争、已分享联系方式变更与受影响授权失效。使用当前对象版本与数据库唯一性处理必要冲突；不引入通用工作流、操作恢复中心或复杂权限配置。

旧0.3.1机器稿只作[历史参考](../archive/README.md)，其中延期端点不实施。后续可在实际开发相应业务段时，把本页对应接口转为OpenAPI并随联调核对；无需先补完历史契约或一次性实现全部端点。

## 第四段当前实现与迁移边界

老师及撮合列表固定每页20项，cursor为上一页最后一个不透明UUID，按ID降序；老师筛选categoryCode/areaCode/serviceMode，撮合筛选state/requestId，后者始终与当前参与方范围取交集。Match另有submissionChanged、teacherProfileChanged，提醒查看时保留原快照；不返回对方邮箱、联系方式、私有声明、截止时刻及审核反馈。老师列表与关系根路径也检查X-Account-Id。

V4脚本为backend/src/main/resources/db/migration/V4__match_relationships.sql：新增match_relationships、match_rounds、match_action_receipts三表，及双方列表、同版拒绝历史三个显式索引。关系唯一约束为(request_id,tutor_id)，当前轮次使用延迟外键绑定到同一关系。每轮保存提交引用及需求/老师白名单快照；回应时间、回应方独立记录。拒绝限制查询同提交版本的历史拒绝轮次，后续撤回不会绕过原限制。新增与回应按相同顺序锁定双方账号/资料，再锁需求或关系，避免双方相遇产生两条关系或自动互选。重复动作返回原回执，客户端成功后再读当前关系。

需求与提交引用为RESTRICT；只清理从未提交且超过90天的草稿，修订草稿的旧提交和关系保留。私有草稿读取仍有90天边界，关系快照持续可读。既有V1—V3不修改。V4已获用户许可在测试库联调后应用两库；合成业务写入仅限_test并已清理。无关老师不能用发起错误探测私有草稿或待审提交，统一404。响应不可解析或连接失败保留同一操作凭据；回应已成功而详情刷新失败时明确区分写入成功与刷新失败，仍可同键恢复或读取最新结果。

## 第五段实现与V5范围

联系方式五个端点已实现于ContactController/ContactService，测试库V5和真实数据库用例通过。Contacts以独立版本维护；PUT为完整替换，null或空字符串删除，不trim非空输入。字段版本只在该字段值实际变化时递增，私密修改不改变老师公开资料版本。Grant另返回roundId、otherRequiresReconfirmation；双方值对象只在canViewContacts=true时出现，未选字段完全省略。

授权版本按当前轮次共享，双方动作均增加version；同意要求最新授权版本和本人选中字段版本。撤回仅清本人意愿及选择，保留对方独立意愿。联系方式修改在同一事务按关系ID加锁，对选中过已改字段的当前关系清除双方有效同意、标记双方重确认并递增授权版本；包括单方已同意、等待对方的状态。未选字段及其他关系不受影响。读取授权和授权动作均先按双方账号ID顺序加锁，再锁关系，与第四段顺序相同。每次读取检查当前轮次、互选和双方字段版本。

contact_action_receipts只存动作指纹和关系/轮次标识，不保存响应或手机号/微信值。同键重试校验原动作内容后，返回当前授权投影，不重新执行同意或撤回，不恢复旧值。前端在开始刷新、失败、撤回及离开时清除旧分享对象；切回页面及15秒定期读取当前进度。页面刷新机制不能收回对方已知信息，也不承诺远程擦除已打开页面。

V5具体脚本：backend/src/main/resources/db/migration/V5__private_contacts_and_consent.sql。新增三表，不更改V1—V4或已有业务表：

| 表 | 列及约束 |
| --- | --- |
| private_contacts | account_id主键、账号外键级联清理；version、phone、wechat、phone_version、wechat_version；版本非负、手机号及微信长度/空白约束，服务端另检查Unicode格式字符 |
| contact_grants | round_id主键，match_id/round_id复合外键绑定同一撮合轮次，随轮次级联清理；version；requester/tutor各自agreed、phone_version、wechat_version、reconfirm；已同意必须至少有一个字段版本且无需重确认；显式索引contact_grants_match(match_id) |
| contact_action_receipts | actor_id/action/idempotency_key复合主键；账号外键、match_id/round_id复合外键及级联清理；fingerprint；action仅AGREE/WITHDRAW；不存分享值或响应 |

用户已明确许可：先在tuplatform_test执行V5和重复启动校验，创建/修改/清理合成账号、会话、资料、需求、提交、审核、应用运营标记、撮合及回执，并新增合成private_contacts/contact_grants/contact_action_receipts写入；沿用_test夹具的邮件令牌、已知loopback限流桶及合成需求到期操作。清理核查后，仅在tuplatform应用同一V5结构及重复启动校验，业务库不写合成业务数据。不改服务器配置，不发真实邮件，不部署或授予真实运营权限。实际执行结果维护在交付计划和核心验收记录。
