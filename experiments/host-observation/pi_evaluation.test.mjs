import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { createHash } from 'node:crypto';
import { inspectProviderPayload, verifyInputCalibration } from './pi_evaluation_payload.mjs';
import { CasePolicy, decodeEnvelope, decodeUsage } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import { summarizeGuard } from './pi_evaluation_records.mjs';
import { driveSession, runOwnedWorker } from './pi_evaluation_controller.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';
import { isolatedSettings } from './pi_evaluation_worker.mjs';

const config=()=>({name:'negative',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:80000,work:{reportedTokens:30000,requests:2,tools:2,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576});
const providerPayload=()=>({model:'gpt-6.1-sol',reasoning:{effort:'high'},tools:[],instructions:'unchanged synthetic instructions',input:[]});
const providerObservation=(body=providerPayload())=>({provider:'openai-codex',model:'gpt-6.1-sol',thinking:'high',payload:inspectProviderPayload({...body,max_output_tokens:2000},4194304)});
const pinned=providerObservation();
const makePolicy=settings=>new CasePolicy(settings);
// Optional synthetic empirical observations are not admission/spend authority.
function empiricallyCalibratedPolicy(settings,request=providerPayload()) {
  const policy=new CasePolicy(settings);
  const bytes=Buffer.from(JSON.stringify({type:'FULL_PAYLOAD_CALIBRATION',provider:'openai-codex',model:'gpt-6.1-sol',request,usage:{input:100,cacheRead:0,cacheWrite:0,output:0,totalTokens:100},calibratedInputCeiling:settings.inputTokenCeiling,method:'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE',qualification:'CALIBRATION_NOT_TOKENIZER_PROOF'}));
  const proof=verifyInputCalibration(bytes,createHash('sha256').update(bytes).digest('hex'));
  assert.equal(proof.type,'verified-input-calibration');assert.equal(policy.registerInputCalibration(proof).allow,true);
  return policy;
}
// Public synthetic facts and usage shapes from the 2026-10-10 pilot. No logs,
// source dumps, live handles, credential fields or personal paths are fixtures.
const fixture=name=>JSON.parse(readFileSync(new URL(`./pi-fixtures/${name}-document.json`,import.meta.url),'utf8'));
const denseRejected={type:'rejected_document',document:fixture('rejected')};
const negativeComplete={type:'complete',document:fixture('complete')};
const qualified={type:'qualified',document:fixture('qualified')};

test('recorded terminal dense rejection stops the next provider request immediately',()=>{
  const policy=makePolicy({...config(),name:'positive',expectedSemanticCalls:2,work:{...config().work,reportedTokens:60000,requests:3}});
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:17199,cacheRead:0,cacheWrite:0,output:92,totalTokens:17291});
  policy.toolResult(negativeComplete);
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:1011,cacheRead:17024,cacheWrite:0,output:110,totalTokens:18145});
  policy.toolResult(denseRejected);
  assert.deepEqual(policy.beforeProvider(pinned),{allow:false,reason:'INTENTIONAL_REJECTION',phase:'STOPPED'});
});
test('recorded negative work usage leaves the separate delivery stop threshold unused',()=>{
  const policy=makePolicy(config());
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:17139,cacheRead:0,cacheWrite:0,output:89,totalTokens:17228});
  policy.toolResult(negativeComplete);
  assert.equal(policy.report().phaseUsage.DELIVERY.totalTokens,0);
  assert.equal(policy.beforeProvider(pinned).allow,true,'Byte/request admission does not reserve or guarantee token spend');
});

function callbacks(policy,recordResult) {
  const handlers=new Map(),records=[];let aborted=0;
  const pi={on:(name,handler)=>handlers.set(name,handler),setActiveTools:()=>{},getActiveTools:()=>['query_symbols','check_diagnostics','health_check'],getThinkingLevel:()=> 'high'};
  const ctx={model:{provider:'openai-codex',id:'gpt-6.1-sol'},abort:()=>{aborted++;}};
  evaluationGuard(policy,value=>{records.push(value);return recordResult;})(pi);
  return {emit:(name,event)=>handlers.get(name)(event,ctx),records,get aborted(){return aborted;}};
}
const providerEvent=()=>({payload:providerPayload()});
const resultEvent=envelope=>({toolName:'query_symbols',toolCallId:'call-1',content:[{type:'text',text:JSON.stringify(envelope)}]});

test('local output cap admission and denial never qualify backend enforcement',()=>{
  for(const [policy,event,allow] of [[empiricallyCalibratedPolicy(config()),providerEvent(),true],
    [makePolicy(config()),providerEvent(),true],
    [makePolicy({...config(),maximumProviderPayloadBytes:1000}),
      {payload:{...providerPayload(),input:[{role:'user',content:'x'.repeat(2000)}]}},false]]) {
    const guard=callbacks(policy),projected=guard.emit('before_provider_request',event);
    assert.equal(projected.max_output_tokens,2000);
    const report=policy.report();
    assert.equal(report.requestObservations[0]?.allow??false,allow);
    assert.equal(guard.aborted,allow?0:1);
    assert.equal(Object.hasOwn(report,'providerEnforcedTokenCap'),false);
    assert.equal(Object.hasOwn(policy,'providerEnforcedTokenCap'),false);
    assert.equal(report.outputCapApplied,allow);
    assert.equal(report.backendOutputCapQualification,'UNQUALIFIED');
    assert.equal(report.usage.totalTokens,0,'Callbacks perform no provider send or inference');
  }
});

