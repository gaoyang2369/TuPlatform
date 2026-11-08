// Current document/preview integrity only. Does not execute Figma or business tests.
import {readFile,access,readdir} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
const root=resolve(import.meta.dirname,'../../..');
const source=resolve(root,'docs/design/source');
const scenes=JSON.parse(await readFile(resolve(source,'scenes.json'),'utf8'));
const navigation=JSON.parse(await readFile(resolve(source,'navigation.json'),'utf8'));
const ids=new Set(scenes.map(s=>s.id)),errors=[];
if(ids.size!==scenes.length)errors.push('Duplicate scene IDs');
const references=scenes.flatMap(s=>[s.to,...s.blocks.flatMap(b=>[b.to,b.optionTo])].filter(Boolean));
for(const ref of references)if(!ids.has(ref))errors.push('Missing scene: '+ref);
for(const [from,to]of Object.entries(navigation.backTargets))if(!ids.has(from)||!ids.has(to))errors.push('Missing back target: '+from);
for(const s of navigation.starts)if(s.key!=='index'&&!ids.has(s.key))errors.push('Missing start: '+s.key);
const byId=new Map(scenes.map(s=>[s.id,s]));
const core=scenes.filter(s=>s.phase!=='later');
for(const s of core){
 const targets=[s.to,navigation.backTargets[s.id],...s.blocks.flatMap(b=>[b.to,b.optionTo])].filter(Boolean);
 for(const target of targets)if(byId.get(target)?.phase==='later')errors.push('Core links to deferred scene: '+s.id+' -> '+target);
 if((s.role||'').startsWith('老师'))for(const target of targets)if(['G-02','P-08','P-contact','S-01','S-03','S-03-confirm','S-03-mine','S-03-both'].includes(target))errors.push('Teacher links to requester own action: '+s.id+' -> '+target);
}
for(const key of ['P-05','G-01-register']){
 const scene=byId.get(key),declaration=scene?.blocks.find(b=>b.control==='checkbox');
 if(!scene?.disabled||scene.to||!declaration||declaration.selected?.length)errors.push('Unchecked declaration permits submission: '+key);
 if(!declaration?.optionTo||!ids.has(declaration.optionTo))errors.push('Missing checked declaration sample: '+key);
}
for(const key of ['S-03-withdraw-confirm','S-03-teacher-withdraw-confirm'])if(!byId.get(key)?.blocks.some(b=>b.type==='button'&&b.back&&!b.to))errors.push('Cancel withdrawal must return to prior state: '+key);
const figmaState=JSON.parse(await readFile(resolve(root,'docs/design/figma-state.json'),'utf8'));
for(const scene of scenes)if(!figmaState.screens[scene.id]?.id)errors.push('Scene has no Figma node mapping: '+scene.id);
const html=await readFile(resolve(root,'docs/design/interaction-preview.html'),'utf8');
const safeJSON=value=>JSON.stringify(value).replaceAll('<','\\u003c');
if(html.includes('/*__SCENES__*/')||html.includes('/*__NAVIGATION__*/'))errors.push('Unbuilt template');
if(!html.includes('const scenes='+safeJSON(scenes)+';'))errors.push('Generated scenes are stale');
if(!html.includes('const navigation='+safeJSON(navigation)+';'))errors.push('Generated navigation is stale');
const inlineScript=html.match(/<script>([\s\S]*)<\/script>/)?.[1];
if(!inlineScript)errors.push('Missing preview script');else new Function(inlineScript);
async function markdownFiles(dir){
 const found=[];
 for(const entry of await readdir(dir,{withFileTypes:true})){
  const path=resolve(dir,entry.name);
  if(entry.isDirectory())found.push(...await markdownFiles(path));
  else if(entry.name.endsWith('.md'))found.push(path);
 }
 return found;
}
const markdown=[resolve(root,'README.md'),...await markdownFiles(resolve(root,'docs'))];
let localLinkCount=0;
for(const file of markdown){
 const content=await readFile(file,'utf8');
 for(const m of content.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g)){
  const href=m[1].split('#')[0].replace(/^<|>$/g,'');
  if(!href||/^[a-z]+:/i.test(href))continue;
  localLinkCount++;
  try{await access(resolve(dirname(file),decodeURIComponent(href)));}
  catch{errors.push('Broken local link: '+file+' -> '+href);}
 }
}
console.log(JSON.stringify({status:errors.length?'failed':'passed',scope:'Current Markdown links, core/deferred navigation, declaration guards, role targets, Figma node mapping and read-only preview integrity',scenes:scenes.length,uniqueSceneIds:ids.size,sceneNavigationReferences:references.length,backTargets:Object.keys(navigation.backTargets).length,starts:navigation.starts.length,markdownFiles:markdown.length,localLinkCount,errors,limits:'Static source checks only. No OpenAPI, real API, authorization, database, remote Figma or browser behavior executed.'},null,2));
if(errors.length)process.exitCode=1;
