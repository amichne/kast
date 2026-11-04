import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { runOwnedWorker } from './pi_evaluation_controller.mjs';
import { examineReplayPins } from './pi_evaluation_replay_identity.mjs';
import { summarizeReplay } from './pi_evaluation_replay_accounting.mjs';

export function expandReplayPlan(plan) {
 if(plan.mode!=='replay'||!Number.isInteger(plan.workers)||plan.workers<1||plan.workers>8
  ||plan.repetitions!==2||plan.wallSeconds!==60||!Array.isArray(plan.caseIds)||!plan.caseIds.length
  ||new Set(plan.caseIds).size!==plan.caseIds.length||plan.caseIds.some(id=>!/^C[1-6]$/.test(id))
  ||plan.maximumProviderRequests!==plan.caseIds.length*4)throw Error('REPLAY_PLAN_REJECTED');
 return plan.caseIds.flatMap((caseId,index)=>[1,2].flatMap(repetition=>{
  const pairId=`${caseId}-r${repetition}`;
  return ((index+repetition)%2?['TOON','JSON']:['JSON','TOON']).map((format,armOrder)=>
   Object.freeze({trialId:`${pairId}-${format}`,pairId,caseId,format,repetition,armOrder}));
 }));
}
export class ReplayBudget {
 #provider=0;#shell=0;#invocations=0;#blockedProvider=0;#blockedShell=0;#changed;
 constructor(changed=()=>{}){this.#changed=changed;}
 provider(){if(this.#provider){this.#blockedProvider++;this.#changed();return false;}this.#provider++;this.#changed();return true;}
 shell(){if(this.#shell){this.#blockedShell++;this.#changed();return false;}this.#shell++;this.#changed();return true;}
 invocation(){const allowed=++this.#invocations===1;this.#changed();return allowed;}
 report(){return {providerSends:this.#provider,shellCalls:this.#shell,kastInvocations:this.#invocations,
  blockedProviderSends:this.#blockedProvider,blockedShellCalls:this.#blockedShell};}
}
export function admitReplayPair(projections,maximumPayloadBytes) {
 if(projections.length!==2||projections.some(p=>p?.type!=='projected'||p.complete!==true
  ||p.presentationOccurrences!==1||!Number.isSafeInteger(p.payloadBytes)||p.payloadBytes>maximumPayloadBytes))
  return {type:'invalid',reason:'PAIR_INPUT_UNAVAILABLE'};
 if(projections.some(p=>p.encodingEquivalent!==true))return {type:'invalid',reason:'PAIR_ENCODING_MISMATCH'};
 return {type:'admitted'};
}
export async function runReplayPool(trials,workers,execute,signal,onReceipt=()=>{}) {
 let cursor=0;const results=new Array(trials.length);
 await Promise.all(Array.from({length:Math.min(workers,trials.length)},async()=>{
  while(cursor<trials.length&&!signal?.aborted){
   const index=cursor++,trial=trials[index];
   try{results[index]={...trial,...await execute(trial,index)};}
   catch{results[index]={...trial,outcome:{type:'invalid',reasons:['WORKER_EXECUTION_FAILED']},usage:null,providerSends:null};}
   onReceipt(results[index]);
  }
 }));
 return trials.map((trial,index)=>results[index]??{...trial,outcome:{type:'cancelled-before-start',reasons:['BATCH_CANCELLED']},usage:null,providerSends:0});
}
export function validateReplayPlan(plan) {
 expandReplayPlan(plan);
 if(plan.instructionProfile!==undefined&&!['full','concise'].includes(plan.instructionProfile)||plan.executionProfile!==undefined&&!['fresh','session'].includes(plan.executionProfile))throw Error('REPLAY_PROFILE_REJECTED');
 if(plan.credentialSource!==undefined&&!['PI_OAUTH','CODEX_CHATGPT'].includes(plan.credentialSource))throw Error('CREDENTIAL_SOURCE_REJECTED');
 for(const name of ['outputRoot','piPackageRoot','authPath','modelsStorePath','adapterManifest'])
  if(typeof plan[name]!=='string'||!path.isAbsolute(plan[name]))throw Error(`ABSOLUTE_${name}_REQUIRED`);
 if(!/^[a-f0-9]{64}$/.test(plan.adapterManifestSha256??'')||typeof plan.piVersion!=='string'
  ||!/^[a-f0-9]{40}$/.test(plan.sourceRevision??'')||!/^[a-f0-9]{64}$/.test(plan.diffSha256??'')
  ||!Array.isArray(plan.sdkIdentity)||!plan.sdkIdentity.length||!Array.isArray(plan.cases)||plan.cases.length!==6
  ||plan.model?.provider!=='openai-codex'||plan.model?.id!=='gpt-6.1-sol'||plan.model?.effort!=='high')throw Error('REPLAY_ARTIFACT_PIN_REQUIRED');
 return plan;
}
export async function executeReplayPlan(planFile,plan) {
 examineReplayPins(plan,{planFile});
 const trials=expandReplayPlan(plan),controller=new AbortController();
 const cancel=()=>controller.abort();process.once('SIGINT',cancel);process.once('SIGTERM',cancel);
 fs.mkdirSync(plan.outputRoot,{mode:0o700});
 const worker=fileURLToPath(new URL('pi_evaluation_replay_worker.mjs',import.meta.url));
 const call=async(trial,mode)=>runOwnedWorker(process.execPath,[worker,mode,planFile,trial.trialId],{
  cwd:plan.outputRoot,env:{...process.env,PI_OFFLINE:'1',PI_TELEMETRY:'0',KAST_TOOL_RPC_COMMAND:undefined},
  wallMillis:60000,graceMillis:1000,signal:controller.signal});
 const artifact=JSON.parse(fs.readFileSync(plan.adapterManifest));
 const withIdentity=receipt=>{const fixture=plan.cases.find(c=>c.id===receipt.caseId);return {...receipt,identity:{sourceRevision:plan.sourceRevision,diffSha256:plan.diffSha256,artifactSha256:artifact.sha256,adapterManifestSha256:plan.adapterManifestSha256,piVersion:plan.piVersion,sdkIdentity:plan.sdkIdentity,model:plan.model,fixtureVersion:fixture.version,canonicalSha256:fixture.canonicalSha256,promptSha256:fixture.promptSha256,contractSha256:fixture.contractSha256,codec:'JToon-3.0.0',instructionProfile:plan.instructionProfile??'full',executionProfile:plan.executionProfile??'fresh',publicContractSha256:plan.publicContractSha256,credentialSource:plan.credentialSource??'PI_OAUTH',graderSha256:createHash('sha256').update(fs.readFileSync(new URL('pi_evaluation_replay_grading.mjs',import.meta.url))).digest('hex'),planSha256:createHash('sha256').update(fs.readFileSync(planFile)).digest('hex'),...receipt.identity,timingMethod:'CONTROLLER_REAPED_WALL_V1'}};};
 const published=new Set();
 const publishReceipt=receipt=>{if(published.has(receipt.trialId))return;fs.writeFileSync(path.join(plan.outputRoot,`${receipt.trialId}.receipt.json`),JSON.stringify(receipt,null,2)+'\n',{mode:0o600,flag:'wx'});published.add(receipt.trialId);};
 const write=(name,value)=>fs.writeFileSync(path.join(plan.outputRoot,name),JSON.stringify(value,null,2)+'\n',{mode:0o600});
 try{
  // All arms project through the real SDK without credentials or network first.
  const preflight=await runReplayPool(trials,plan.workers,async trial=>{
   const result=await call(trial,'--preflight');
   return {projection:result.type==='report'?result.report:null,controller:result};
  },controller.signal);
  write('preflight.json',preflight);
  const pairs=new Map();for(const trial of trials){const items=pairs.get(trial.pairId)??[];items.push(trial);pairs.set(trial.pairId,items);}
  const allowed=new Set(),rejected=new Map();
  for(const [id,pair]of pairs){const admission=admitReplayPair(pair.map(t=>preflight.find(p=>p.trialId===t.trialId)?.projection),262144);if(admission.type==='admitted')allowed.add(id);else rejected.set(id,admission.reason);}
  let scheduledSends=0;
  const started=performance.now();
  const receipts=(await runReplayPool(trials,plan.workers,async trial=>{
   if(!allowed.has(trial.pairId))return {outcome:{type:'invalid',reasons:[rejected.get(trial.pairId)]},usage:null,providerSends:0,shellCalls:0,kastInvocations:0,nativeCalls:0,projection:preflight.find(p=>p.trialId===trial.trialId)?.projection};
   if(scheduledSends>=plan.maximumProviderRequests)throw Error('BATCH_REQUEST_CAP');
   scheduledSends++;
   const queued=performance.now()-started,launched=performance.now(),result=await call(trial,'--trial');
   const controllerMs=performance.now()-launched;
   if(result.type==='report')return {...result.report,latency:{...result.report.latency,workerMs:result.report.latency?.trialMs??null,trialMs:controllerMs,queueMs:queued,method:'CONTROLLER_REAPED_WALL_V1'},controller:result};
   // A killed worker has unknown incurred usage. Keep its incremental ledger.
   let ledger={providerSends:null,usage:null};
   try{ledger=JSON.parse(fs.readFileSync(path.join(plan.outputRoot,trial.trialId,'ledger.json'),'utf8'));}catch{}
   return {...ledger,latency:{trialMs:controllerMs,workerMs:null,queueMs:queued,providerMs:null,shellMs:null,method:'CONTROLLER_REAPED_WALL_V1'},outcome:{type:result.type==='cancelled'&&ledger.providerSends===1?'incomplete':'invalid',reasons:[result.type==='cancelled'?'DEADLINE':'WORKER_REPORT_UNAVAILABLE']},controller:result};
  },controller.signal,receipt=>publishReceipt(withIdentity(receipt)))).map(withIdentity);
  for(const receipt of receipts)publishReceipt(receipt);
  const report={...summarizeReplay(trials,receipts),liveAcceptance:receipts.some(r=>r.providerSends>0)?'MEASURED':'NOT_RUN',preflight:{planned:trials.length,completeInputs:preflight.filter(r=>r.projection?.complete===true).length,qualifiedPairs:allowed.size},source:{revision:plan.sourceRevision,diffSha256:plan.diffSha256,adapterManifestSha256:plan.adapterManifestSha256},limits:{workers:plan.workers,maximumProviderRequests:plan.maximumProviderRequests,maximumRequestsPerTrial:1,wallSeconds:60}};write('report.json',report);
  return report;
 }finally{process.removeListener('SIGINT',cancel);process.removeListener('SIGTERM',cancel);}
}