test('provider projection replaces saved-message byte growth and enforces the output reserve',()=>{
  const policy=makePolicy(config());
  assert.equal(policy.restoreReceivedResult(qualified,238968).allow,true);
  const guard=callbacks(policy),event=providerEvent();
  const projected=guard.emit('before_provider_request',event);
  assert.equal(guard.aborted,0,'SDK-omitted details must not consume delivery input budget');
  assert.equal(projected.max_output_tokens,2000);
  assert.equal(policy.report().outputCapApplied,true);
  const estimate=policy.report().requestObservations[0];
  assert.equal(estimate.inputEstimate,Buffer.byteLength(JSON.stringify({...event.payload,max_output_tokens:2000})));
  assert.equal(estimate.inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
  assert.equal(estimate.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  const tooLarge=makePolicy({...config(),maximumProviderPayloadBytes:1000}),denied=callbacks(tooLarge);
  const largeEvent=providerEvent();largeEvent.payload.input=[{role:'user',content:'x'.repeat(30000)}];
  denied.emit('before_provider_request',largeEvent);
  assert.equal(tooLarge.report().outcome,'HARNESS_BUDGET_LIMIT');
  assert.equal(denied.aborted,1,'Actual provider-visible growth remains bounded');
});

test('inline SDK declarations consume the declaration bound once and unknown input fails closed',()=>{
  const event=providerEvent();
  event.payload.input=[{type:'additional_tools',role:'developer',tools:[{type:'function',name:'synthetic',description:'x'.repeat(10000),parameters:{type:'object'}}]}];
  const policy=makePolicy(config()),guard=callbacks(policy);
  guard.emit('before_provider_request',event);
  const observation=policy.report().requestObservations[0];
  assert.equal(observation.contextBytes,2);assert.ok(observation.declarationInputBytes>10000);
  assert.equal(observation.inputEstimate,Buffer.byteLength(JSON.stringify({...event.payload,max_output_tokens:2000})));
  assert.equal(observation.inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
  assert.equal(observation.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.equal(observation.allow,true);
  event.payload.input[0].tools[0].description='x'.repeat(90000);
  const oversized=makePolicy(config()),denied=callbacks(oversized);denied.emit('before_provider_request',event);
  assert.equal(oversized.report().outcome,'HARNESS_BUDGET_LIMIT');
  const unknown=makePolicy(config()),uncertain=callbacks(unknown);
  event.payload.input=[{type:'additional_tools',data:'x'.repeat(30000)}];uncertain.emit('before_provider_request',event);
  assert.equal(unknown.report().outcome,'HARNESS_PAYLOAD_UNSUPPORTED','Unproven declaration shape cannot evade admission');
});

test('actual guard aborts in tool_result before any provider continuation or changed-scope tool',()=>{
  const policy=makePolicy({...config(),name:'positive',expectedSemanticCalls:2});const guard=callbacks(policy);
  guard.emit('before_provider_request',providerEvent());
  guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
  guard.emit('tool_result',resultEvent(denseRejected));
  assert.equal(guard.aborted,1,'rejection must abort at the callback boundary itself');
  const blocked=guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'changed-scope',input:{request:{type:'RUN',scope:'changed',budget:{maxElapsedMs:20000}}}});
  assert.deepEqual(blocked,{block:true,reason:'INTENTIONAL_REJECTION'});
  assert.equal(policy.report().modelProposals[1].nativeReplyObserved,false);
  assert.equal(policy.report().modelProposals[1].harnessDecision,'INTENTIONAL_REJECTION');
});

test('each case keeps independent usage including cached input and newly generated output',()=>{
  const positive=makePolicy({...config(),name:'positive',maximumReportedTokens:30000}),negative=makePolicy(config());
  positive.beforeProvider(pinned);positive.modelUsage({input:1000,cacheRead:30000,cacheWrite:0,output:900,reasoning:100,totalTokens:31900});
  assert.equal(positive.report().outcome,'HARNESS_BUDGET_LIMIT');
  assert.equal(negative.beforeProvider(pinned).allow,true);
  assert.deepEqual(negative.report().usage,{input:0,cacheRead:0,cacheWrite:0,output:0,reasoning:0,totalTokens:0});
  assert.equal(positive.report().usage.totalTokens,31900);assert.equal(positive.report().phaseUsage.WORK.totalTokens,31900);
  assert.equal(positive.report().usage.reasoning,100);
});

test('declaration bytes deny before admission and finalized global overshoot is fully debited',()=>{
  const tools={...providerPayload(),tools:[{type:'function',name:'synthetic',description:'x'.repeat(90000),parameters:{type:'object'}}]};
  const oversized=makePolicy(config(),tools);assert.equal(oversized.beforeProvider(providerObservation(tools)).reason,'HARNESS_BUDGET_LIMIT');
  const body={...providerPayload(),instructions:'x'.repeat(90000)};
  const instructions=makePolicy(config(),body);assert.equal(instructions.beforeProvider(providerObservation(body)).reason,'HARNESS_BUDGET_LIMIT');
  const small=makePolicy({...config(),maximumReportedTokens:35000,delivery:{...config().delivery,reportedTokens:18000}});
  small.beforeProvider(pinned);small.modelUsage({input:17139,output:89,totalTokens:17228});small.toolResult(negativeComplete,2141);
  assert.equal(small.beforeProvider(pinned).allow,true,'No calibrated spend guarantee is inferred before the response');
  assert.equal(small.modelUsage({input:18001,output:100,totalTokens:18101}).reason,'HARNESS_BUDGET_LIMIT');
  assert.equal(small.report().phaseUsage.DELIVERY.totalTokens,18101);
  assert.equal(small.report().usage.totalTokens,35329);
  assert.equal(small.beforeProvider(pinned).reason,'HARNESS_BUDGET_LIMIT');
  assert.equal(small.report().nativeOutcome,'EXHAUSTIVE_RESULT');assert.equal(small.report().finalAnswer,false);
});

test('evidence-only opt-in admits exact READ_RESULT but rejects RUN RESUME and changed grants',()=>{
  const next=denseRejected.document.rejection.detail.evidence.nextQuery.request;
  for(const request of [{type:'RUN',scope:'changed'},{type:'RESUME'}, {...next,cursor:1},{...next,budget:{maxResults:2000}}]) {
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});policy.toolResult(denseRejected);
    assert.equal(policy.toolCall('query_symbols',{request},'blocked').allow,false);
    assert.equal(policy.report().nativeRepliesObserved,0);
  }
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true});policy.toolResult(denseRejected);
  assert.equal(policy.toolCall('query_symbols',{request:next},'read').allow,true);
  policy.toolResult(negativeComplete,0,'read');
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Qualified rejection and delivered evidence.'}]});
  assert.equal(policy.report().outcome,'INTENTIONAL_REJECTION');assert.equal(policy.report().finalAnswer,true);
});

test('final text and exhaustive native coverage never manufacture oracle row/evidence verification',()=>{
  const policy=makePolicy(config());policy.toolResult(negativeComplete);
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Qualified negative answer.'}]});
  const report=policy.report();assert.equal(report.outcome,'FINAL_ANSWER');assert.equal(report.exhaustiveEvidence,true);
  assert.equal(report.exhaustiveRowsVerified,false);assert.equal(report.requiredEvidenceVerified,false);
});

test('supported received-result controller continues once with zero prompts or tool execution',async()=>{
  const effects=[];const result={role:'toolResult',toolName:'query_symbols',content:[{type:'text',text:'received evidence'}]};
  const session={agent:{state:{messages:[result]},continue:async()=>effects.push('continue'),waitForIdle:async()=>effects.push('idle')},prompt:async()=>assert.fail('No new prompt allowed'),dispose:()=>effects.push('dispose')};
  await driveSession(session,{mode:'received-result'});
  assert.deepEqual(effects,['continue','idle','dispose']);assert.deepEqual(session.agent.state.messages,[result]);
  await assert.rejects(driveSession(session,{mode:'received-result',prompt:'coaching'}),/no new prompt/);
});

test('delivery phase guard blocks any additional semantic tool before execution',()=>{
  const policy=makePolicy(config());policy.toolResult(negativeComplete);const guard=callbacks(policy);
  const result=guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'retry',input:{request:{type:'RUN'}}});
  assert.deepEqual(result,{block:true,reason:'HARNESS_TOOL_BLOCKED'});assert.equal(guard.aborted,1);assert.equal(policy.report().nativeRepliesObserved,0);
});

test('owned real worker cancellation reaps an unresponsive process without inference',async()=>{
  const cancel=new AbortController();
  const script="process.on('message',()=>{}); process.on('SIGTERM',()=>{}); process.send({type:'ready'}); setInterval(()=>{},1000);";
  const result=await runOwnedWorker(process.execPath,['-e',script],{cwd:process.cwd(),env:{},signal:cancel.signal,wallMillis:5000,graceMillis:0,terminateMillis:0,onEvent:event=>{assert.equal(event.type==='ready'||event.type==='controller_cancel',true);if(event.type==='ready')cancel.abort();}});
  assert.equal(result.type,'cancelled');assert.equal(result.ownedChildReaped,true);assert.equal(result.signal,'SIGKILL');
});

