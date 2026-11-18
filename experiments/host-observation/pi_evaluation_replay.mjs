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
 #provider=0;#shell=0;#invocations=0;#blockedProvider=0;#blockedShell=0;#proposals=0;#blockedProposals=0;#changed;
 constructor(changed=()=>{}){this.#changed=changed;}
 provider(){if(this.#provider){this.#blockedProvider++;this.#changed();return false;}this.#provider++;this.#changed();return true;}
 shell(){if(this.#shell){this.#blockedShell++;this.#changed();return false;}this.#shell++;this.#changed();return true;}
 invocation(){const allowed=++this.#invocations===1;this.#changed();return allowed;}
 proposal(){const allowed=++this.#proposals===1;if(!allowed)this.#blockedProposals++;this.#changed();return allowed;}
 report(){return {providerSends:this.#provider,shellCalls:this.#shell,kastInvocations:this.#invocations,
  blockedProviderSends:this.#blockedProvider,blockedShellCalls:this.#blockedShell,toolProposals:this.#proposals,blockedToolProposals:this.#blockedProposals};}
}
export function admitReplayPair(projections,maximumPayloadBytes) {
 if(projections.length!==2||projections.some(p=>p?.type!=='projected'||p.complete!==true
  ||p.presentationOccurrences!==1||!Number.isSafeInteger(p.payloadBytes)||p.payloadBytes>maximumPayloadBytes))
  return {type:'invalid',reason:'PAIR_INPUT_UNAVAILABLE'};
 if(projections.some(p=>p.encodingEquivalent!==true))return {type:'invalid',reason:'PAIR_ENCODING_MISMATCH'};
 return {type:'admitted'};
}
export async function runReplayPool(trials,workers,execute,signal) {
 let cursor=0;const results=new Array(trials.length);
 await Promise.all(Array.from({length:Math.min(workers,trials.length)},async()=>{
  while(cursor<trials.length&&!signal?.aborted){
   const index=cursor++,trial=trials[index];
   try{results[index]={...trial,...await execute(trial,index)};}
   catch{results[index]={...trial,outcome:{type:'invalid',reasons:['WORKER_EXECUTION_FAILED']},usage:null,providerSends:null};}
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
// Promptfoo schedules live trials. This owner admits the paired inputs and
// records controller evidence for each admitted trial.
export async function prepareReplayRun(planFile,planBytes,plan,signal,{checkPins=examineReplayPins,spawnWorker=runOwnedWorker}={}) {
 checkPins(plan,{planFile,requireGuidance:true});
 const trials=expandReplayPlan(plan);
 const planned=new Map(trials.map(trial=>[trial.trialId,trial]));
 const artifact=JSON.parse(fs.readFileSync(plan.adapterManifest));
 fs.mkdirSync(plan.outputRoot,{mode:0o700});
 const snapshot=path.join(plan.outputRoot,'plan.json');
 fs.writeFileSync(snapshot,planBytes,{mode:0o400,flag:'wx'});
 const worker=fileURLToPath(new URL('pi_evaluation_replay_worker.mjs',import.meta.url));
 const call=(trial,mode,workerSignal)=>spawnWorker(process.execPath,[worker,mode,snapshot,trial.trialId],{
  cwd:plan.outputRoot,env:{...process.env,PI_OFFLINE:'1',PI_TELEMETRY:'0',KAST_TOOL_RPC_COMMAND:undefined},
  wallMillis:60000,graceMillis:1000,signal:workerSignal});
 const graderSha256=createHash('sha256').update(fs.readFileSync(new URL('pi_evaluation_replay_grading.mjs',import.meta.url))).digest('hex');
 const planSha256=createHash('sha256').update(planBytes).digest('hex');
 const withIdentity=(trial,observation)=>{
  const fixture=plan.cases.find(item=>item.id===trial.caseId);
  return {...observation,...trial,identity:{sourceRevision:plan.sourceRevision,diffSha256:plan.diffSha256,
   artifactSha256:artifact.sha256,adapterManifestSha256:plan.adapterManifestSha256,piVersion:plan.piVersion,
   sdkIdentity:plan.sdkIdentity,model:plan.model,fixtureVersion:fixture.version,replayStateSha256:fixture.replayStateSha256,
   canonicalSha256:fixture.canonicalSha256,promptSha256:fixture.promptSha256,contractSha256:fixture.contractSha256,
   codec:'JToon-3.0.0',instructionProfile:plan.instructionProfile??'full',executionProfile:plan.executionProfile??'fresh',
   publicContractSha256:plan.publicContractSha256,credentialSource:plan.credentialSource??'PI_OAUTH',
   kastGuidanceSha256:plan.kastGuidanceSha256,
   graderSha256,planSha256,...observation.identity,timingMethod:'CONTROLLER_REAPED_WALL_V1'}};
 };
 const write=(name,value,flag='w')=>fs.writeFileSync(path.join(plan.outputRoot,name),JSON.stringify(value,null,2)+'\n',{mode:0o600,flag});

 // All arms project through the SDK without credentials or network first.
 const preflight=await runReplayPool(trials,plan.workers,async trial=>{
  const result=await call(trial,'--preflight',signal);
  return {projection:result.type==='report'?result.report:null,controller:result};
 },signal);
 write('preflight.json',preflight);
 const projections=new Map(preflight.map(row=>[row.trialId,row.projection]));
 const pairs=new Map();
 for(const trial of trials) {
  const pair=pairs.get(trial.pairId)??[];
  pair.push(projections.get(trial.trialId));
  pairs.set(trial.pairId,pair);
 }
 const admissions=new Map([...pairs].map(([id,pair])=>[id,admitReplayPair(pair,262144)]));
 const receipts=new Map(),claimed=new Set();
 const publishSummary=()=>write('report.json',{
  ...summarizeReplay(trials,[...receipts.values()]),
  liveAcceptance:[...receipts.values()].some(row=>row.providerSends>0)?'MEASURED':'NOT_RUN',
  preflight:{planned:trials.length,completeInputs:preflight.filter(row=>row.projection?.complete===true).length,
   qualifiedPairs:[...admissions.values()].filter(admission=>admission.type==='admitted').length},
  source:{revision:plan.sourceRevision,diffSha256:plan.diffSha256,adapterManifestSha256:plan.adapterManifestSha256},
  limits:{workers:plan.workers,maximumProviderRequests:plan.maximumProviderRequests,maximumRequestsPerTrial:1,wallSeconds:60},
 });
 const publish=(trial,observation)=>{
  const receipt=withIdentity(trial,observation);
  write(`${trial.trialId}.receipt.json`,receipt,'wx');
  receipts.set(trial.trialId,receipt);
  return receipt;
 };
 const cancelled={outcome:{type:'cancelled-before-start',reasons:['BATCH_CANCELLED']},usage:null,providerSends:0};
 const publishCancelled=()=>{
  if(signal.aborted)for(const trial of trials)if(!claimed.has(trial.trialId)&&!receipts.has(trial.trialId))publish(trial,cancelled);
 };
 publishCancelled();
 publishSummary();
 const started=performance.now();
 return {
  async run(trialId,workerSignal) {
   const trial=planned.get(trialId);
   if(!trial||claimed.has(trialId))throw Error('REPLAY_TRIAL_REJECTED');
   if(receipts.has(trialId))return receipts.get(trialId);
   claimed.add(trialId);
   let observation;
   const admission=admissions.get(trial.pairId);
   if(workerSignal.aborted)observation=cancelled;
   else if(admission.type!=='admitted')observation={outcome:{type:'invalid',reasons:[admission.reason]},usage:null,
    providerSends:0,shellCalls:0,kastInvocations:0,nativeCalls:0,projection:projections.get(trialId)};
   else {
    const queueMs=performance.now()-started,launched=performance.now();
    const result=await call(trial,'--trial',workerSignal);
    const controllerMs=performance.now()-launched;
    if(result.type==='report')observation={...result.report,latency:{...result.report.latency,
     workerMs:result.report.latency?.trialMs??null,trialMs:controllerMs,queueMs,method:'CONTROLLER_REAPED_WALL_V1'},controller:result};
    else {
     // A killed worker has unknown incurred usage. Keep its incremental ledger.
     let ledger={providerSends:null,usage:null};
     try{ledger=JSON.parse(fs.readFileSync(path.join(plan.outputRoot,trialId,'ledger.json'),'utf8'));}catch{}
     observation={...ledger,latency:{trialMs:controllerMs,workerMs:null,queueMs,providerMs:null,shellMs:null,method:'CONTROLLER_REAPED_WALL_V1'},
      outcome:{type:result.type==='cancelled'&&ledger.providerSends===1?'incomplete':'invalid',reasons:[result.type==='cancelled'?'DEADLINE':'WORKER_REPORT_UNAVAILABLE']},controller:result};
    }
   }
   const receipt=publish(trial,observation);
   publishCancelled();
   publishSummary();
   return receipt;
  },
 };
}
