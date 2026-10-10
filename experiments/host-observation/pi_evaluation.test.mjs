import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CasePolicy, decodeEnvelope, decodeUsage } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import { driveSession, runOwnedWorker } from './pi_evaluation_controller.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';

const config=()=>({name:'negative',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:80000,work:{reportedTokens:30000,requests:2,tools:2,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576});
const pinned={provider:'openai-codex',model:'gpt-6.1-sol',thinking:'high',payloadModel:'gpt-6.1-sol',effort:'high',payloadBytes:79339,toolsBytes:73716,instructionsBytes:1920,inputBytes:3357};
// Public synthetic facts and usage shapes from the 2026-10-10 pilot. No logs,
// source dumps, live handles, credential fields or personal paths are fixtures.
const denseRejected={type:'rejected_document',document:{status:'rejected',rejection:{type:'COMPLETION_UNPROVEN',detail:{coverage:{type:'QUALIFIED',knownMinimum:56,limitations:['BYTE_LIMIT_REACHED'],progress:{type:'terminal_incomplete',reason:'checkpoint-capacity-exceeded'}},policyProgress:{type:'EVIDENCE_ONLY'},evidence:{nextQuery:{request:{type:'READ_RESULT',result:'synthetic-retained-result',output:{type:'OCCURRENCES'},cursor:0}}}}},next_action:'report_failure'}};
const negativeComplete={type:'complete',document:{status:'complete',items:[],coverage:{exhaustive:true},invocation:{stop:{type:'COMPLETED'}}}};

test('recorded terminal dense rejection stops the next provider request immediately',()=>{
  const policy=new CasePolicy({...config(),name:'positive',expectedSemanticCalls:2,work:{...config().work,reportedTokens:60000,requests:3}});
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:17199,cacheRead:0,cacheWrite:0,output:92,totalTokens:17291});
  policy.toolResult(negativeComplete);
  assert.equal(policy.beforeProvider(pinned).allow,true);
  policy.modelUsage({input:1011,cacheRead:17024,cacheWrite:0,output:110,totalTokens:18145});
  policy.toolResult(denseRejected);
  assert.deepEqual(policy.beforeProvider(pinned),{allow:false,reason:'INTENTIONAL_REJECTION',phase:'STOPPED'});
});
test('recorded 30k negative work target cannot silently consume its separate final allowance',()=>{
  const policy=new CasePolicy(config());
  assert.equal(policy.beforeProvider({...pinned,inputBytes:304,payloadBytes:76286}).allow,true);
  policy.modelUsage({input:17139,cacheRead:0,cacheWrite:0,output:89,totalTokens:17228});
  policy.toolResult(negativeComplete);
  assert.equal(policy.beforeProvider(pinned).allow,true,'unchanged measured declaration/context fits the explicitly separate 25k delivery allowance');
});

function callbacks(policy) {
  const handlers=new Map(),records=[];let aborted=0;
  const pi={on:(name,handler)=>handlers.set(name,handler),setActiveTools:()=>{},getActiveTools:()=>['query_symbols','check_diagnostics','health_check'],getThinkingLevel:()=> 'high'};
  const ctx={model:{provider:'openai-codex',id:'gpt-6.1-sol'},abort:()=>{aborted++;}};
  evaluationGuard(policy,value=>records.push(value))(pi);
  return {emit:(name,event)=>handlers.get(name)(event,ctx),records,get aborted(){return aborted;}};
}
const providerEvent=()=>({payload:{model:'gpt-6.1-sol',reasoning:{effort:'high'},tools:[],instructions:'unchanged synthetic instructions',input:[]}});
const resultEvent=envelope=>({toolName:'query_symbols',toolCallId:'call-1',content:[{type:'text',text:JSON.stringify(envelope)}]});

test('actual guard aborts in tool_result before any provider continuation or changed-scope tool',()=>{
  const policy=new CasePolicy({...config(),name:'positive',expectedSemanticCalls:2});const guard=callbacks(policy);
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
  const positive=new CasePolicy({...config(),name:'positive'}),negative=new CasePolicy(config());
  positive.beforeProvider(pinned);positive.modelUsage({input:1000,cacheRead:30000,cacheWrite:0,output:9000,reasoning:100,totalTokens:40000});
  assert.equal(positive.report().outcome,'HARNESS_BUDGET_LIMIT');
  assert.equal(negative.beforeProvider(pinned).allow,true);
  assert.deepEqual(negative.report().usage,{input:0,cacheRead:0,cacheWrite:0,output:0,reasoning:0,totalTokens:0});
  assert.equal(positive.report().usage.totalTokens,40000);
  assert.equal(positive.report().usage.reasoning,100);
});

