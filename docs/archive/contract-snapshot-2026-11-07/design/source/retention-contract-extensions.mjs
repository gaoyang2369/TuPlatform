// Design projection rules only. No recovery implementation or retention worker.
export function extendRetentionContracts({schemas, accountSchemas, paths, accountPaths, obj, ref, problem}) {
 const rule = obj({categoryCode:{type:'string',minLength:1,maxLength:64,pattern:'^[A-Z0-9][A-Z0-9_-]*$'},audienceStageCode:{type:'string',enum:['PRIMARY','JUNIOR','SENIOR','ADULT','OTHER']},serviceMode:{type:'string',enum:['ONLINE','OFFLINE','UNSPECIFIED']},allowSubmit:{type:'boolean'}});
 const operationDescription=schemas.Operation.description+' 原终态事实不改写；若已合法清理/限制结果正文，查询或重放410 OPERATION_RESULT_UNAVAILABLE，不再次执行，不回放已删内容。';
 // UNSPECIFIED represents omitted serviceMode; never silently default to OFFLINE.
 for (const set of [schemas,accountSchemas]) {
  set.PublishingRule=rule;
  set.PublishingOptions.properties.rules={type:'array',items:ref('PublishingRule'),description:'类别×阶段×方式准入组合；单个选项有效不等于组合允许。未命中组合禁止提交。'};
  if(!set.PublishingOptions.required.includes('rules'))set.PublishingOptions.required.push('rules');
  for(const name of ['OwnerPage','OwnerSubmissionPage'])set[name].description='只列仍在普通历史访问范围内且正文可读的对象；已清正文不伪造成空内容。180天按各类起算事件独立计算，见data-retention-spec-v0.1.md。';
  set.Operation.description=operationDescription;
  set.IdentityArchive.description='本人旧epoch归档元信息；资源计数为当前可读取正文数量，不承诺永久保留；角色资料180天、草稿90天、提交历史分别计算。';
 }
 for(const p of ['/me/requests/{id}','/operations/{id}'])paths[p].get.responses['410']=problem(p.startsWith('/operations')?'OPERATION_RESULT_UNAVAILABLE；原终态不改变，不推断未发生':'CONTENT_UNAVAILABLE；已获权但正文已清或超普通历史范围；他人404');
 for(const p of ['/me/identity-archives/{id}/requests/{requestId}'])accountPaths[p].get.responses['410']=problem('CONTENT_UNAVAILABLE；归属校验后说明正文不可读，不返回空SubmissionContent');
 for(const pathsBySpec of [paths,accountPaths])for(const item of Object.values(pathsBySpec))for(const [method,op]of Object.entries(item))if(['post','put','patch','delete'].includes(method)&&op.parameters?.some(p=>p.name==='Idempotency-Key')){
  op.responses['410']=problem('OPERATION_RESULT_UNAVAILABLE（原键重放且结果正文已清/受限，原终态不变）；或本动作已定义的REQUEST_EXPIRED / TOKEN_UNUSABLE。');
 }
 paths['/request-publishing-options'].get.description='rules是服务端当前组合准入投影，UNSPECIFIED匹配省略方式；不得从categories.allowSubmit自行生成笛卡尔积。测试配置不用于真实开放。';
 // Both specs share the operation path object; schema parity is verified separately.
}
