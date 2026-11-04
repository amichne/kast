import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { pathToFileURL,fileURLToPath } from 'node:url';
import { expandReplayPlan,ReplayBudget } from './pi_evaluation_replay.mjs';
import { replayCase,replayTransition } from './pi_evaluation_replay_cases.mjs';
import { gradeReplayDecision } from './pi_evaluation_replay_grading.mjs';
import { loadReplayCredential } from './pi_evaluation_replay_auth.mjs';
import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
import { createEvaluationSession,disposeEvaluationSession,publishWorkerResult } from './pi_evaluation_worker.mjs';
import { withPrivateReplaySession } from './pi_evaluation_records.mjs';
import { startReplayAdapter } from './pi_evaluation_replay_adapter_session.mjs';
import { createReplayShell } from './pi_evaluation_replay_shell.mjs';

export const replaySha=value=>crypto.createHash('sha256').update(value).digest('hex');
export function adapterCommand(manifest,mode,input,format) {
 const result=spawnSync(manifest.command[0],[...manifest.command.slice(1),...mode],{
  input,encoding:'utf8',maxBuffer:1048576,timeout:15000,
  env:{PATH:path.dirname(manifest.command[0]),HOME:os.tmpdir(),KAST_OUTPUT_TOON:format==='TOON'?'1':'0'}});
 if(result.error||result.status!==0)throw Error('PRODUCTION_ADAPTER_FAILED');
 return result.stdout;
}
export function readPreparedReplayInput(directory,expected) {
 const rejected=reason=>({type:'unavailable',reason});
 try{
  const input=path.join(directory,'input.json'),proof=path.join(directory,'projection.json');
  if([input,proof].some(file=>{const stat=fs.lstatSync(file);return !stat.isFile()||stat.isSymbolicLink()||stat.size>4194304;}))return rejected('PREFLIGHT_RENDERING_UNAVAILABLE');
  const data=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(fs.readFileSync(input)));
  const metadata=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(fs.readFileSync(proof)));
  if(typeof data.presentation!=='string'||metadata.type!=='projected'||metadata.complete!==true||metadata.encodingEquivalent!==true
   ||['canonicalSha256','artifactSha256','format'].some(key=>metadata[key]!==expected[key])
   ||replaySha(data.presentation)!==metadata.presentationSha256)return rejected('PREFLIGHT_RENDERING_REJECTED');
  return Object.freeze({type:'prepared',presentation:data.presentation});
 }catch{return rejected('PREFLIGHT_RENDERING_UNAVAILABLE');}
}
export function replayAdapterOutcome(failure) {
 if(failure?.trigger==='MODEL_ADMISSION'&&failure.failure?.operation==='ADMIT'&&failure.failure?.reason==='ADAPTER_REJECTED'&&failure.failure?.cause==='RESPONSE_TOO_LARGE')return {type:'incomplete',reasons:['ADAPTER_OUTPUT_BUDGET']};
 return {type:'invalid',reasons:['ADAPTER_SESSION_UNAVAILABLE']};
}
export function replayShellFailureOutcome(failure) {
 switch(failure?.reason){
  case 'STDIN_INVALID_UTF8':case 'DRIVER_REQUEST_INVALID_UTF8':case 'DRIVER_REQUEST_REJECTED':case 'SHELL_STDOUT_INVALID_UTF8':case 'SHELL_STDERR_INVALID_UTF8':return {type:'incorrect',reasons:[failure.reason]};
  case 'STDIN_BUDGET':case 'DRIVER_REQUEST_TOO_LARGE':case 'SHELL_OUTPUT_BUDGET':return {type:'incomplete',reasons:[failure.reason]};
  default:return {type:'invalid',reasons:['SHELL_ADAPTER_FAILURE']};
 }
}
export function replayMessages(item,presentation) {
 const id=item.prefixCall.toolCallId;
 const usage={input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}};
 return [{role:'user',content:item.task+'\n\n'+item.visibleContract,timestamp:1},
  {role:'assistant',api:'openai-codex-responses',provider:'openai-codex',model:'gpt-6.1-sol',
   content:[{type:'toolCall',id,name:'bash',arguments:item.prefixCall.input}],usage,stopReason:'toolUse',timestamp:2},
  {role:'toolResult',toolCallId:id,toolName:'bash',content:[{type:'text',text:presentation}],isError:item.exitStatus!==0,timestamp:3}];
}
export function inspectReplayProjection(payload,presentation,maximumBytes=262144) {
 const inspected=inspectProviderPayload(payload,maximumBytes);
 const results=(payload.input??[]).filter(item=>item.type==='function_call_output');
 const occurrences=results.filter(item=>item.output===presentation||Array.isArray(item.output)&&item.output.length===1&&item.output[0].text===presentation).length;
 return {type:inspected.type==='admitted-payload'?'projected':'invalid',complete:occurrences===1,
  presentationOccurrences:occurrences,payloadBytes:Buffer.byteLength(JSON.stringify(payload)),payloadSha256:inspected.payloadSha256??null,
  presentationSha256:replaySha(presentation),failure:inspected.reason??null};
}
export async function syntheticRuntime(sdk,load) {
 const {AuthStorage}=await load('dist/core/auth-storage.js');
 const {InMemoryModelsStore}=await load('../pi-ai/dist/models-store.js');
 const modelsStore=new InMemoryModelsStore();
 await modelsStore.write('openai-codex',{models:[{id:'gpt-6.1-sol',name:'GPT-6.1 Sol',provider:'openai-codex',api:'openai-codex-responses',baseUrl:'https://example.invalid',type:'chat',reasoning:true,input:['text'],contextWindow:1050000,maxTokens:128000,cost:{input:0,output:0,cacheRead:0,cacheWrite:0},thinkingLevelMap:{high:'high'}}]});
 const access=`synthetic.${Buffer.from(JSON.stringify({'https://api.openai.com/auth':{chatgpt_account_id:'synthetic-account'}})).toString('base64url')}.unsigned`;
 return sdk.ModelRuntime.create({credentials:AuthStorage.inMemory({'openai-codex':{type:'oauth',access,refresh:'synthetic-never-refresh',expires:Date.now()+86400000}}),modelsStore,modelsPath:null,allowModelNetwork:false,refreshOnCreate:false});
}
export async function replayWorker(mode,plan,trial,services={}) {
 const started=performance.now(),preflight=mode==='--preflight',item=replayCase(trial.caseId,plan.instructionProfile??'full'),budget=new ReplayBudget(()=>ledger());
 const directory=path.join(plan.outputRoot,preflight?`preflight-${trial.trialId}`:trial.trialId);
 fs.mkdirSync(directory,{mode:0o700});
 const write=(name,value)=>fs.writeFileSync(path.join(directory,name),JSON.stringify(value,null,2)+'\n',{mode:0o600});
 let responseLimit=false;
 let usage=null,projection=null,providerStarted=null,providerMs=null,assistant=null,proposal=null,session,shell,transportFailure=false,deadline=false,setupComplete=false,setupMs=null,adapterSession=null,adapterFailure=null,adapterClose=null;
 const measurement=preflight?'NO_INFERENCE':services.modelRuntime||services.fetch?'SYNTHETIC_TRANSPORT':'LIVE_MODEL';
 const events=[],record=value=>{events.push({...value,atMs:performance.now()-started});return {type:'recorded'};};
 const ledger=()=>write('ledger.json',{...budget.report(),measurement,usage,nativeCalls:0});ledger();
 const manifestBytes=fs.readFileSync(plan.adapterManifest);
 if(replaySha(manifestBytes)!==plan.adapterManifestSha256)throw Error('ADAPTER_IDENTITY_CHANGED');
 const manifest=JSON.parse(manifestBytes);
 let encodingEquivalent=true,presentation;
 if(!preflight&&plan.executionProfile==='session'){
  const reused=readPreparedReplayInput(path.join(plan.outputRoot,`preflight-${trial.trialId}`),{canonicalSha256:item.canonicalSha256,artifactSha256:manifest.sha256,format:trial.format});
  if(reused.type!=='prepared')throw Error(reused.reason);
  presentation=reused.presentation;
 }else{
  const stdout=adapterCommand(manifest,['render-raw',item.canonicalPath],undefined,trial.format);
  try{adapterCommand(manifest,['render',item.canonicalPath],undefined,trial.format);}catch{encodingEquivalent=false;}
  presentation=`stdout:\n${stdout}\nstderr:\n${item.stderr}exit_status: ${item.exitStatus}\n`;
 }
 write('encoding.json',{encodingEquivalent,canonicalSha256:item.canonicalSha256,format:trial.format});
 write('input.json',{task:item.task,presentation,messages:replayMessages(item,presentation)});
 const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
 if(JSON.parse(fs.readFileSync(path.join(plan.piPackageRoot,'package.json'))).version!==plan.piVersion)throw Error('SDK_IDENTITY_CHANGED');
 const sdk=await load('dist/index.js');
 const protectedPaths=preflight||services.modelRuntime?[]:[plan.authPath,plan.modelsStorePath];
 const before=protectedPaths.map(file=>replaySha(fs.readFileSync(file)));
 let runtime,authIdentity;
 if(services.modelRuntime)runtime=services.modelRuntime;
 else if(preflight)runtime=await syntheticRuntime(sdk,load);
 else{
  const auth=await load('dist/core/auth-storage.js');
  const credential=loadReplayCredential(auth,plan,Date.now());
  if(credential.type!=='ready'){
   const report={...trial,measurement,outcome:{type:'invalid',reasons:[credential.reason]},...budget.report(),usage:null,nativeCalls:0};write('report.json',report);return report;
  }
  authIdentity={credentialSource:credential.source,principalSha256:credential.principalSha256};
  runtime=await sdk.ModelRuntime.create({credentials:credential.storage,modelsPath:null,modelsStorePath:plan.modelsStorePath,allowModelNetwork:false,refreshOnCreate:false});
 }
 const savedFetch=globalThis.fetch,originalFetch=services.fetch??savedFetch;
 let expectedPayload=null,actualPayload=null,providerHttpStatus=null;
 globalThis.fetch=async(url,options)=>{
  if(preflight)throw Error('PREFLIGHT_NETWORK_FORBIDDEN');
  const encoded=new Headers(options?.headers).get('content-encoding')==='zstd'?zlib.zstdDecompressSync(options.body).toString('utf8'):options.body;
  actualPayload=JSON.parse(encoded);const actual=inspectReplayProjection(actualPayload,presentation);
  record({type:'actual_provider_send',projection:actual});write('provider-payload.json',actualPayload);
  if(actual.type!=='projected'||actual.payloadSha256!==expectedPayload?.payloadSha256){transportFailure=true;throw Error('PROVIDER_PAYLOAD_CHANGED');}
  if(!budget.provider()){ledger();throw Error('PROVIDER_REQUEST_LIMIT');}
  providerStarted=performance.now();ledger();
  try{const response=await originalFetch(url,options);providerHttpStatus=response.status;record({type:'provider_http_response',status:providerHttpStatus});if(!response.ok)transportFailure=true;return response;}catch(error){transportFailure=true;throw error;}
 };
 const abort=new AbortController(),cancel=()=>{deadline=true;abort.abort();session?.agent.abort();};
 const onMessage=message=>{if(message?.type==='cancel')cancel();};process.on('message',onMessage);process.once('SIGTERM',cancel);
 let adapterPrepared;
 try{
  adapterPrepared=!preflight&&plan.executionProfile==='session'?startReplayAdapter(manifest,{signal:abort.signal,format:trial.format}):null;
  const socket=path.join(fs.realpathSync(os.tmpdir()),`kr-${process.pid}.sock`);
  shell=await createReplayShell({directory:path.join(directory,'fixture'),socket,budget,
   admit:async request=>{if(!adapterSession)return JSON.parse(adapterCommand(manifest,['admit'],JSON.stringify(request),trial.format));const result=await adapterSession.admit(request);if(result.type!=='admission'){adapterFailure={...result,trigger:'MODEL_ADMISSION'};throw Error('REPLAY_ADAPTER_SESSION_FAILED');}return result.receipt;},
   transition:async admission=>{const transition=replayTransition(item.id,admission);if(transition.type!=='supported')return transition;const format=admission.argv?.includes('--json')?'JSON':trial.format;let text;if(adapterSession){const result=await adapterSession.render(transition.canonicalResponse,format);if(result.type!=='rendered'){adapterFailure=result;throw Error('REPLAY_ADAPTER_SESSION_FAILED');}text=result.text;}else text=adapterCommand(manifest,['render-raw',transition.canonicalPath],undefined,format);return {stdout:text+'\n',stderr:transition.stderr,exitStatus:transition.exitStatus};}});
  const shellTool=sdk.createBashToolDefinition(shellDirectory(directory),{operations:shell.operations,exposeSessionEnvironment:false});
  const guard=pi=>{
   pi.on('session_start',()=>pi.setActiveTools(['bash']));
   pi.on('before_provider_request',(event,ctx)=>{
    const payload={...event.payload,max_output_tokens:2000};
    if(budget.report().providerSends!==0){record({type:'provider_followup_blocked',reason:'MICROTRIAL_COMPLETE'});ctx.abort();return payload;}
    projection={...inspectReplayProjection(payload,presentation),encodingEquivalent,canonicalSha256:item.canonicalSha256,artifactSha256:manifest.sha256,format:trial.format};expectedPayload=projection;
    record({type:'provider_admission',projection});
    if(preflight||!encodingEquivalent||projection.type!=='projected'||budget.report().providerSends!==0){ctx.abort();}
    else{
     const earlier=JSON.parse(fs.readFileSync(path.join(plan.outputRoot,`preflight-${trial.trialId}`,'projection.json')));
     if(earlier.payloadSha256!==projection.payloadSha256){projection={...projection,type:'invalid',failure:'PREFLIGHT_PROJECTION_CHANGED'};ctx.abort();}
    }
    return payload;
   });
   pi.on('tool_call',(event,ctx)=>{
    if(!budget.proposal()){record({type:'excess_tool_proposal'});return {block:true,reason:'SHELL_CALL_LIMIT'};}
    proposal={toolName:event.toolName,input:event.input};record({type:'model_tool_proposal',...proposal});
    if(event.toolName!=='bash'){ctx.abort();return {block:true,reason:'WRONG_TOOL'};}
   });
   pi.on('message_update',(event,ctx)=>{if(event.message?.role==='assistant'&&Buffer.byteLength(JSON.stringify(event.message))>65536){responseLimit=true;ctx.abort();}});
   pi.on('message_end',event=>{if(event.message.role==='assistant'){if(assistant===null){assistant=event.message;if(Buffer.byteLength(JSON.stringify(assistant))<=65536)write('assistant.json',assistant);else write('assistant-limit.json',{type:'RESPONSE_RECORD_LIMIT'});providerMs=providerStarted===null?null:performance.now()-providerStarted;}if(event.message.usage?.totalTokens>0)usage=event.message.usage;ledger();}});
  };
  const archive=await withPrivateReplaySession(sdk.SessionManager,{directory,workspaceRoot:shellDirectory(directory)},async manager=>{
   for(const message of replayMessages(item,presentation))manager.appendMessage(message);
   const created=await createEvaluationSession({sdk,plan:{workspaceRoot:shellDirectory(directory)},directory,modelRuntime:runtime,
    policy:{config:{finalPresentation:'canonical'}},record,manager,replay:{guard,shellTool}});
   if(created.type!=='session')throw Error(JSON.stringify(created));
   session=created.session;
   if(adapterPrepared){const ready=await adapterPrepared;if(ready.type==='ready')adapterSession=ready.session;else adapterFailure=ready;}
   setupComplete=!adapterFailure;setupMs=performance.now()-started;
   if(!adapterFailure){await session.agent.continue();await session.agent.waitForIdle();}
   await disposeEvaluationSession(session,'WORKER_EXECUTION');session=null;
   return true;
  });
  if(archive.type!=='completed')throw Error(JSON.stringify(archive));
  if(adapterSession){adapterClose=await adapterSession.close();adapterSession=null;if(adapterClose.type!=='closed')adapterFailure??=adapterClose;}
  write('events.json',events);write('shell.json',{captures:shell.captures,invocations:shell.invocations,rejections:shell.rejections??[],modelFailure:shell.modelFailure??null});
  if(preflight){write('projection.json',projection);return projection??{type:'invalid',failure:'PROJECTION_UNAVAILABLE'};}
  const inputValid=projection?.type==='projected'&&actualPayload!==null;
  let decision;
  if(proposal)decision={kind:'invoke'};
  else{try{decision=JSON.parse((assistant?.content??[]).filter(c=>c.type==='text').map(c=>c.text).join(''));}catch{decision={kind:'malformed'};}}
  const admission=shell.invocations[0]?.admission??{type:'unavailable'};
  const evaluated=gradeReplayDecision(item.id,decision,admission);
  const counts=budget.report();
  const excess=counts.kastInvocations>1||counts.blockedShellCalls>0||counts.blockedProviderSends>0||counts.blockedToolProposals>0;
  const grade={...evaluated,correct:evaluated.correct&&!excess&&!shell.modelFailure,misuse:[...evaluated.reasons,...(excess?['RESOURCE_MISUSE']:[]),...(shell.modelFailure?[shell.modelFailure.reason]:[])],argumentCause:evaluated.productionCause??null,coverageCorrect:evaluated.dimensions.coverage==='PASS'?true:evaluated.dimensions.coverage==='FAIL'?false:null};
  let outcome;
  if(deadline)outcome={type:'incomplete',reasons:['DEADLINE']};
  else if(adapterFailure)outcome=replayAdapterOutcome(adapterFailure);
  else if(shell.modelFailure)outcome=replayShellFailureOutcome(shell.modelFailure);
  else if(transportFailure||!inputValid||shell.failed)outcome={type:'invalid',reasons:[transportFailure?'PROVIDER_TRANSPORT_FAILURE':!inputValid?'INPUT_PROJECTION_FAILED':'SHELL_ADAPTER_FAILURE']};
  else if(responseLimit||assistant?.stopReason==='length')outcome={type:'incomplete',reasons:['OUTPUT_BUDGET']};
  else outcome={type:grade.correct?'correct':'incorrect',reasons:grade.misuse};
  const protectedFilesUnchanged=protectedPaths.every((file,index)=>replaySha(fs.readFileSync(file))===before[index]);
  if(!protectedFilesUnchanged)outcome={type:'invalid',reasons:['PROTECTED_FILE_CHANGED']};
  const report={...trial,measurement,outcome,usage,...budget.report(),nativeCalls:0,grade,decision,proposal,
   adapterSession:adapterClose,adapterFailure,shellModelFailure:shell.modelFailure??null,productionAdmission:admission,providerHttpStatus,presentationBytes:Buffer.byteLength(presentation),presentationSha256:replaySha(presentation),
   projection,actualProviderProjection:inspectReplayProjection(actualPayload??{},presentation),
   latency:{trialMs:performance.now()-started,queueMs:0,setupMs,providerMs,shellMs:shell.captures.reduce((total,capture)=>total+capture.durationMs,0),method:'WORKER_MONOTONIC_V1'},
   identity:{...authIdentity,instructionProfile:plan.instructionProfile??'full',executionProfile:plan.executionProfile??'fresh',publicContractSha256:plan.publicContractSha256,sourceRevision:plan.sourceRevision,diffSha256:plan.diffSha256,artifactSha256:manifest.sha256,adapterManifestSha256:plan.adapterManifestSha256,piVersion:plan.piVersion,provider:'openai-codex',model:'gpt-6.1-sol',effort:'high',timingMethod:'WORKER_MONOTONIC_V1',fixtureVersion:item.version,canonicalSha256:item.canonicalSha256,contractSha256:replaySha(item.visibleContract),promptSha256:replaySha(item.task),graderSha256:replaySha(fs.readFileSync(new URL('pi_evaluation_replay_grading.mjs',import.meta.url))),sdkIdentity:plan.sdkIdentity??[],budgetSha256:replaySha(JSON.stringify({wallSeconds:60,workers:plan.workers,providerRequests:1,shellCalls:1,invocations:1,maximumProviderPayloadBytes:262144,maximumProviderResponseBytes:65536,outputCap:2000,retries:0,compaction:false,cacheWarming:'off',transport:'sse'}))},
   protectedFilesUnchanged,
   settings:{maximumProviderPayloadBytes:262144,maximumProviderResponseBytes:65536,transport:'sse',cacheWarming:'off',retries:0,compaction:false,outputCap:2000,outputCapEnforcement:'REQUESTED_BACKEND_ENFORCEMENT_UNPROVEN'}};
  write('report.json',report);return report;
 }finally{
  globalThis.fetch=savedFetch;process.removeListener('message',onMessage);process.removeListener('SIGTERM',cancel);
  if(session)await disposeEvaluationSession(session);
  if(adapterSession)await adapterSession.close();else if(adapterPrepared){const ready=await adapterPrepared;if(ready.type==='ready'&&!adapterClose)await ready.session.close();}
  if(shell)await shell.dispose();
  record({type:'worker_end',setupComplete});
 }
}
const shellDirectory=directory=>path.join(directory,'fixture');
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 let result,plan,trial;try{plan=JSON.parse(fs.readFileSync(process.argv[3]));trial=expandReplayPlan(plan).find(t=>t.trialId===process.argv[4]);
  result={type:'report',report:await replayWorker(process.argv[2],plan,trial)};
 }catch(error){
  let ledger={usage:null,providerSends:null,shellCalls:null,kastInvocations:null,nativeCalls:0};
  try{ledger=JSON.parse(fs.readFileSync(path.join(plan.outputRoot,process.argv[2]==='--preflight'?`preflight-${trial.trialId}`:trial.trialId,'ledger.json')));}catch{}
  result={type:'report',report:{...trial,...ledger,outcome:{type:'invalid',reasons:[error.message==='CREDENTIAL_UNAVAILABLE'?'CREDENTIAL_UNAVAILABLE':'REPLAY_SETUP_FAILED']},diagnostic:String(error.message).slice(0,300)}};
 }
 await publishWorkerResult(result);
}