test('unknown envelopes usage and model/effort stay finite failures without weaker fallback',()=>{
  for(const content of [[],[{type:'text',text:'not-json'}],[{type:'text',text:'{"type":"rejected_document"}'}]])assert.deepEqual(decodeEnvelope(content),{type:'invalid'});
  for(const usage of [undefined,null,'unknown',{input:10,output:1,totalTokens:10},{input:0,output:2,reasoning:3,totalTokens:2}])assert.deepEqual(decodeUsage(usage),{type:'invalid'});
  const model=makePolicy(config());assert.equal(model.beforeProvider({...pinned,model:'other'}).reason,'HARNESS_MODEL_MISMATCH');
  const effort=makePolicy(config());assert.equal(effort.beforeProvider(providerObservation({...providerPayload(),reasoning:{effort:'medium'}})).reason,'HARNESS_MODEL_MISMATCH');
});

test('received-result policy preserves qualification and forbids all new semantic work',()=>{
  const policy=makePolicy(config());assert.equal(policy.restoreReceivedResult(negativeComplete,2141).allow,true);
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:153,cacheRead:17664,cacheWrite:0,output:88,totalTokens:17905});
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Exact absent identity, within qualified scope.'}]});
  assert.equal(policy.report().usage.totalTokens,17905);assert.equal(policy.report().semanticRepliesObserved,0);
  assert.equal(policy.report().nativeOutcome,'EXHAUSTIVE_RESULT');assert.equal(policy.report().finalAnswer,true);
  assert.equal(policy.report().phaseUsage.WORK.totalTokens,0);
});

test('explicit plans reject duplicated cases accidental fresh continuation and missing context identity',()=>{
  const plan={piPackageRoot:'/public/pi',kastAdapter:'/public/adapter',workspaceRoot:'/public/fixture',outputRoot:'/new/output',authPath:'/existing/auth',modelsStorePath:'/existing/registry',kastAdapterSha256:'a'.repeat(64),piVersion:'1.0.2',cases:[{...config(),mode:'fresh',prompt:'Fixed public question',wallSeconds:120}]};
  assert.equal(validatePlan(plan),plan);
  assert.throws(()=>validatePlan({...plan,cases:[...plan.cases,...plan.cases]}),/Independent/);
  assert.throws(()=>validatePlan({...plan,cases:[{...plan.cases[0],mode:'received-result',receivedSessionFile:'/saved/session',receivedResultEntryId:'result'}]}),/Continuation/);
  assert.throws(()=>validatePlan({...plan,cases:[{...plan.cases[0],mode:'received-result',prompt:undefined,receivedSessionFile:'/saved/session',receivedResultEntryId:'result'}]}),/Continuation/);
  const restored={...plan.cases[0],mode:'received-result',prompt:undefined,receivedSessionFile:'/saved/session',
    receivedResultEntryId:'result',receivedContextSha256:'b'.repeat(64),receivedSessionSha256:'c'.repeat(64)};
  const receivedPlan={...plan,cases:[restored]};assert.equal(validatePlan(receivedPlan),receivedPlan);
  for(const receivedSessionSha256 of [undefined,null,'not-a-digest',['c'.repeat(64)]])
    assert.throws(()=>validatePlan({...plan,cases:[{...restored,receivedSessionSha256}]}),/Continuation/);
});

test('evidence-only delivery preserves JSON meaning while exact scope cursor and grants stay pinned',()=>{
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true});policy.toolResult(denseRejected);
  const original=denseRejected.document.rejection.detail.evidence.nextQuery.request;
  const reordered={cursor:original.cursor,output:original.output,result:original.result,type:original.type};
  assert.equal(policy.toolCall('query_symbols',{request:reordered},'read').allow,true);
});

test('model response beyond its reserve remains harness budget limit even if final text arrived',()=>{
  const policy=makePolicy(config());policy.restoreReceivedResult(negativeComplete,2141);policy.beforeProvider(pinned);
  policy.modelUsage({input:17000,cacheRead:0,output:9000,totalTokens:26000});
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Model final text received beyond the declared bound.'}]});
  const report=policy.report();assert.equal(report.outcome,'HARNESS_BUDGET_LIMIT');assert.equal(report.finalTextObserved,true);assert.equal(report.finalAnswer,false);
});

test('another tool cannot race past an unresolved semantic call and its eventual rejection',()=>{
  const policy=makePolicy(config());assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN'}},'first').allow,true);
  assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN',scope:'other'}},'parallel').allow,false);
  assert.equal(policy.report().semanticRepliesObserved,0);
});

test('real guard callbacks deliver recorded negative final answer under its separate allowance',()=>{
  const policy=makePolicy(config()),guard=callbacks(policy);
  guard.emit('before_provider_request',providerEvent());
  guard.emit('message_end',{message:{role:'assistant',stopReason:'toolUse',usage:{input:17139,output:89,totalTokens:17228},content:[]}});
  assert.equal(guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}}),undefined);
  guard.emit('tool_execution_start',{toolCallId:'call-1'});
  guard.emit('tool_result',resultEvent(negativeComplete));
  assert.equal(guard.aborted,0);
  guard.emit('before_provider_request',providerEvent());
  guard.emit('message_end',{message:{role:'assistant',stopReason:'stop',usage:{input:153,cacheRead:17664,output:88,totalTokens:17905},content:[{type:'text',text:'Exact declaration absent under native qualification.'}]}});
  const report=policy.report();assert.equal(report.outcome,'FINAL_ANSWER');assert.equal(report.usage.totalTokens,35133);
  assert.equal(report.phaseUsage.WORK.totalTokens,17228);assert.equal(report.phaseUsage.DELIVERY.totalTokens,17905);
  assert.equal(report.modelProposals.length,1);assert.equal(report.semanticRepliesObserved,1);assert.equal(report.modelProposals[0].adapterStartObserved,true);
});

test('ordinary read tools remain admitted without manufacturing query evidence',()=>{
  const policy=makePolicy(config());assert.equal(policy.toolCall('health_check',{},'health').allow,true);
  assert.equal(policy.toolResult(decodeEnvelope([{type:'text',text:'{"type":"complete","document":{"healthy":true}}'}],'health_check'),60,'health').allow,true);
  assert.equal(policy.report().nativeOutcome,'UNOBSERVED');assert.equal(policy.report().semanticRepliesObserved,0);
  policy.toolResult(negativeComplete);assert.equal(policy.toolCall('health_check',{},'extra').allow,false);
});

test('optional empirical input estimate cannot become a spend guarantee or stop threshold',()=>{
  const policy=empiricallyCalibratedPolicy(config());assert.equal(policy.beforeProvider(pinned).allow,true);
  assert.equal(policy.modelUsage({input:26000,cacheRead:0,output:100,totalTokens:26100}).allow,true);
  const report=policy.report();assert.equal(report.usage.totalTokens,26100);
  assert.equal(report.requestObservations[0].inputEstimate,20000);
  assert.equal(report.requestObservations[0].inputEstimateMethod,'VERIFIED_FULL_PAYLOAD_EMPIRICAL_CALIBRATION');
  assert.equal(report.requestObservations[0].inputEstimateQualification,'CALIBRATION_NOT_TOKENIZER_PROOF');
  policy.toolResult(negativeComplete,2141);
  assert.equal(policy.beforeProvider(pinned).allow,true,'Empirical estimate undercounts this finalized response without blocking bounded next input');
});

