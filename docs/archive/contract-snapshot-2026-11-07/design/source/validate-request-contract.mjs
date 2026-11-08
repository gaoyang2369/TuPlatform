// Static artifact validation only. No server, domain execution, or full OAS meta-schema.
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const specs=await Promise.all(['request-flow','account-access'].map(async name=>({name,spec:JSON.parse(await readFile(new URL('../../contracts/'+name+'.openapi.json',import.meta.url),'utf8'))})));
const methods=new Set(['get','post','put','patch','delete','head','options','trace']);
const reports=[];
for(const {name,spec}of specs){
 let refs=0;
 const resolveRef=path=>{assert(path.startsWith('#/'),'Only local refs');let target=spec;for(const p of path.slice(2).split('/'))target=target?.[p];assert(target,'Missing '+path);return target;};
 const walk=value=>{if(!value||typeof value!=='object')return;if(value.$ref){refs++;resolveRef(value.$ref);}if(value.type==='object'&&value.required)for(const k of value.required)assert(Object.hasOwn(value.properties,k),'Missing required property '+k);for(const v of Object.values(value))walk(v);};
 walk(spec);
 const operationIds=[];
 for(const [path,item]of Object.entries(spec.paths))for(const [method,op]of Object.entries(item)){
  if(!methods.has(method))continue;
  assert(op.operationId,'Missing operationId');operationIds.push(op.operationId);
  const actual=(op.parameters??[]).filter(p=>p.in==='path').map(p=>p.name).sort();
  const expected=[...path.matchAll(/\{([^}]+)\}/g)].map(m=>m[1]).sort();assert.deepEqual(actual,expected,'Path parameters '+path);
  assert.equal(new Set((op.parameters??[]).map(p=>p.in+':'+p.name)).size,(op.parameters??[]).length,'Duplicate parameter');
  for(const p of (op.parameters??[]).filter(p=>p.in==='path'))assert(p.required);
  assert(Object.keys(op.responses).every(c=>/^[1-5][0-9X]{2}$/.test(c)));
  for(const response of Object.values(op.responses))assert.equal(response.headers?.['Cache-Control']?.schema?.const,'no-store','Cache policy');
  if(['post','put','patch','delete'].includes(method))assert(op.parameters?.some(p=>p.name==='X-CSRF-Token'&&p.required),'Missing CSRF '+op.operationId);
  if(op.parameters?.some(p=>p.name==='Idempotency-Key')){assert(op.parameters.some(p=>p.name==='Operation-Started-At'&&p.required));assert(op.responses['202']?.content?.['application/json']?.schema.$ref.endsWith('/OperationProcessing'));}
 }
 assert.equal(new Set(operationIds).size,operationIds.length,'Duplicate operationId');
 assert.equal(spec.components.securitySchemes.Session.name,'__Host-linke_session');
 assert(spec['x-pending'].length,'Must retain actual blockers');
 // Recursively inspect public projections, not just their top-level DTOs.
 const forbidden=new Set(['phone','wechat','email','ownerId','expiresAt','exactAddress','feedback','reviewerId','publisherDeclaration','opsCapabilities']);
 const visited=new Set();
 function publicWalk(schema){if(schema.$ref){if(visited.has(schema.$ref))return;visited.add(schema.$ref);return publicWalk(resolveRef(schema.$ref));}for(const k of Object.keys(schema.properties??{}))assert(!forbidden.has(k),'Public leak '+k);if(schema.type==='object')assert.equal(schema.additionalProperties,false);for(const v of Object.values(schema.properties??{}))publicWalk(v);if(schema.items)publicWalk(schema.items);for(const option of schema.oneOf??[])publicWalk(option);}
 publicWalk(spec.components.schemas.PublicRequest);
 for(const s of ['OwnerRequest','OwnerSubmission'])assert(!Object.hasOwn(spec.components.schemas[s].properties,'expiresAt'));
 // Small checker for the schema keywords used by our fixtures; not a general JSON Schema engine.
 function matches(schema,value){
  if(schema.$ref)return matches(resolveRef(schema.$ref),value);
  if(schema.oneOf&&schema.oneOf.filter(s=>matches(s,value)).length!==1)return false;
  if(schema.allOf&&!schema.allOf.every(s=>matches(s,value)))return false;
  if(Object.hasOwn(schema,'const')&&JSON.stringify(schema.const)!==JSON.stringify(value))return false;
  if(schema.enum&&!schema.enum.some(x=>JSON.stringify(x)===JSON.stringify(value)))return false;
  if(schema.type){const allowed=Array.isArray(schema.type)?schema.type:[schema.type];const actual=value===null?'null':Array.isArray(value)?'array':typeof value;if(!allowed.some(t=>t===actual||(t==='integer'&&typeof value==='number'&&Number.isInteger(value))||(t==='object'&&actual==='object')))return false;}
  if(typeof value==='string'){const len=[...value].length;if(schema.minLength!==undefined&&len<schema.minLength)return false;if(schema.maxLength!==undefined&&len>schema.maxLength)return false;if(schema.pattern&&!new RegExp(schema.pattern,'u').test(value))return false;}
  if(typeof value==='number'){if(schema.minimum!==undefined&&value<schema.minimum)return false;if(schema.maximum!==undefined&&value>schema.maximum)return false;}
  if(Array.isArray(value)){if(schema.minItems!==undefined&&value.length<schema.minItems)return false;if(schema.maxItems!==undefined&&value.length>schema.maxItems)return false;if(schema.items&&!value.every(v=>matches(schema.items,v)))return false;}
  if(value!==null&&typeof value==='object'&&!Array.isArray(value)){if((schema.required??[]).some(k=>!Object.hasOwn(value,k)))return false;for(const[k,v]of Object.entries(value)){if(schema.properties?.[k]){if(!matches(schema.properties[k],v))return false;}else if(schema.additionalProperties===false)return false;}}
  return true;
 }
 const id='11111111-1111-4111-8111-111111111111',now='2026-11-06T02:00:00Z';
 const content={title:'合成需求',categoryCode:'TEST_ALLOWED',audienceStageCode:'PRIMARY',areaCode:'PS_PINGSHAN',publisherDeclaration:{kind:'GUARDIAN_OF_MINOR',statementVersion:'publisher-v1'},goal:'合成说明',budgetMin:'1234.56',budgetMax:'1234.56',durationHours:'1.5',frequency:{kind:'NEGOTIABLE'},availableSlots:[{weekday:6,start:'14:00',end:'17:00'}]};
 const {publisherDeclaration,...publicContent}=content;
 const owner={id,version:3,status:'PENDING_REVIEW',revisionNumber:1,content,updatedAt:now,feedback:[]};
 const publicRequest={id,version:2,status:'OPEN',content:publicContent,publishedAt:now,updatedAt:now};
 const opBase={id,acceptedAt:now,completedAt:now,expiresAt:'2026-11-09T02:00:00Z',originalHttpStatus:200};
 const outcome={submissionId:id,requestId:id,submittedVersion:2,requestVersionAfter:4,statusAfter:'OPEN',resolution:'APPROVED',resolvedAt:now,reviewDecisionId:id};
 const cases=[
  ['Money','1234.56',true],['Money','999999999999.99',true],['Money','1000000000000',false],['Money','12.345',false],['Money','1e3',false],['Money','-1',false],
  ['DurationHours','0.5',true],['DurationHours','8',true],['DurationHours','8.5',false],['DurationHours','1.25',false],
  ['Frequency',{kind:'WEEKLY',times:14},true],['Frequency',{kind:'WEEKLY',times:15},false],['Frequency',{kind:'NEGOTIABLE',times:1},false],
  ['Slot',{weekday:7,start:'23:00',end:'24:00'},true],['Slot',{weekday:7,start:'24:00',end:'24:00'},false],
  ['DraftContent',{},true],['DraftContent',{title:null},false],['DraftContent',{ownerId:id},false],['SubmissionContent',content,true],
 ['PublishingRule',{categoryCode:'TEST_ALLOWED',audienceStageCode:'ADULT',serviceMode:'UNSPECIFIED',allowSubmit:true},true],
 ['PublishingRule',{categoryCode:'TEST_ALLOWED',audienceStageCode:'ADULT',serviceMode:null,allowSubmit:true},false],
  ['SubmissionContent',{...content,availableSlots:[]},false],['SubmissionContent',{...content,availableSlots:Array(22).fill(content.availableSlots[0])},false],
  ['SubmissionContent',{...content,title:'字'.repeat(61)},false],['SubmissionContent',{...content,title:'😀'.repeat(60)},true],
  ['OwnerRequest',owner,true],['OwnerRequest',{...owner,expiresAt:now},false],
  ['PublicRequest',publicRequest,true],['PublicRequest',{...publicRequest,ownerId:id},false],['PublicRequest',{...publicRequest,content},false],
  ['ReviewDecision',{expectedVersion:3,decision:'REJECT'},false],['ReviewOutcome',outcome,true],['ReviewOutcome',{...outcome,statusAfter:'REJECTED'},false],
  ['Operation',{...opBase,kind:'REVIEW_DECIDED',status:'SUCCEEDED',result:outcome},true],['Operation',{...opBase,kind:'REVIEW_DECIDED',status:'SUCCEEDED',result:owner},false],
  ['Operation',{...opBase,kind:'REQUEST_SUBMITTED',status:'SUCCEEDED',result:owner},true],
  ['Operation',{id,kind:'REVIEW_DECIDED',acceptedAt:now,status:'PROCESSING'},true],['Operation',{id,kind:'REVIEW_DECIDED',acceptedAt:now,status:'PROCESSING',result:outcome},false],
  ['Operation',{...opBase,originalHttpStatus:410,kind:'REVIEW_DECIDED',status:'FAILED',error:{code:'REQUEST_EXPIRED',message:'到期',traceId:'synthetic'}},true],
  ['Operation',{...opBase,originalHttpStatus:200,kind:'REVIEW_DECIDED',status:'FAILED',error:{code:'REQUEST_EXPIRED',message:'到期',traceId:'synthetic'}},false],
  ['Operation',{...opBase,originalHttpStatus:410,kind:'REQUEST_SAVED',status:'SUCCEEDED',result:owner},false],
  ['Operation',{...opBase,originalHttpStatus:409,kind:'IDENTITY_CORRECTION_APPLIED',status:'FAILED',error:{code:'STATE_CONFLICT',message:'冲突',traceId:'synthetic'},reviewOutcome:outcome},false],
 ];
 if(name==='account-access')cases.push(['Register',{accountName:'demo_a',email:'a@example.invalid',password:' '.repeat(15),adultDeclaration:true,adultDeclarationVersion:'adult-18-v1'},true],['Register',{accountName:'demo_a',email:'a@example.invalid',password:'a'.repeat(14),adultDeclaration:true,adultDeclarationVersion:'adult-18-v1'},false],['Register',{accountName:'demo_a',email:'a@example.invalid',password:'a'.repeat(15),adultDeclaration:false,adultDeclarationVersion:'adult-18-v1'},false],['IdentityBinding',{businessIdentity:'OPS',expectedIdentityVersion:0},false],['ProfileContent',{phone:'synthetic'},false],['OpsStepUp',{password:'synthetic-password',totpCode:'123456'},true],['OpsStepUp',{password:'synthetic-password',totpCode:'12345'},false]);
 for(const[schema,value,expected]of cases)assert.equal(matches(spec.components.schemas[schema],value),expected,`${name} ${schema} fixture ${JSON.stringify(value)}`);
 reports.push({name,paths:Object.keys(spec.paths).length,operations:operationIds.length,schemas:Object.keys(spec.components.schemas).length,localReferences:refs,schemaExamples:cases.length});
}
const [request,account]=specs.map(x=>x.spec);
assert.deepEqual(request.paths['/requests'].get.security,[]);assert.deepEqual(request.paths['/requests/{id}'].get.security,[]);
assert(request.paths['/ops/request-reviews/{id}'].get.summary.includes('处理后'));
assert(request.paths['/me/requests/{id}'].put.responses['412']);assert(request.paths['/me/requests/{id}'].put.responses['428']);
assert(request.paths['/operations/{id}'].get.responses['410']);
assert(request.components.schemas.PublishingOptions.required.includes('rules'));
for(const[k,v]of Object.entries(request.components.schemas))assert.deepEqual(v,account.components.schemas[k],'Shared schema drift '+k);
assert.deepEqual(request.paths['/operations/{id}'],account.paths['/operations/{id}']);
console.log(JSON.stringify({status:'passed',reports,checks:'local refs, exact path parameters, unique operations, CSRF/cache/operation headers, recursive public whitelist, shared schema parity, schema-subset fixtures',limitations:'Not full OAS/JSON Schema validation; format semantics, cross-field/domain rules, authorization, DB transactions, real mail and browser behavior not executed.'},null,2));