test('preflight declaration overhead and insufficient final reserve are harness budget limits',()=>{
  const oversized=new CasePolicy(config());assert.equal(oversized.beforeProvider({...pinned,toolsBytes:90000}).reason,'HARNESS_BUDGET_LIMIT');
  const small=new CasePolicy({...config(),delivery:{...config().delivery,reportedTokens:18000}});
  small.beforeProvider(pinned);small.modelUsage({input:17139,output:89,totalTokens:17228});small.toolResult(negativeComplete,2141);
  assert.equal(small.beforeProvider(pinned).reason,'HARNESS_BUDGET_LIMIT');
  assert.equal(small.report().nativeOutcome,'EXHAUSTIVE_RESULT');assert.equal(small.report().finalAnswer,false);
});

test('evidence-only opt-in admits exact READ_RESULT but rejects RUN RESUME and changed grants',()=>{
  const next=denseRejected.document.rejection.detail.evidence.nextQuery.request;
  for(const request of [{type:'RUN',scope:'changed'},{type:'RESUME'}, {...next,cursor:1},{...next,budget:{maxResults:2000}}]) {
    const policy=new CasePolicy({...config(),evidenceOnlyDelivery:true});policy.toolResult(denseRejected);
    assert.equal(policy.toolCall('query_symbols',{request},'blocked').allow,false);
    assert.equal(policy.report().nativeRepliesObserved,0);
  }
  const policy=new CasePolicy({...config(),evidenceOnlyDelivery:true});policy.toolResult(denseRejected);
  assert.equal(policy.toolCall('query_symbols',{request:next},'read').allow,true);
  policy.toolResult(negativeComplete,0,'read');
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Qualified rejection and delivered evidence.'}]});
  assert.equal(policy.report().outcome,'INTENTIONAL_REJECTION');assert.equal(policy.report().finalAnswer,true);
});

test('final text and exhaustive native coverage never manufacture oracle row/evidence verification',()=>{
  const policy=new CasePolicy(config());policy.toolResult(negativeComplete);
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
  const policy=new CasePolicy(config());policy.toolResult(negativeComplete);const guard=callbacks(policy);
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
  const model=new CasePolicy(config());assert.equal(model.beforeProvider({...pinned,model:'other'}).reason,'HARNESS_MODEL_MISMATCH');
  const effort=new CasePolicy(config());assert.equal(effort.beforeProvider({...pinned,effort:'medium'}).reason,'HARNESS_MODEL_MISMATCH');
});

test('received-result policy preserves qualification and forbids all new semantic work',()=>{
  const policy=new CasePolicy(config());assert.equal(policy.restoreReceivedResult(negativeComplete,2141).allow,true);
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
});

test('evidence-only delivery preserves JSON meaning while exact scope cursor and grants stay pinned',()=>{
  const policy=new CasePolicy({...config(),evidenceOnlyDelivery:true});policy.toolResult(denseRejected);
  const original=denseRejected.document.rejection.detail.evidence.nextQuery.request;
  const reordered={cursor:original.cursor,output:original.output,result:original.result,type:original.type};
  assert.equal(policy.toolCall('query_symbols',{request:reordered},'read').allow,true);
});

test('model response beyond its reserve remains harness budget limit even if final text arrived',()=>{
  const policy=new CasePolicy(config());policy.restoreReceivedResult(negativeComplete,2141);policy.beforeProvider(pinned);
  policy.modelUsage({input:17000,cacheRead:0,output:9000,totalTokens:26000});
  policy.assistantEnded({stopReason:'stop',content:[{type:'text',text:'Model final text received beyond the declared bound.'}]});
  const report=policy.report();assert.equal(report.outcome,'HARNESS_BUDGET_LIMIT');assert.equal(report.finalTextObserved,true);assert.equal(report.finalAnswer,false);
});

test('another tool cannot race past an unresolved semantic call and its eventual rejection',()=>{
  const policy=new CasePolicy(config());assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN'}},'first').allow,true);
  assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN',scope:'other'}},'parallel').allow,false);
  assert.equal(policy.report().semanticRepliesObserved,0);
});

test('real guard callbacks deliver recorded negative final answer under its separate allowance',()=>{
  const policy=new CasePolicy(config()),guard=callbacks(policy);
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
  const policy=new CasePolicy(config());assert.equal(policy.toolCall('health_check',{},'health').allow,true);
  assert.equal(policy.toolResult(decodeEnvelope([{type:'text',text:'{"type":"complete","document":{"healthy":true}}'}],'health_check'),60,'health').allow,true);
  assert.equal(policy.report().nativeOutcome,'UNOBSERVED');assert.equal(policy.report().semanticRepliesObserved,0);
  policy.toolResult(negativeComplete);assert.equal(policy.toolCall('health_check',{},'extra').allow,false);
});