test('global provider call limit spans work and delivery while saved continuation admits at most one',()=>{
  const fresh=makePolicy({...config(),maximumProviderRequests:3,work:{...config().work,requests:3},delivery:{...config().delivery,requests:2}});
  for(let i=0;i<2;i++) {
    const observation=providerObservation({...providerPayload(),input:[{role:'user',content:`Distinct request ${i}`} ]});
    assert.deepEqual(fresh.beforeProvider(observation),{allow:true,reason:'ADMITTED',phase:'WORK'});
    assert.equal(fresh.modelUsage({input:100,output:10,totalTokens:110},'toolUse').allow,true);
  }
  fresh.toolResult(negativeComplete);
  assert.deepEqual(fresh.beforeProvider(pinned),{allow:true,reason:'ADMITTED',phase:'DELIVERY'});
  assert.equal(fresh.modelUsage({input:100,output:10,totalTokens:110},'toolUse').allow,true);
  assert.equal(fresh.report().providerRequests,3);assert.equal(fresh.report().bounds.maximumProviderRequests,3);
  assert.deepEqual(fresh.beforeProvider(pinned),{allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'});
  assert.equal(fresh.report().phaseUsage.DELIVERY.totalTokens,110,'Delivery retains ample global tokens and a spare phase request');
  const saved=makePolicy({...config(),delivery:{...config().delivery,requests:2}});
  assert.equal(saved.restoreReceivedResult(negativeComplete,2141).allow,true);
  assert.equal(saved.report().bounds.maximumProviderRequests,1);
  assert.equal(saved.beforeProvider(pinned).allow,true);saved.modelUsage({input:100,output:10,totalTokens:110},'toolUse');
  assert.deepEqual(saved.beforeProvider(pinned),{allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'});
  assert.equal(saved.report().providerRequests,1);assert.equal(saved.report().phaseUsage.WORK.totalTokens,0);
});

test('postresponse cumulative cached-inclusive overshoot is fully debited and permanently blocks retry',()=>{
  const policy=makePolicy({...config(),maximumReportedTokens:2000});
  assert.equal(policy.beforeProvider(pinned).allow,true);
  assert.equal(policy.modelUsage({input:600,cacheRead:300,output:100,totalTokens:1000},'toolUse').allow,true);
  assert.equal(policy.toolResult(negativeComplete).allow,true);assert.equal(policy.report().phase,'DELIVERY');
  assert.equal(policy.beforeProvider(pinned).allow,true,'Remaining1000 is a post-response threshold, not a reserved spend guarantee');
  const next=policy.report().requestObservations[1];
  assert.equal(next.inputEstimate,900,'Finalized measured input remains the larger diagnostic floor');
  assert.equal(next.required,2900);assert.equal(next.remaining,1000);
  assert.equal(next.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.deepEqual(policy.modelUsage({input:500,cacheRead:500,cacheWrite:100,output:200,totalTokens:1300},'toolUse'),
    {allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'});
  const report=policy.report();
  assert.deepEqual(report.usage,{input:1100,cacheRead:800,cacheWrite:100,output:300,reasoning:0,totalTokens:2300});
  assert.equal(report.phaseUsage.WORK.totalTokens,1000);assert.equal(report.phaseUsage.DELIVERY.totalTokens,1300);
  assert.equal(report.bounds.maximumReportedTokens,2000);assert.equal(report.providerRequests,2);
  assert.equal(report.requestAdmissionMethod,'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS');
  assert.equal(report.tokenAccountingQualification,'POST_RESPONSE_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT');
  assert.deepEqual(policy.beforeProvider(pinned),{allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'});
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Late final output'}]});
  assert.equal(policy.report().finalAnswer,false);assert.equal(policy.report().usage.totalTokens,2300);
});

test('unknown response accounting model error and over-reserve output stop the guard without retries',()=>{
  for(const [usage,stopReason,expected] of [
    [{input:100,output:1,totalTokens:0},'toolUse','HARNESS_OBSERVATION_INVALID'],
    [{input:100,output:10,totalTokens:110},'error','MODEL_ERROR'],
    [{input:100,output:2001,totalTokens:2101},'stop','HARNESS_BUDGET_LIMIT'],
  ]) {
    const policy=makePolicy(config()),guard=callbacks(policy);guard.emit('before_provider_request',providerEvent());
    guard.emit('message_end',{message:{role:'assistant',usage,stopReason,content:[{type:'text',text:'Observed response'}]}});
    assert.equal(policy.report().outcome,expected);assert.equal(policy.report().phase,'STOPPED');
    assert.ok(guard.aborted>=1);guard.emit('before_provider_request',providerEvent());
    assert.equal(policy.report().providerRequests,1);assert.equal(policy.report().outcome,expected);
    assert.equal(policy.report().finalAnswer,false);
  }
  const policy=makePolicy(config());
  const mismatched={...pinned,payload:inspectProviderPayload({...providerPayload(),max_output_tokens:1999},262144)};
  assert.deepEqual(policy.beforeProvider(mismatched),{allow:false,reason:'HARNESS_OBSERVATION_INVALID',phase:'STOPPED'});
  assert.equal(policy.beforeProvider(pinned).reason,'HARNESS_OBSERVATION_INVALID');assert.equal(policy.report().providerRequests,0);
});

test('streamed assistant response cap aborts the actual update callback and forbids continuation',()=>{
  const policy=makePolicy(config()),guard=callbacks(policy);guard.emit('before_provider_request',providerEvent());
  const content=[{type:'text',text:'x'.repeat(65536)}];
  const message={role:'assistant',content};guard.emit('message_update',{message});
  assert.equal(policy.report().outcome,'HARNESS_BUDGET_LIMIT');assert.equal(guard.aborted,1);
  assert.equal(policy.report().maximumObservedResponseBytes,Buffer.byteLength(JSON.stringify(message)));
  guard.emit('message_end',{message:{role:'assistant',content,stopReason:'stop',usage:{input:100,output:10,totalTokens:110}}});
  assert.equal(policy.report().usage.totalTokens,110,'Finalized usage remains debited after the response-byte stop');
  guard.emit('before_provider_request',providerEvent());assert.equal(policy.report().providerRequests,1);
  assert.equal(policy.report().outcome,'HARNESS_BUDGET_LIMIT');assert.equal(policy.report().finalAnswer,false);
});

test('canonical qualified producer reply permits bounded delivery without exhaustive success',()=>{
  const policy=makePolicy({...config(),expectedSemanticCalls:2}),guard=callbacks(policy);
  guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
  guard.emit('tool_result',resultEvent(qualified));
  assert.equal(guard.aborted,0);assert.equal(policy.report().phase,'DELIVERY');
  assert.equal(policy.report().nativeOutcome,'QUALIFIED_RESULT');assert.equal(policy.report().exhaustiveEvidence,false);
  assert.deepEqual(policy.report().qualification,{knownMinimum:56,limitations:['byte-limit-reached'],progress:{type:'terminal_incomplete',reason:'checkpoint-capacity-exceeded'}});
  policy.beforeProvider(pinned);policy.modelUsage({input:17000,output:100,totalTokens:17100});
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'A qualified prefix; completeness is unproven.'}]});
  assert.equal(policy.report().outcome,'QUALIFIED_ANSWER');assert.equal(policy.report().finalAnswer,true);
  const received=makePolicy(config());assert.equal(received.restoreReceivedResult(qualified,2141).allow,true);
  assert.equal(received.toolCall('query_symbols',{request:{type:'RUN'}},'new').allow,false);
  const malformed=structuredClone(qualified);malformed.document.coverage.exhaustive=true;
  assert.deepEqual(decodeEnvelope([{type:'text',text:JSON.stringify(malformed)}]),{type:'invalid'});
});

test('canonical rejection preserves originalCoverage at the actual guard boundary',()=>{
  const policy=makePolicy(config()),guard=callbacks(policy);
  guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
  guard.emit('tool_result',resultEvent(denseRejected));
  assert.equal(guard.aborted,1);
  assert.deepEqual(policy.report().semanticRejection,{code:'COMPLETION_UNPROVEN',nextAction:'report_failure',knownMinimum:56,limitations:['BYTE_LIMIT_REACHED'],progress:{type:'terminal_incomplete',reason:'checkpoint-capacity-exceeded'},policyProgress:'EVIDENCE_ONLY'});
});

test('worker explicitly disables unaccounted warming and selects abort-safe SSE',()=>{
  let captured,settings;
  const prepared=isolatedSettings({inMemory:options=>{captured=options;return {getTransport:()=>options.transport??'auto',getCacheWarmingMode:()=>options.cacheWarming??'streaming'};}});
  assert.equal(prepared.type,'settings');settings=prepared.settingsManager;
  assert.equal(settings.getTransport(),'sse');assert.equal(settings.getCacheWarmingMode(),'off');
  assert.equal(captured.defaultModel,'gpt-6.1-sol');assert.equal(captured.defaultThinkingLevel,'high');
  assert.deepEqual(captured.extensions,[]);assert.deepEqual(captured.packages,[]);
  const denied=isolatedSettings({inMemory:()=>({getTransport:()=> 'auto',getCacheWarmingMode:()=> 'off'})});
  assert.equal(denied.type,'failure');assert.equal(denied.failure.operation,'SETTINGS_POLICY');
});

test('closed production RPC failures retain their code without fabricated native completion',()=>{
  const source=readFileSync(new URL('../../pi/extension.ts',import.meta.url),'utf8');
  const failures=source.match(/const TOOL_RPC_FAILURES = \[([\s\S]*?)\] as const;/)[1].match(/"[A-Z_]+"/g).map(value=>JSON.parse(value));
  for(const failure of failures) {
    const policy=makePolicy(config()),guard=callbacks(policy);
    guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
    guard.emit('tool_result',resultEvent({type:'rejected',failure}));
    assert.equal(guard.aborted,1);assert.equal(policy.report().outcome,'TOOL_RPC_REJECTION');
    assert.deepEqual(policy.report().rpcRejection,{failure});assert.equal(policy.report().semanticRejection,null);
    assert.equal(policy.report().modelProposals[0].rpcReplyObserved,true);
    assert.equal(policy.report().nativeRepliesObserved,0);assert.equal(policy.report().semanticRepliesObserved,0);
  }
  assert.deepEqual(decodeEnvelope([{type:'text',text:'{"type":"rejected","failure":"UNKNOWN"}'}]),{type:'invalid'});
});

const deliveryCases=JSON.parse(readFileSync(new URL('./pi-fixtures/query-delivery-cases.json',import.meta.url),'utf8')).cases;
function deliveredCase(name,options={}) {
  const policy=makePolicy({...config(),delivery:{...config().delivery,reportedTokens:60000},...options}),guard=callbacks(policy);
  guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
  const fixture=deliveryCases[name];
  guard.emit('tool_result',{...resultEvent(fixture.result),isError:fixture.hostFailure});
  return {policy,guard,fixture};
}
// A small received-prefix policy case derived from the owning Kotlin-serialized
// rejection/page fixture. The changed finite observations are synthetic; these
// counts and identities do not establish native semantics or live authority.
function partialPrefix() {
  const envelope=structuredClone(deliveryCases.rejectedProof.result);
  const detail=envelope.initial.document.rejection.detail;
  detail.originalCoverage.knownMinimum=194;
  detail.originalCoverage.limitations=['BYTE_LIMIT_REACHED'];
  detail.originalCoverage.progress.reason='checkpoint-capacity-exceeded';
  detail.stop='BYTE_LIMIT';
  const page=envelope.pages[0].document;
  page.items=page.items.slice(0,3);
  for(const key of ['failures','omissions','walk_observations','reference_observations','discovery_observations','relation_observations'])page[key]=[];
  page.interpretation.originalCoverage=structuredClone(detail.originalCoverage);
  page.qualification.knownMinimum=194;
  page.qualification.limitations=['byte-limit-reached','static-model-unproven'];
  page.page_progress.rows=3;page.page_progress.failures=0;
  page.evidence_window={type:'MORE',start:0,end:0,total:3};
  page.next_cursor=3;
  envelope.pages=envelope.pages.slice(0,1);
  envelope.delivery.stop='BYTE_LIMIT';envelope.delivery.rpc_count=3;
  return envelope;
}
test('explicit evidence-only BYTE_LIMIT prefix permits one honest answer and preserves native rejection',()=>{
  const envelope=partialPrefix(),before=JSON.stringify(envelope);
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true,delivery:{...config().delivery,requests:3}});
  const decoded=decodeEnvelope(resultEvent(envelope).content);
  assert.equal(policy.restoreReceivedResult(decoded,Buffer.byteLength(before)).allow,true);
  const report=policy.report();
  assert.equal(report.nativeOutcome,'REJECTED');assert.equal(report.exhaustiveEvidence,false);
  assert.equal(report.semanticRejection.knownMinimum,194);
  assert.equal(report.semanticRejection.progress.reason,'checkpoint-capacity-exceeded');
  assert.deepEqual(report.partialEvidence,{stop:'BYTE_LIMIT',receivedRows:3,duplicatePreviewRows:1,
    evidence:{received:0,total:3},knownMinimum:194,question:envelope.pages[0].document.question,
    live:envelope.pages[0].document.live,result:envelope.delivery.result,maximumAnswerRequests:1});
  assert.equal(report.semanticRepliesObserved,0);assert.equal(report.providerRequests,0);
  assert.equal(report.requiredEvidenceVerified,false);assert.equal(report.exhaustiveRowsVerified,false);
  assert.equal(JSON.stringify(envelope),before);
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:100,output:50,totalTokens:150});
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Three received rows only. Native query rejected; evidence remains incomplete.'}]});
  assert.equal(policy.report().outcome,'INTENTIONAL_REJECTION');assert.equal(policy.report().finalAnswer,true);
  assert.equal(policy.beforeProvider(pinned).allow,false);
});
test('partial prefix false policy and other client blockers preserve their first terminal result',()=>{
  for(const stop of ['BYTE_LIMIT','DELIVERED']) {
    const envelope=partialPrefix();envelope.delivery.stop=stop;
    const policy=makePolicy({...config(),evidenceOnlyDelivery:false});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).reason,'INTENTIONAL_REJECTION');
    assert.equal(policy.report().partialEvidence,null);assert.equal(policy.report().nativeOutcome,'REJECTED');
    assert.equal(policy.beforeProvider(pinned).allow,false);
    assert.equal(policy.toolResult(negativeComplete).reason,'INTENTIONAL_REJECTION');
    assert.equal(policy.report().nativeOutcome,'REJECTED');
  }
  for(const stop of ['CANCELLED','TIME_LIMIT','PAGE_LIMIT','DELIVERY_UNAVAILABLE','BUDGET_INCREASE_REQUIRED','MALFORMED_PAGE','IDENTITY_MISMATCH','NON_ADVANCING']) {
    const envelope=partialPrefix();envelope.delivery.stop=stop;
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).reason,'CLIENT_DELIVERY_BLOCKED',stop);
    assert.equal(policy.beforeProvider(pinned).allow,false);assert.equal(policy.report().partialEvidence,null);
  }
});
test('partial-prefix admission rejects lost stale malformed and mismatched scope authority or evidence',()=>{
  const changes=[
    e=>{e.initial=null;e.pages=[];e.delivery.original_outcome='rejected_document';},
    e=>e.pages=[],
    e=>e.pages[0].document.retention.reference='result:v1:00000000-0000-4000-8000-000000000002',
    e=>e.pages[0].document.question.steps.push({type:'changed-scope'}),
    e=>delete e.pages[0].document.live,
    e=>e.pages[0].document.live.contentView='UNKNOWN',
    e=>e.pages[0].document.live.epoch=-1,
    e=>e.pages[0].document.qualification.progress.type='retention_unavailable',
    e=>e.pages.push({type:'rejected_document',document:{status:'rejected',rejection:{type:'execution-rejected',reason:'result-stale-basis'}}}),
    e=>e.pages[0].document.interpretation.originalCoverage.knownMinimum=1001,
    e=>e.pages[0].document.next_cursor=4,
    e=>e.pages[0].document.evidence_window.start=1,
    e=>e.pages[0].document.evidence_window.end=1,
    e=>e.pages[0].document.evidence_window.total=-1,
    e=>e.pages[0].document.items[0]=null,
    e=>e.pages[0].document.items[0].type='unknown',
    e=>e.pages[0].document.items[0].row_id='invalid',
    e=>delete e.pages[0].document.failures,
    e=>e.initial.document.rejection.detail.evidence.nextQuery.request.type='RUN',
    e=>{e.pages[0].document.items=[];e.pages[0].document.page_progress.rows=0;e.pages[0].document.next_cursor=0;e.initial.document.rejection.detail.evidence.preview=[];},
  ];
  for(const [index,change] of changes.entries()) {
    const envelope=partialPrefix();change(envelope);
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false,index);
    assert.equal(policy.report().partialEvidence,null,index);assert.equal(policy.beforeProvider(pinned).allow,false,index);
  }
  for(const key of ['host','epoch','root']) {
    const envelope=partialPrefix(),page=structuredClone(envelope.pages[0]);
    page.document.items=[];page.document.page_progress.rows=0;
    page.document.live[key]=key==='epoch'?2:'changed';envelope.pages.push(page);
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false,key);
  }
});
test('partial prefix preserves contiguous evidence counts across multiple pages',()=>{
  const envelope=partialPrefix(),first=envelope.pages[0].document,last=structuredClone(envelope.pages[0]);
  first.failures=[{type:'exact-reference',ref:'exact:v2:fixture-0',reason:'compiler-identity-unavailable'}];
  first.evidence_window.end=1;
  last.document.items=[];last.document.page_progress.rows=0;
  last.document.failures=structuredClone(first.failures);
  last.document.evidence_window={type:'MORE',start:1,end:2,total:3};
  envelope.pages.push(last);
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,true);
  assert.deepEqual(policy.report().partialEvidence.evidence,{received:2,total:3});
  assert.deepEqual(envelope.pages[0].document.failures,first.failures);
  last.document.evidence_window.total=4;
  const inconsistent=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(inconsistent.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false);
});
test('partial prefix occurrence identity retains distinct rows and rejects conflicting copies',()=>{
  const envelope=partialPrefix(),rows=envelope.pages[0].document.items;
  rows[1].ref=rows[0].ref;
  for(const [index,row] of rows.entries())row.row_id=`result-row:v1:00000000-0000-4000-8000-00000000000${index+1}`;
  envelope.initial.document.rejection.detail.evidence.preview=[structuredClone(rows[0])];
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,true);
  assert.equal(policy.report().partialEvidence.receivedRows,3);assert.equal(policy.report().partialEvidence.duplicatePreviewRows,1);
  envelope.initial.document.rejection.detail.evidence.preview[0].name='conflicting description';
  const conflict=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(conflict.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false);
});
test('missing row IDs use symbol or occurrence identity and conflicting preview descriptions fail closed',()=>{
  const symbol=partialPrefix();symbol.initial.document.rejection.detail.evidence.preview[0].name='conflicting name';
  const rejected=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(rejected.restoreReceivedResult(decodeEnvelope(resultEvent(symbol).content),10000).allow,false);
  // Existing pinned, sanitized canonical reference rows supply actual occurrence
  // shape. Missing IDs deliberately exercise the compatible optional-ID boundary.
  const native=JSON.parse(readFileSync(new URL('../../cli/src/test/resources/query-presentation-native.json',import.meta.url),'utf8')).cases.occurrences.reply.document;
  const references=partialPrefix(),page=references.pages[0].document,proof=references.initial.document.rejection.detail.evidence;
  page.items=structuredClone(native.items.slice(0,3));
  for(const row of page.items)delete row.row_id;
  assert.equal(new Set(page.items.map(row=>row.ref)).size,1,'Same target can have distinct source occurrences');
  page.question=structuredClone(native.question);page.live=structuredClone(native.live);
  proof.question=structuredClone(native.question);proof.preview=[structuredClone(page.items[0])];
  const valid=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(valid.restoreReceivedResult(decodeEnvelope(resultEvent(references).content),10000).allow,true);
  assert.equal(valid.report().partialEvidence.receivedRows,3);
  assert.equal(valid.report().partialEvidence.duplicatePreviewRows,1);
  proof.preview[0].occurrence.target.name='conflicting target description';
  const conflict=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(conflict.restoreReceivedResult(decodeEnvelope(resultEvent(references).content),10000).allow,false);
});
test('malformed terminal reason cannot enter partial interpretation and legitimate differing reasons remain eligible',()=>{
  for(const reason of ['result-stale-basis','result-unavailable','UNKNOWN',null,undefined]) {
    const envelope=partialPrefix();envelope.pages[0].document.qualification.progress.reason=reason;
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false,reason);
  }
  const envelope=partialPrefix();
  assert.equal(envelope.pages[0].document.qualification.progress.reason,'upstream-incomplete');
  assert.equal(envelope.initial.document.rejection.detail.originalCoverage.progress.reason,'checkpoint-capacity-exceeded');
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,true);
});
test('retained preview must match the ordered received prefix including its issued row IDs',()=>{
  for(const change of [
    p=>p[0].row_id='result-row:v1:00000000-0000-4000-8000-000000000004',
    p=>delete p[0].row_id,
    p=>p.reverse(),
    p=>p.push({...p.at(-1),row_id:'result-row:v1:00000000-0000-4000-8000-000000000004'}),
  ]) {
    const envelope=partialPrefix(),rows=envelope.pages[0].document.items;
    for(const [i,row] of rows.entries())row.row_id=`result-row:v1:00000000-0000-4000-8000-00000000000${i+1}`;
    const proof=envelope.initial.document.rejection.detail.evidence;proof.preview=structuredClone(rows);
    change(proof.preview);
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false);
    assert.equal(policy.report().partialEvidence,null);
  }
});
test('internally consistent malformed questions and contradictory evidence qualification cannot enable partial delivery',()=>{
  for(const change of [
    q=>q.from=true,q=>q.output=true,q=>q.completion.model='UNKNOWN',q=>q.from.offset=-1,
    q=>q.output.fields=['invalid'],q=>q.output.fields=['name','name'],q=>q.steps=[true],
    q=>q.steps=[{type:'related',relation:'references',expansion_scope:{type:'SOURCE_DOMAIN',source_sets:[],directory:null,source_policy:'production-and-test',generated_sources:'exclude'}}],
  ]) {
    const envelope=partialPrefix(),question=envelope.initial.document.rejection.detail.evidence.question;change(question);
    for(const page of envelope.pages)page.document.question=structuredClone(question);
    for(const saved of [false,true]) {
      const policy=makePolicy({...config(),evidenceOnlyDelivery:true}),decoded=decodeEnvelope(resultEvent(envelope).content);
      assert.equal((saved?policy.restoreReceivedResult(decoded,10000):policy.toolResult(decoded,10000)).allow,false);
      assert.equal(policy.report().partialEvidence,null);
    }
  }
  for(const change of [
    q=>q.limitations=[],q=>q.limitations=['static-model-unproven'],q=>q.limitations=['time-limit-reached','static-model-unproven'],
    q=>q.limitations=['byte-limit-reached','UNKNOWN','static-model-unproven'],
    q=>q.progress.reason='checkpoint-capacity-exceeded',q=>q.progress.extra='unknown',
  ]) {
    const envelope=partialPrefix();change(envelope.pages[0].document.qualification);
    const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
    assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000).allow,false);
  }
  const pageLimited=partialPrefix();pageLimited.pages[0].document.qualification.limitations.unshift('result-limit-reached');
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true});
  assert.equal(policy.restoreReceivedResult(decodeEnvelope(resultEvent(pageLimited).content),10000).allow,true,'Retained page selection may add its own result limit');
});
test('fresh partial prefix guard counts full instructions and body permits one request and forbids every further tool',()=>{
  const policy=makePolicy({...config(),evidenceOnlyDelivery:true,delivery:{...config().delivery,requests:3}}),guard=callbacks(policy);
  const envelope=partialPrefix();
  guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
  guard.emit('tool_result',{...resultEvent(envelope),isError:true});
  assert.equal(guard.aborted,0);assert.equal(policy.report().semanticRepliesObserved,1);
  assert.equal(summarizeGuard(guard.records.at(-1)).decision.reason,'DECLARED_RECEIVED_PARTIAL_EVIDENCE');
  const payload=guard.emit('before_provider_request',{payload:{...providerPayload(),input:[{type:'function_call_output',call_id:'call-1',output:JSON.stringify(envelope)}]}});
  assert.match(payload.instructions,/query remains rejected \(COMPLETION_UNPROVEN; checkpoint-capacity-exceeded\)/);
  assert.match(payload.instructions,/received evidence entries: 0\/3/);
  assert.equal(policy.report().requestObservations[0].payloadBytes,Buffer.byteLength(JSON.stringify(payload)));
  policy.modelUsage({input:100,output:50,totalTokens:150});
  assert.equal(policy.beforeProvider(pinned).reason,'HARNESS_BUDGET_LIMIT');
  for(const request of [{type:'RUN'},{type:'RESUME'},envelope.initial.document.rejection.detail.evidence.nextQuery.request]) {
    const p=makePolicy({...config(),evidenceOnlyDelivery:true});
    p.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000);
    assert.equal(p.toolCall('query_symbols',{request},'new-call').reason,'HARNESS_TOOL_BLOCKED');
    assert.equal(p.report().nativeOutcome,'REJECTED');
  }
  const bounded=makePolicy({...config(),evidenceOnlyDelivery:true,maximumProviderPayloadBytes:500}),blocked=callbacks(bounded);
  bounded.restoreReceivedResult(decodeEnvelope(resultEvent(envelope).content),10000);
  blocked.emit('before_provider_request',providerEvent());
  assert.equal(blocked.aborted,1);assert.equal(bounded.report().providerRequests,0);
  assert.equal(bounded.report().outcome,'HARNESS_BUDGET_LIMIT');
  assert.equal(bounded.report().nativeOutcome,'REJECTED');
});
test('actual shared-client delivered complete and qualified preserve initial semantics and count physical RPCs separately',()=>{
  for(const [name,nativeOutcome,finalOutcome] of [['complete','EXHAUSTIVE_RESULT','FINAL_ANSWER'],['qualified','QUALIFIED_RESULT','QUALIFIED_ANSWER']]) {
    const {policy,guard,fixture}=deliveredCase(name);
    assert.equal(guard.aborted,0);assert.equal(policy.report().phase,'DELIVERY');
    assert.equal(policy.report().nativeOutcome,nativeOutcome);
    assert.equal(policy.report().modelProposals.length,1);assert.equal(policy.report().semanticRepliesObserved,1);
    const delivery=policy.report().queryDeliveries[0];
    assert.equal(delivery.stop,'DELIVERED');assert.equal(delivery.rpcCount,fixture.result.delivery.rpc_count);
    assert.equal(delivery.hostFailureExpected,fixture.hostFailure);
    assert.equal(policy.report().modelProposals[0].hostIsErrorObserved,fixture.hostFailure);
    assert.deepEqual(delivery.canonicalOutcomes.map(r=>r.type),[fixture.result.initial,...fixture.result.pages].map(r=>r.type));
    if(name==='qualified')assert.deepEqual(policy.report().qualification,fixture.result.initial.document.qualification);
    assert.equal(policy.beforeProvider(pinned).allow,true);policy.modelUsage({input:17000,output:100,totalTokens:17100});
    policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Bounded interpretation of delivered evidence.'}]});
    assert.equal(policy.report().outcome,finalOutcome);assert.equal(policy.report().requiredEvidenceVerified,false);
    const second=deliveredCase(name);assert.equal(second.policy.toolCall('query_symbols',{request:{type:'RUN'}},'rerun').allow,false);
  }
});
test('actual shared-client delivered rejection proof stays rejected and cannot trigger another retained read',()=>{
  const {policy,guard,fixture}=deliveredCase('rejectedProof',{expectedSemanticCalls:2});
  assert.equal(guard.aborted,1);assert.equal(policy.report().outcome,'INTENTIONAL_REJECTION');
  assert.equal(policy.report().semanticRejection.code,'COMPLETION_UNPROVEN');
  assert.equal(policy.report().semanticRejection.knownMinimum,43);assert.equal(policy.report().exhaustiveEvidence,false);
  assert.equal(policy.report().queryDeliveries[0].hostFailureExpected,true);
  assert.equal(policy.report().queryDeliveries[0].rpcCount,3);assert.equal(policy.report().semanticRepliesObserved,1);
  assert.equal(policy.beforeProvider(pinned).allow,false);
  const opted=deliveredCase('rejectedProof',{evidenceOnlyDelivery:true});
  assert.equal(opted.policy.report().phase,'DELIVERY');
  assert.equal(opted.policy.toolCall('query_symbols',{request:fixture.result.initial.document.rejection.detail.evidence.nextQuery.request},'duplicate-proof').allow,false);
});
test('actual shared-client blockers cancellation and lost-byte initial remain finite delivery failures with zero semantic reruns',()=>{
  for(const name of ['unavailable','expired','budgetIncrease','readBudgetIncrease','cancelled','cancelledAfterPage','lostDelivery','deadline','byteLimitWithoutInitial']) {
    const {policy,guard,fixture}=deliveredCase(name,{expectedSemanticCalls:2}),report=policy.report();
    assert.equal(report.outcome,'CLIENT_DELIVERY_BLOCKED',name);assert.equal(guard.aborted,1);
    assert.equal(report.queryDeliveries[0].stop,fixture.result.delivery.stop);
    assert.equal(report.queryDeliveries[0].rpcCount,fixture.result.delivery.rpc_count);
    assert.equal(report.queryDeliveries[0].hostFailureExpected,true);
    assert.equal(report.modelProposals[0].hostIsErrorObserved,true);
    assert.equal(report.exhaustiveEvidence,false);assert.equal(report.requiredEvidenceVerified,false);
    assert.equal(policy.beforeProvider(pinned).allow,false);
    assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN'}},'rerun').allow,false);
    assert.equal(policy.toolCall('query_symbols',{request:{type:'RESUME'}},'resume').allow,false);
    if(name==='expired')assert.equal(report.queryDeliveries[0].canonicalOutcomes.at(-1).rejection.reason,'result-unavailable');
    if(name==='unavailable'||name==='budgetIncrease')assert.deepEqual(report.queryDeliveries[0].canonicalOutcomes.at(-1).qualification,fixture.result.pages.at(-1).document.qualification);
    if(name==='byteLimitWithoutInitial') {
      assert.equal(report.nativeOutcome,'UNOBSERVED');assert.equal(report.semanticRepliesObserved,0);
      assert.equal(report.queryDeliveries[0].originalOutcome,'complete');
    }
  }
});
test('shared-client saved delivery continues only its existing received result and rejects incompatible envelopes',()=>{
  for(const name of ['complete','qualified']) {
    const policy=makePolicy({...config(),delivery:{...config().delivery,reportedTokens:60000}});
    const envelope=decodeEnvelope(resultEvent(deliveryCases[name].result).content);
    assert.equal(policy.restoreReceivedResult(envelope,10000).allow,true);
    assert.equal(policy.report().phase,'DELIVERY');assert.equal(policy.report().semanticRepliesObserved,0);
    assert.equal(policy.report().deliveryPhysicalRpcCountReported,0);
    assert.equal(policy.report().savedDeliveryPhysicalRpcCountReported,deliveryCases[name].result.delivery.rpc_count);
    assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN'}},'retry').allow,false);
  }
  const failed=makePolicy(config());
  assert.equal(failed.restoreReceivedResult(decodeEnvelope(resultEvent(deliveryCases.lostDelivery.result).content),2000).reason,'CLIENT_DELIVERY_BLOCKED');
  assert.equal(decodeEnvelope(resultEvent(deliveryCases.lostDelivery.result).content).type,'query_delivery','failed RPC count can exceed 1 + pages');
  for(const change of [r=>r.delivery.stop='UNKNOWN',r=>r.delivery.rpc_count=0,r=>r.pages.push(structuredClone(r)),r=>r.initial=null]) {
    const value=structuredClone(deliveryCases.complete.result);change(value);
    assert.deepEqual(decodeEnvelope(resultEvent(value).content),{type:'invalid',reason:'INVALID_OUTER_SHAPE'});
  }
});

