import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';
import { replayWorker, syntheticRuntime, replaySha, inspectReplayProjection } from './pi_evaluation_replay_worker.mjs';
import { expandReplayPlan } from './pi_evaluation_replay.mjs';
import { replayOracleControls } from './pi_evaluation_replay_grading.mjs';
import { normalizeReplayUsage } from './pi_evaluation_replay_accounting.mjs';

const planPath=process.env.KAST_REPLAY_TEST_PLAN;
const syntheticReply=(text,incomplete=false)=>{
 const item={id:'synthetic-message',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text}]};
 const events=[{type:'response.created',response:{id:'synthetic-response'}},{type:'response.output_item.added',output_index:0,item:{...item,content:[]}},{type:'response.output_item.done',output_index:0,item},{type:incomplete?'response.incomplete':'response.completed',response:{id:'synthetic-response',status:incomplete?'incomplete':'completed',...(incomplete?{incomplete_details:{reason:'max_output_tokens'}}:{}),output:[item],usage:{input_tokens:100,output_tokens:10,total_tokens:110,input_tokens_details:{cached_tokens:20},output_tokens_details:{reasoning_tokens:4}}}}];
 return new Response(events.map(event=>`data: ${JSON.stringify(event)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
};
const syntheticToolReply=command=>{
 const items=(Array.isArray(command)?command:[command]).map((command,index)=>({id:`synthetic-call-item-${index}`,type:'function_call',call_id:`synthetic-repair-call-${index}`,name:'bash',arguments:JSON.stringify({command}),status:'completed'}));
 const events=[{type:'response.created',response:{id:'synthetic-tool-response'}},...items.flatMap((item,index)=>[{type:'response.output_item.added',output_index:index,item:{...item,arguments:''}},{type:'response.output_item.done',output_index:index,item}]),{type:'response.completed',response:{id:'synthetic-tool-response',status:'completed',output:items,usage:{input_tokens:100,output_tokens:10,total_tokens:110,input_tokens_details:{cached_tokens:20}}}}];
 return new Response(events.map(event=>`data: ${JSON.stringify(event)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
};
const decodeBodyText=options=>new Headers(options.headers).get('content-encoding')==='zstd'?zlib.zstdDecompressSync(options.body).toString('utf8'):options.body;
const decodeBody=options=>JSON.parse(decodeBodyText(options));

test('two SDK shell proposals remain unsuccessful after the first correct invocation',{skip:!planPath},async t=>{
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-excess-proposal-')));
 t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const source=JSON.parse(fs.readFileSync(planPath,'utf8'));
 const plan={...source,executionProfile:'session',outputRoot:root,adapterManifestSha256:replaySha(fs.readFileSync(source.adapterManifest)),authPath:'/never-read-live-auth',modelsStorePath:'/never-read-live-models'};
 const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
 const sdk=await load('dist/index.js'),runtime=await syntheticRuntime(sdk,load);
 const trial=expandReplayPlan(plan).find(item=>item.caseId==='C6'&&item.repetition===1&&item.format==='TOON');
 assert.equal((await replayWorker('--preflight',plan,trial,{modelRuntime:runtime})).type,'projected');
 let sends=0;
 const report=await replayWorker('--trial',plan,trial,{modelRuntime:runtime,fetch:async()=>{
  sends++;return syntheticToolReply([replayOracleControls('C6').accept[0].command,'kast query --name OtherService']);
 }});
 assert.equal(sends,1);assert.equal(report.providerSends,1);assert.equal(report.nativeCalls,0);
 assert.equal(report.outcome.type,'incorrect',JSON.stringify(report));
 assert.equal(report.grade.correct,false);assert.ok(report.grade.misuse.includes('RESOURCE_MISUSE'));
 assert.equal(report.toolProposals,2);assert.equal(report.blockedToolProposals,1);
 assert.equal(report.shellCalls,1);assert.equal(report.kastInvocations,1);
 assert.equal(report.productionAdmission.type,'ADMITTED');
 assert.equal(normalizeReplayUsage(report.usage).value.totalTokens,110);
 const ledger=JSON.parse(fs.readFileSync(path.join(root,trial.trialId,'ledger.json')));
 assert.equal(ledger.toolProposals,2);assert.equal(ledger.blockedToolProposals,1);
});

test('real SDK replay preserves complete format inputs once at synthetic transport and accounts for failed work', {skip:!planPath},async t=>{
 assert.ok(path.isAbsolute(planPath));
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-sdk-')));fs.chmodSync(root,0o700);
 t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const source=JSON.parse(fs.readFileSync(planPath,'utf8'));
 const plan={...source,outputRoot:root,adapterManifestSha256:replaySha(fs.readFileSync(source.adapterManifest)),authPath:'/never-read-live-auth',modelsStorePath:'/never-read-live-models'};
 const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
 const sdk=await load('dist/index.js'),runtime=await syntheticRuntime(sdk,load);
 const savedFetch=globalThis.fetch;let unexpectedNetwork=0;
 globalThis.fetch=async()=>{unexpectedNetwork++;throw Error('UNEXPECTED_NETWORK');};
 t.after(()=>{globalThis.fetch=savedFetch;});
 const all=expandReplayPlan(plan);
 const rows=[...all.filter(trial=>trial.caseId==='C5'),all.find(trial=>trial.caseId==='C6'&&trial.repetition===1&&trial.format==='TOON')];
 assert.equal(rows.length,5);
 let sends=0;
 for(const trial of rows){
  const preflight=await replayWorker('--preflight',plan,trial,{modelRuntime:runtime});
  assert.equal(preflight.type,'projected',JSON.stringify(preflight));
  assert.equal(preflight.presentationOccurrences,1);assert.equal(preflight.complete,true);
  const preflightLedger=JSON.parse(fs.readFileSync(path.join(root,`preflight-${trial.trialId}`,'ledger.json')));
  assert.equal(preflightLedger.providerSends,0);assert.equal(preflightLedger.nativeCalls,0);
  const presentation=JSON.parse(fs.readFileSync(path.join(root,`preflight-${trial.trialId}`,'input.json'))).presentation;
  const kind=trial.caseId==='C6'?'incomplete':trial.repetition===1?'correct':trial.format==='TOON'?'malformed':'transport';
  const report=await replayWorker('--trial',plan,trial,{modelRuntime:runtime,fetch:async(_url,options)=>{
   sends++;
   const payload=decodeBody(options),projection=inspectReplayProjection(payload,presentation);
   assert.equal(projection.type,'projected');assert.equal(projection.presentationOccurrences,1);
   assert.equal(projection.payloadSha256,preflight.payloadSha256);
   assert.equal(payload.max_output_tokens,2000);
   assert.equal(payload.model,'gpt-6.1-sol');assert.equal(payload.reasoning.effort,'high');
   assert.equal(JSON.stringify(payload).includes('GRADED_CORRECT'),false);
   assert.equal(payload.input.filter(item=>item.role==='user').length,1);
   if(kind==='transport')throw Error('SYNTHETIC_TRANSPORT_FAILURE');
   return syntheticReply(kind==='correct'?JSON.stringify(replayOracleControls('C5').accept[0].decision):'malformed decision',kind==='incomplete');
  }});
  assert.equal(report.measurement,'SYNTHETIC_TRANSPORT');assert.equal(report.latency.shellMs,0);assert.equal(report.providerSends,1);assert.equal(report.shellCalls,0);assert.equal(report.kastInvocations,0);assert.equal(report.nativeCalls,0);
  assert.equal(report.outcome.type,kind==='correct'?'correct':kind==='malformed'?'incorrect':kind==='incomplete'?'incomplete':'invalid',JSON.stringify(report));
  if(kind!=='transport'){
   const usage=normalizeReplayUsage(report.usage);
   assert.equal(usage.type,'known',JSON.stringify(report.usage));
   assert.equal(usage.value.input,80);assert.equal(usage.value.cacheRead,20);assert.equal(usage.value.output,10);assert.equal(usage.value.totalTokens,110);
  }else assert.equal(report.usage,null);
  assert.equal(report.protectedFilesUnchanged,true);
 }
 assert.equal(sends,5);
 // One synthetic model proposal traverses the actual SDK bash executor, shell,
 // driver, production admission, and declared fixture transition.
 const invocationRoot=path.join(root,'tool-invocation');fs.mkdirSync(invocationRoot,{mode:0o700});
 const invocationPlan={...plan,outputRoot:invocationRoot};
 const invocationTrial=all.find(trial=>trial.caseId==='C6'&&trial.repetition===1&&trial.format==='TOON');
 const preflight=await replayWorker('--preflight',invocationPlan,invocationTrial,{modelRuntime:runtime});
 assert.equal(preflight.type,'projected');
 const command=replayOracleControls('C6').accept[0].command;
 let toolSends=0;
 const report=await replayWorker('--trial',invocationPlan,invocationTrial,{modelRuntime:runtime,fetch:async(_url,options)=>{
  toolSends++;const payload=decodeBody(options);
  assert.equal(payload.input.filter(item=>item.type==='function_call_output').length,1);
  assert.equal(toolSends,1,'The SDK must block its automatic post-tool request before transport');
  return syntheticToolReply(command);
 }});
 assert.equal(report.measurement,'SYNTHETIC_TRANSPORT');assert.ok(report.latency.shellMs>0);assert.ok(report.latency.setupMs>0);assert.equal(toolSends,1);assert.equal(report.providerSends,1);
 assert.equal(report.shellCalls,1,JSON.stringify(report));assert.equal(report.kastInvocations,1,JSON.stringify(JSON.parse(fs.readFileSync(path.join(invocationRoot,invocationTrial.trialId,'shell.json')))));assert.equal(report.nativeCalls,0);
 assert.equal(report.outcome.type,'correct',JSON.stringify(report));assert.equal(report.grade.correct,true);
 assert.equal(normalizeReplayUsage(report.usage).value.totalTokens,110,'A blocked second request cannot erase finalized first-request usage');
 const archive=JSON.parse(fs.readFileSync(path.join(invocationRoot,invocationTrial.trialId,'shell.json')));
 assert.equal(archive.captures.length,1);assert.equal(archive.captures[0].command,command);
 assert.equal(archive.captures[0].exitStatus,0);assert.equal(archive.captures[0].stderr,'');
 assert.match(archive.captures[0].stdout,/OrderService/);
 assert.equal(archive.invocations.length,1);assert.deepEqual(archive.invocations[0].request.argv,['query','--name','OrderService','--in','services/orders','--source-set','main','--fields','name,signature']);
 assert.equal(archive.invocations[0].request.stdin,'');assert.equal(archive.invocations[0].admission.type,'ADMITTED');
 const events=JSON.parse(fs.readFileSync(path.join(invocationRoot,invocationTrial.trialId,'events.json')));
 assert.equal(events.filter(event=>event.type==='provider_followup_blocked').length,1);
 assert.equal(events.filter(event=>event.type==='actual_provider_send').length,1);
 assert.equal(unexpectedNetwork,0);
});

// Execution profiles own different SDK cache keys. The comparison removes only
// that isolated-session field, then compares every remaining wire byte.
test('fresh and session profiles preserve normalized provider bytes and exact shell results',{skip:!planPath},async t=>{
 assert.ok(path.isAbsolute(planPath));
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-sdk-profiles-')));
 t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const source=JSON.parse(fs.readFileSync(planPath,'utf8'));
 const plan={...source,instructionProfile:'full',adapterManifestSha256:replaySha(fs.readFileSync(source.adapterManifest)),authPath:'/never-read-live-auth',modelsStorePath:'/never-read-live-models'};
 const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
 const sdk=await load('dist/index.js'),runtime=await syntheticRuntime(sdk,load);
 const savedFetch=globalThis.fetch;let unexpectedNetwork=0,sends=0;
 globalThis.fetch=async()=>{unexpectedNetwork++;throw Error('UNEXPECTED_NETWORK');};
 t.after(()=>{globalThis.fetch=savedFetch;});
 const trials=expandReplayPlan(plan).filter(trial=>['C5','C6'].includes(trial.caseId)&&trial.repetition===1);
 assert.equal(trials.length,4);
 for(const trial of trials){
  const pair=[];
  for(const executionProfile of ['fresh','session']){
   const outputRoot=path.join(root,executionProfile);fs.mkdirSync(outputRoot,{mode:0o700,recursive:true});
   const candidate={...plan,executionProfile,outputRoot};
   const preflight=await replayWorker('--preflight',candidate,trial,{modelRuntime:runtime});
   assert.equal(preflight.type,'projected',JSON.stringify(preflight));
   assert.equal(preflight.presentationOccurrences,1);assert.equal(preflight.encodingEquivalent,true);
   const preflightLedger=JSON.parse(fs.readFileSync(path.join(outputRoot,`preflight-${trial.trialId}`,'ledger.json')));
   assert.equal(preflightLedger.providerSends,0);assert.equal(preflightLedger.nativeCalls,0);
   let wire=null,payload=null,trialSends=0;
   const report=await replayWorker('--trial',candidate,trial,{modelRuntime:runtime,fetch:async(_url,options)=>{
    sends++;trialSends++;assert.equal(trialSends,1,'No automatic provider follow-up can reach transport');
    wire=decodeBodyText(options);payload=JSON.parse(wire);
    assert.equal(wire,JSON.stringify(payload),'The captured JSON serialization must survive comparison without normalization');
    assert.equal(payload.max_output_tokens,2000);assert.equal(payload.model,'gpt-6.1-sol');assert.equal(payload.reasoning.effort,'high');
    assert.equal(payload.input.filter(item=>item.type==='function_call_output').length,1);
    assert.equal(typeof payload.prompt_cache_key,'string');assert.ok(payload.prompt_cache_key.length>0);
    return trial.caseId==='C5'?syntheticReply(JSON.stringify(replayOracleControls('C5').accept[0].decision)):syntheticToolReply(replayOracleControls('C6').accept[0].command);
   }});
   assert.equal(report.measurement,'SYNTHETIC_TRANSPORT');assert.equal(report.outcome.type,'correct',JSON.stringify(report));
   assert.equal(report.providerSends,1);assert.equal(trialSends,1);assert.equal(report.nativeCalls,0);assert.equal(report.grade.correct,true);
   assert.equal(report.identity.instructionProfile,'full');assert.equal(report.identity.executionProfile,executionProfile);
   assert.equal(report.projection.payloadSha256,preflight.payloadSha256);assert.equal(report.actualProviderProjection.payloadSha256,preflight.payloadSha256);
   assert.equal(report.actualProviderProjection.presentationOccurrences,1);assert.equal(report.actualProviderProjection.complete,true);
   assert.equal(normalizeReplayUsage(report.usage).value.totalTokens,110);
   assert.equal(report.protectedFilesUnchanged,true);
   const archive=JSON.parse(fs.readFileSync(path.join(outputRoot,trial.trialId,'shell.json')));
   const events=JSON.parse(fs.readFileSync(path.join(outputRoot,trial.trialId,'events.json')));
   assert.equal(events.filter(event=>event.type==='actual_provider_send').length,1);
   if(trial.caseId==='C5'){
    assert.equal(report.shellCalls,0);assert.equal(report.kastInvocations,0);assert.equal(report.latency.shellMs,0);
    assert.deepEqual(archive.captures,[]);assert.deepEqual(archive.invocations,[]);
    assert.equal(events.filter(event=>event.type==='provider_followup_blocked').length,0);
   }else{
    assert.equal(report.shellCalls,1);assert.equal(report.kastInvocations,1);assert.ok(report.latency.shellMs>0);
    assert.equal(archive.captures.length,1);assert.equal(archive.invocations.length,1);
    assert.equal(archive.captures[0].command,replayOracleControls('C6').accept[0].command);
    assert.equal(archive.captures[0].exitStatus,0);assert.equal(archive.captures[0].stderr,'');
    assert.equal(archive.invocations[0].admission.type,'ADMITTED');assert.equal(archive.invocations[0].admission.nativeCalls,0);
    assert.equal(archive.invocations[0].request.stdin,'');
    assert.equal(events.filter(event=>event.type==='provider_followup_blocked').length,1);
   }
   if(executionProfile==='fresh')assert.equal(report.adapterSession,null);
   else{
    assert.equal(report.adapterSession.type,'closed',JSON.stringify(report.adapterSession));
    assert.equal(report.adapterSession.ownedChildReaped,true);assert.equal(report.adapterSession.spawned,true);
    assert.equal(report.adapterSession.exitCode,0);assert.equal(report.adapterSession.signal,null);assert.equal(report.adapterSession.stderrBytes,0);
   }
   pair.push({report,payload,wire,archive});
  }
  const [fresh,session]=pair;
  assert.notEqual(fresh.payload.prompt_cache_key,session.payload.prompt_cache_key,'SDK session identities stay isolated');
  const withoutCache=payload=>JSON.stringify(Object.fromEntries(Object.entries(payload).filter(([key])=>key!=='prompt_cache_key')));
  assert.equal(withoutCache(fresh.payload),withoutCache(session.payload),'Only the isolated SDK cache key can differ in provider wire bytes');
  for(const field of ['input','instructions','tools'])assert.equal(JSON.stringify(fresh.payload[field]),JSON.stringify(session.payload[field]),field);
  assert.equal(fresh.report.actualProviderProjection.payloadSha256,session.report.actualProviderProjection.payloadSha256);
  assert.equal(fresh.report.presentationSha256,session.report.presentationSha256);
  const result=archive=>archive.captures.map(({command,stdout,stderr,exitStatus})=>({command,stdout,stderr,exitStatus}));
  assert.deepEqual(result(fresh.archive),result(session.archive));
  assert.deepEqual(fresh.archive.invocations.map(row=>row.request.argv),session.archive.invocations.map(row=>row.request.argv));
 }
 assert.equal(sends,8);assert.equal(unexpectedNetwork,0);
});

test('SDK cancellation closes and reaps its prepared adapter without a shell or another request',{skip:!planPath},async t=>{
 assert.ok(path.isAbsolute(planPath));
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-sdk-cancel-')));
 t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const source=JSON.parse(fs.readFileSync(planPath,'utf8'));
 const plan={...source,instructionProfile:'full',executionProfile:'session',outputRoot:root,adapterManifestSha256:replaySha(fs.readFileSync(source.adapterManifest)),authPath:'/never-read-live-auth',modelsStorePath:'/never-read-live-models'};
 const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
 const sdk=await load('dist/index.js'),runtime=await syntheticRuntime(sdk,load);
 const before={message:process.listenerCount('message'),SIGTERM:process.listenerCount('SIGTERM')};
 const trial=expandReplayPlan(plan).find(trial=>trial.caseId==='C5'&&trial.repetition===1&&trial.format==='TOON');
 assert.equal((await replayWorker('--preflight',plan,trial,{modelRuntime:runtime})).type,'projected');
 const savedFetch=globalThis.fetch;let sends=0,unexpectedNetwork=0;
 globalThis.fetch=async()=>{unexpectedNetwork++;throw Error('UNEXPECTED_NETWORK');};
 t.after(()=>{globalThis.fetch=savedFetch;});
 const report=await replayWorker('--trial',plan,trial,{modelRuntime:runtime,fetch:async()=>{
  sends++;assert.equal(sends,1);process.emit('message',{type:'cancel'});
  return syntheticReply(JSON.stringify(replayOracleControls('C5').accept[0].decision));
 }});
 assert.equal(report.measurement,'SYNTHETIC_TRANSPORT');assert.equal(report.outcome.type,'incomplete',JSON.stringify(report));
 assert.deepEqual(report.outcome.reasons,['DEADLINE']);assert.equal(report.providerSends,1);assert.equal(sends,1);
 assert.equal(report.shellCalls,0);assert.equal(report.kastInvocations,0);assert.equal(report.nativeCalls,0);
 assert.equal(report.adapterSession.type,'failure');assert.equal(report.adapterSession.failure.reason,'CANCELLED');
 assert.equal(report.adapterSession.ownedChildReaped,true);assert.equal(report.protectedFilesUnchanged,true);
 assert.equal(process.listenerCount('message'),before.message);assert.equal(process.listenerCount('SIGTERM'),before.SIGTERM);
 assert.equal(unexpectedNetwork,0);
});

// Synthetic provider replies enter the real SDK shell path. Invalid bytes and
// exhausted output remain failed model work with finalized first-request usage.
test('shell model failures retain failed spend through the real SDK and production adapter',{skip:!planPath},async t=>{
 assert.ok(path.isAbsolute(planPath));
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-sdk-shell-failures-')));
 t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
 const source=JSON.parse(fs.readFileSync(planPath,'utf8'));
 const plan={...source,instructionProfile:'full',executionProfile:'session',adapterManifestSha256:replaySha(fs.readFileSync(source.adapterManifest)),authPath:'/never-read-live-auth',modelsStorePath:'/never-read-live-models'};
 const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
 const sdk=await load('dist/index.js'),runtime=await syntheticRuntime(sdk,load);
 const savedFetch=globalThis.fetch;let unexpectedNetwork=0,totalSends=0;
 globalThis.fetch=async()=>{unexpectedNetwork++;throw Error('UNEXPECTED_NETWORK');};
 t.after(()=>{globalThis.fetch=savedFetch;});
 const trial=expandReplayPlan(plan).find(value=>value.caseId==='C6'&&value.repetition===1&&value.format==='TOON');
 const valid=replayOracleControls('C6').accept[0].command;
 const cases=[
  {name:'invalid stdin before production admission',command:`printf '\\377' | ${valid}`,reason:'STDIN_INVALID_UTF8',outcome:'incorrect',admitted:false},
  {name:'invalid stdout after a correctly admitted target',command:`${valid}; printf '\\377'`,reason:'SHELL_STDOUT_INVALID_UTF8',outcome:'incorrect',admitted:true},
  {name:'stdout budget after a correctly admitted target',command:`${valid}; printf '%*s' 70000 ''`,reason:'SHELL_OUTPUT_BUDGET',outcome:'incomplete',admitted:true},
 ];
 for(const [index,control]of cases.entries())await t.test(control.name,async()=>{
  const outputRoot=path.join(root,String(index));fs.mkdirSync(outputRoot,{mode:0o700});
  const candidate={...plan,outputRoot};
  const preflight=await replayWorker('--preflight',candidate,trial,{modelRuntime:runtime});
  assert.equal(preflight.type,'projected',JSON.stringify(preflight));assert.equal(preflight.encodingEquivalent,true);
  const preflightLedger=JSON.parse(fs.readFileSync(path.join(outputRoot,`preflight-${trial.trialId}`,'ledger.json')));
  assert.equal(preflightLedger.providerSends,0);assert.equal(preflightLedger.nativeCalls,0);
  let sends=0;
  const report=await replayWorker('--trial',candidate,trial,{modelRuntime:runtime,fetch:async()=>{
   totalSends++;sends++;assert.equal(sends,1,'Failed model work cannot trigger another provider request');
   return syntheticToolReply(control.command);
  }});
  assert.equal(report.measurement,'SYNTHETIC_TRANSPORT');assert.deepEqual(report.outcome,{type:control.outcome,reasons:[control.reason]},JSON.stringify(report));
  assert.equal(report.providerSends,1);assert.equal(sends,1);assert.equal(report.shellCalls,1);assert.equal(report.kastInvocations,1);assert.equal(report.nativeCalls,0);
  assert.equal(report.grade.correct,false);assert.ok(report.grade.misuse.includes(control.reason));assert.equal(report.grade.argumentCause,null);
  assert.equal(report.shellModelFailure.reason,control.reason);assert.equal(report.adapterFailure,null);assert.equal(report.protectedFilesUnchanged,true);
  const usage=normalizeReplayUsage(report.usage);assert.equal(usage.type,'known');assert.equal(usage.value.totalTokens,110);
  assert.equal(usage.value.input,80);assert.equal(usage.value.cacheRead,20);assert.equal(usage.value.output,10);
  const ledger=JSON.parse(fs.readFileSync(path.join(outputRoot,trial.trialId,'ledger.json')));
  assert.equal(ledger.providerSends,1);assert.equal(normalizeReplayUsage(ledger.usage).value.totalTokens,110);
  assert.equal(report.adapterSession.type,'closed',JSON.stringify(report.adapterSession));assert.equal(report.adapterSession.ownedChildReaped,true);assert.equal(report.adapterSession.exitCode,0);
  const archive=JSON.parse(fs.readFileSync(path.join(outputRoot,trial.trialId,'shell.json')));
  assert.equal(archive.modelFailure.reason,control.reason);assert.equal(archive.captures.length,1);assert.equal(archive.invocations.length,1);
  const capture=archive.captures[0],invocation=archive.invocations[0];
  assert.equal(capture.command,control.command);assert.equal(report.proposal.input.command,control.command);
  assert.deepEqual(invocation.request.argv,replayOracleControls('C6').accept[0].argv);
  if(control.admitted){
   assert.equal(report.productionAdmission.type,'ADMITTED');assert.equal(invocation.admission.type,'ADMITTED');
   assert.equal(report.grade.dimensions.arguments,'PASS');assert.equal(report.grade.dimensions.target,'PASS');assert.equal(report.grade.dimensions.scope,'PASS');
   if(control.reason==='SHELL_STDOUT_INVALID_UTF8'){
    assert.equal(capture.stdoutEncoding,'INVALID_UTF8');assert.equal(capture.stdout,'');
    const raw=Buffer.from(capture.stdoutBytesBase64,'base64');assert.equal(raw.at(-1),0xff);assert.ok(raw.length>1);assert.equal(capture.stdoutTruncated,false);
   }else{
    assert.equal(capture.stdoutTruncated,true);assert.ok(capture.stdoutBytes>65536);
    assert.ok(Buffer.from(capture.stdoutBytesBase64,'base64').length<=65537);
   }
  }else{
   assert.equal(invocation.request.stdinEncoding,'INVALID_UTF8');assert.deepEqual(Buffer.from(invocation.request.stdinBytesBase64,'base64'),Buffer.from([0xff]));
   assert.equal(report.productionAdmission.type,'DRIVER_INPUT_REJECTED');assert.equal(report.productionAdmission.nativeCalls,0);
   assert.equal(report.productionAdmission.arguments,undefined);assert.equal(invocation.admission.arguments,undefined);
   assert.equal(capture.stdout,'');assert.equal(capture.exitStatus,2);assert.match(capture.stderr,/REPLAY_DRIVER_STDIN_INVALID_UTF8/);
   assert.doesNotMatch(capture.stderr,/kast:|INVALID_COMMAND|INVALID_ARGUMENTS|INVALID_FIELDS/);
  }
  const events=JSON.parse(fs.readFileSync(path.join(outputRoot,trial.trialId,'events.json')));
  assert.equal(events.filter(event=>event.type==='actual_provider_send').length,1);
 });
 assert.equal(totalSends,3);assert.equal(unexpectedNetwork,0);
});