test('record failure aborts provider admission and blocks subsequent tool execution',()=>{
  const policy=makePolicy(config()),guard=callbacks(policy,{type:'failure',failure:{stage:'RECORD_WRITE',reason:'IO_FAILED'}});
  guard.emit('before_provider_request',providerEvent());
  assert.equal(guard.aborted,1);
  assert.equal(policy.report().outcome,'HARNESS_OBSERVATION_INVALID');
  assert.deepEqual(guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'unsafe',input:{request:{type:'RUN'}}}),{block:true,reason:'HARNESS_OBSERVATION_INVALID'});
  assert.equal(guard.aborted,2);
  assert.equal(policy.report().nativeRepliesObserved,0);
});

test('optional raw finite bounds reject explicit null and coercible unknown values',()=>{
  for(const key of ['maximumProviderPayloadBytes','maximumProviderRequests','maximumProviderResponseBytes','maximumReportedTokens']) {
    for(const value of [null,'3',false,0,1.5,Infinity]) assert.throws(()=>new CasePolicy({...config(),[key]:value}));
  }
  assert.equal(new CasePolicy(config()).report().bounds.maximumReportedTokens,55000);
});


test('compact production text keeps canonical SDK details and visible-byte accounting in the actual guard',()=>{
  const source=readFileSync(new URL('../../cli/src/main/js/query-delivery.mjs',import.meta.url),'utf8');
  const present=runInNewContext(source+';queryPresentation',{Buffer});
  for(const [name,expectedOutcome] of [['complete','RUNNING'],['qualified','RUNNING'],['rejectedProof','INTENTIONAL_REJECTION']]) {
    const envelope=deliveryCases[name].result,text=present(envelope);
    assert.notEqual(text,JSON.stringify(envelope));
    assert.equal(decodeEnvelope([{type:'text',text}]).type,'invalid');
    assert.deepEqual(decodeEnvelope([{type:'text',text}],'query_symbols',envelope),envelope);
    const policy=makePolicy(config()),guard=callbacks(policy);
    guard.emit('tool_call',{toolName:'query_symbols',toolCallId:'call-1',input:{request:{type:'RUN'}}});
    guard.emit('tool_result',{toolName:'query_symbols',toolCallId:'call-1',content:[{type:'text',text}],details:envelope,
      isError:name==='rejectedProof'});
    assert.equal(policy.report().outcome,expectedOutcome);
    assert.equal(guard.records.at(-1).textBytes,Buffer.byteLength(text));
    assert.equal(policy.report().semanticRepliesObserved,1);
    assert.equal(policy.report().queryDeliveries[0].rpcCount,envelope.delivery.rpc_count);
  }
});

test('malformed SDK detail cannot bypass closed canonical decoding or saved-result validation',()=>{
  const content=[{type:'text',text:JSON.stringify(negativeComplete)}];
  for(const invalid of [null,{type:'complete',document:{}},{type:'rejected',failure:'UNKNOWN'}]) {
    assert.deepEqual(decodeEnvelope(content,'query_symbols',invalid),{type:'invalid'});
  }
  const saved=deliveryCases.complete.result;
  const policy=makePolicy(config());
  assert.equal(policy.restoreReceivedResult(decodeEnvelope([{type:'text',text:'compact received result'}],
    'query_symbols',saved),23).allow,true);
  assert.equal(policy.report().semanticRepliesObserved,0);
  assert.equal(policy.report().modelProposals.length,0);
});
