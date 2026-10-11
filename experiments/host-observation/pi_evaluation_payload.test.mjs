import test from 'node:test';
import assert from 'node:assert/strict';
import { CasePolicy, decodeEnvelope, Outcome } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import negativeDocument from './pi-fixtures/complete-document.json' with {type:'json'};

const config = () => ({name:'payload',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:80000,maximumProviderPayloadBytes:1048576,work:{reportedTokens:30000,requests:2,tools:2,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576});
const fresh = () => ({model:'gpt-6.1-sol',store:false,stream:true,instructions:'Fixed instructions',input:[{role:'user',content:[{type:'input_text',text:'Fixed request'}]}],reasoning:{effort:'high',summary:'auto'},text:{verbosity:'low'},include:['reasoning.encrypted_content'],tool_choice:'auto',parallel_tool_calls:true});
function boundary(policy,payload) {
  const handlers = new Map(),records=[];
  let aborts=0;
  evaluationGuard(policy,value=>records.push(value))({on:(name,handler)=>handlers.set(name,handler),getThinkingLevel:()=> 'high'});
  const result=handlers.get('before_provider_request')({payload},{model:{provider:'openai-codex',id:'gpt-6.1-sol'},abort:()=>aborts++});
  return {result,aborts,records};
}

test('uncalibrated full fresh input is rejected before network intent',()=>{
  const policy=new CasePolicy(config());
  const {aborts}=boundary(policy,fresh());
  assert.equal(aborts,1);
  assert.equal(policy.report().outcome,'HARNESS_INPUT_BOUND_UNAVAILABLE');
});

test('unsupported provider input cannot pass a known text calibration ceiling',()=>{
  const policy=new CasePolicy(config()),payload=fresh();
  payload.input=[{role:'user',content:[{type:'input_image',image_url:'https://fixture.invalid/image.png'}]}];
  const {aborts}=boundary(policy,payload);
  assert.equal(aborts,1);
  assert.equal(policy.report().outcome,'HARNESS_PAYLOAD_UNSUPPORTED');
});

// These offline source observations are explicitly synthetic test inputs. The
// product verifies bytes/identity; it does not manufacture a provider receipt.
import { createHash } from 'node:crypto';
import { inspectProviderPayload, verifyInputCalibration } from './pi_evaluation_payload.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const tool = () => ({type:'function',name:'query_symbols',description:'Fixed semantic tool',parameters:{type:'object',properties:{request:{type:'object'}},required:['request']},strict:null});
const receipt = (request,{input=400,cacheRead=600,cacheWrite=0,ceiling=1200}={}) => Buffer.from(JSON.stringify({type:'FULL_PAYLOAD_CALIBRATION',provider:'openai-codex',model:'gpt-6.1-sol',request,usage:{input,cacheRead,cacheWrite,output:100,reasoning:50,totalTokens:input+cacheRead+cacheWrite+100},calibratedInputCeiling:ceiling,method:'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE',qualification:'CALIBRATION_NOT_TOKENIZER_PROOF'}));
function calibratedPolicy(payload,settings=config(),options) {
  const policy=new CasePolicy(settings),bytes=receipt(payload,options),calibration=verifyInputCalibration(bytes,digest(bytes));
  assert.equal(calibration.type,'verified-input-calibration');
  assert.equal(policy.registerInputCalibration(calibration).allow,true);
  return policy;
}
const received = () => ({type:'qualified',document:{status:'qualified',coverage:{exhaustive:false},items:[],qualification:{knownMinimum:0,limitations:['byte-limit-reached'],progress:{type:'terminal_incomplete',reason:'checkpoint-capacity-exceeded'}}}});

test('verified fresh admission covers complete instructions tools and input and preserves originals',()=>{
  const payload=fresh();payload.tools=[tool()];
  const original=structuredClone(payload),policy=calibratedPolicy(payload);
  const {result,aborts,records}=boundary(policy,payload);
  assert.equal(aborts,0);assert.equal(result.max_output_tokens,2000);
  assert.deepEqual(payload,original);assert.deepEqual(result,{...original,max_output_tokens:2000});
  const observation=policy.report().requestObservations[0];
  assert.equal(observation.inputEstimate,1200);assert.equal(observation.required,3200);
  assert.equal(observation.boundQualification,'CALIBRATION_NOT_TOKENIZER_PROOF');
  assert.equal(observation.topLevelTools,1);assert.equal(observation.inputItems,1);
  assert.equal(JSON.stringify(records).includes('Fixed semantic tool'),false);
  assert.equal(JSON.stringify(records).includes('Fixed instructions'),false);
});

test('changed fresh context instructions top-level tools and restored declarations cannot reuse a calibration',()=>{
  const payload=fresh();payload.tools=[tool()];
  const variants=[
    value=>value.instructions+=' changed',
    value=>value.input[0].content[0].text+=' changed',
    value=>value.tools[0].description+=' changed',
    value=>value.input.push({type:'additional_tools',role:'developer',tools:[tool()]}),
  ];
  for(const mutate of variants) {
    const policy=calibratedPolicy(payload),changed=structuredClone(payload);mutate(changed);
    assert.equal(boundary(policy,changed).aborts,1);
    assert.equal(policy.report().outcome,'HARNESS_INPUT_BOUND_UNAVAILABLE');
    assert.equal(policy.report().requestObservations[0].allow,false);
  }
});

test('resumed tool text and restored declarations bind one complete projection',()=>{
  const payload=fresh();payload.input.push({type:'additional_tools',role:'developer',tools:[tool()]},{type:'function_call',call_id:'received',name:'query_symbols',arguments:'{"request":{"type":"RUN"}}'},{type:'function_call_output',call_id:'received',output:JSON.stringify(received())});
  const policy=calibratedPolicy(payload);
  assert.equal(policy.restoreReceivedResult(received(),Buffer.byteLength(JSON.stringify(received()))).allow,true);
  const original=structuredClone(payload),observed=boundary(policy,payload);
  assert.equal(observed.aborts,0);assert.deepEqual(payload,original);
  assert.equal(policy.report().requestObservations[0].required,3200);
  assert.equal(policy.report().requestObservations[0].restoredDeclarations,1);
  assert.ok(policy.report().requestObservations[0].declarationInputBytes>0);
  assert.equal(observed.result.input.filter(item=>item.type==='function_call_output').length,1);
  assert.equal(policy.report().requestObservations[0].inputBytes,Buffer.byteLength(JSON.stringify(payload.input)));
  assert.equal(policy.report().toolTextBytes,Buffer.byteLength(JSON.stringify(received())));
  const changed=structuredClone(payload);changed.input[1].tools[0].description+=' changed';
  const denied=calibratedPolicy(payload);denied.restoreReceivedResult(received(),0);
  assert.equal(boundary(denied,changed).aborts,1);
});

test('cached measured floor changes admission for a different fully calibrated request',()=>{
  const first=fresh(),second=fresh();second.instructions='Independently calibrated next instructions';
  const settings={...config(),work:{...config().work,reportedTokens:7600}};
  const policy=calibratedPolicy(first,settings,{ceiling:4000});
  const secondReceipt=receipt(second);
  assert.equal(policy.registerInputCalibration(verifyInputCalibration(secondReceipt,digest(secondReceipt))).allow,true);
  assert.equal(boundary(policy,first).aborts,0);
  assert.equal(policy.modelUsage({input:200,cacheRead:2300,cacheWrite:500,output:100,totalTokens:3100}).allow,true);
  assert.equal(boundary(policy,second).aborts,1);
  const observation=policy.report().requestObservations[1];
  assert.equal(observation.measuredInputFloor,3000);assert.equal(observation.inputEstimate,3000);
  assert.equal(observation.required,5000);assert.equal(observation.remaining,4500);
  assert.equal(observation.allow,false);assert.equal(policy.report().outcome,'HARNESS_BUDGET_LIMIT');
  // Without the measured floor, ceiling1200 + reserve2000 would fit remaining4500.
  const other=calibratedPolicy(first,config(),{ceiling:4000});boundary(other,first);other.modelUsage({input:200,cacheRead:2300,cacheWrite:500,output:100,totalTokens:3100});
  assert.equal(boundary(other,second).aborts,1);assert.equal(other.report().outcome,'HARNESS_INPUT_BOUND_UNAVAILABLE');
});

test('dynamic negative result lacks a next-request input bound despite unused delivery allowance',()=>{
  // Only the first full request has an explicitly synthetic offline receipt.
  // This regression proves denial; it is not dynamic E2E or provider readiness.
  const first=fresh();first.tools=[tool()];
  const callId='dynamic-negative-call-1',argumentsValue={request:{type:'RUN'}};
  const content=[{type:'text',text:JSON.stringify({type:'complete',document:negativeDocument})}];
  const envelope=decodeEnvelope(content);
  assert.equal(envelope.type,'complete');assert.equal(envelope.document.coverage.exhaustive,true);
  assert.deepEqual(envelope.document.items,[]);
  const next=structuredClone(first);
  next.input.push({type:'function_call',call_id:callId,name:'query_symbols',arguments:JSON.stringify(argumentsValue)},
    {type:'function_call_output',call_id:callId,output:content[0].text});
  const observation=body=>({provider:'openai-codex',model:'gpt-6.1-sol',thinking:'high',
    payload:inspectProviderPayload({...body,max_output_tokens:2000},1048576)});
  const initial=observation(first),delivery=observation(next);
  assert.equal(initial.payload.type,'admitted-payload');assert.equal(delivery.payload.type,'admitted-payload');
  assert.notEqual(initial.payload.payloadSha256,delivery.payload.payloadSha256);
  const completedWork=()=>{
    const policy=calibratedPolicy(first);
    assert.deepEqual(policy.beforeProvider(initial),{allow:true,reason:'ADMITTED',phase:'WORK'});
    assert.deepEqual(policy.modelUsage({input:400,cacheRead:600,cacheWrite:0,output:100,reasoning:50,totalTokens:1100},'toolUse'),
      {allow:true,reason:'ACCOUNTED',phase:'WORK'});
    assert.deepEqual(policy.toolCall('query_symbols',argumentsValue,callId),{allow:true,reason:'ADMITTED',phase:'WORK'});
    policy.adapterStarted(callId);
    assert.deepEqual(policy.toolResult(envelope,Buffer.byteLength(content[0].text),callId,false),
      {allow:true,reason:'RESULT_RECEIVED',phase:'DELIVERY'});
    const report=policy.report();
    assert.equal(report.nativeOutcome,'EXHAUSTIVE_RESULT');assert.equal(report.nativeRepliesObserved,1);
    assert.equal(report.modelProposals[0].callId,callId);assert.equal(report.modelProposals[0].adapterStartObserved,true);
    assert.equal(report.toolTextBytes,Buffer.byteLength(content[0].text));
    assert.equal(report.phaseUsage.WORK.totalTokens,1100);assert.equal(report.phaseUsage.DELIVERY.totalTokens,0);
    assert.equal(report.bounds.delivery.reportedTokens-report.phaseUsage.DELIVERY.totalTokens,25000);
    return policy;
  };
  const policy=completedWork();
  assert.deepEqual(policy.beforeProvider(delivery),{allow:false,reason:Outcome.INPUT_BOUND_UNAVAILABLE,phase:'STOPPED'});
  const denied=policy.report().requestObservations[1];
  assert.equal(denied.phase,'DELIVERY');assert.equal(denied.remaining,25000);
  assert.equal(denied.inputEstimate,null);assert.equal(denied.required,null);assert.equal(denied.measuredInputFloor,1000);
  assert.equal(denied.payloadSha256,delivery.payload.payloadSha256);
  const lateBytes=receipt(next),lateCalibration=verifyInputCalibration(lateBytes,digest(lateBytes));
  assert.equal(lateCalibration.type,'verified-input-calibration');
  assert.deepEqual(policy.registerInputCalibration(lateCalibration),{allow:false,reason:Outcome.INPUT_BOUND_UNAVAILABLE,phase:'STOPPED'},
    'A stopped case preserves its first input-bound failure');
  const attemptedLateRegistration=completedWork();
  assert.deepEqual(attemptedLateRegistration.registerInputCalibration(lateCalibration),{allow:false,reason:Outcome.INVALID,phase:'STOPPED'});
  assert.deepEqual(attemptedLateRegistration.beforeProvider(delivery),{allow:false,reason:Outcome.INVALID,phase:'STOPPED'});
  assert.deepEqual(attemptedLateRegistration.registerInputCalibration(lateCalibration),{allow:false,reason:Outcome.INVALID,phase:'STOPPED'});
  assert.equal(attemptedLateRegistration.report().phaseUsage.DELIVERY.totalTokens,0);
});

test('declaration total and complete payload byte ceilings deny without trimming',()=>{
  const payload=fresh();payload.tools=[tool()];payload.input.push({type:'additional_tools',role:'developer',tools:[tool()]});
  const details=inspectProviderPayload(payload,1048576),total=details.toolsBytes+details.instructionsBytes+details.declarationInputBytes;
  const declarationPolicy=calibratedPolicy(payload,{...config(),declarationByteCeiling:total-1});
  assert.equal(boundary(declarationPolicy,payload).aborts,1);assert.equal(declarationPolicy.report().outcome,'HARNESS_BUDGET_LIMIT');
  const capped={...payload,max_output_tokens:2000},bytes=Buffer.byteLength(JSON.stringify(capped));
  const bytePolicy=calibratedPolicy(payload,{...config(),maximumProviderPayloadBytes:bytes-1});
  assert.equal(boundary(bytePolicy,payload).aborts,1);assert.equal(bytePolicy.report().outcome,'HARNESS_BUDGET_LIMIT');
  const exactPolicy=calibratedPolicy(payload,{...config(),maximumProviderPayloadBytes:bytes,declarationByteCeiling:total});
  assert.equal(boundary(exactPolicy,payload).aborts,0);
});

test('unknown structures sparse inputs and unrecognized replay items fail closed',()=>{
  const mutations=[value=>value.extra='unknown',value=>value.input.push(null),value=>value.input.push({type:'additional_tools',role:'user',tools:[tool()]}),value=>value.input.push({type:'function_call_output',call_id:'id',output:{text:'unknown'}}),value=>value.input[0].content.push({type:'refusal',refusal:'unknown'}),value=>value.tools=[{type:'web_search'}],value=>value.input=new Array(1),value=>value.input.push({type:'reasoning',id:'rs',summary:[],unknown:'opaque'})];
  for(const mutate of mutations) {
    const payload=fresh();mutate(payload);
    const policy=new CasePolicy(config()),{aborts}=boundary(policy,payload);
    assert.equal(aborts,1);assert.ok(['HARNESS_PAYLOAD_UNSUPPORTED','HARNESS_OBSERVATION_INVALID'].includes(policy.report().outcome));
  }
});

test('offline calibration must verify receipt bytes usage and full request before registration',()=>{
  const bytes=receipt(fresh());
  assert.equal(verifyInputCalibration(bytes,'0'.repeat(64)).type,'rejected');
  for(const mutate of [value=>value.usage.totalTokens++,value=>value.calibratedInputCeiling=999,value=>value.method='CALLER_ASSERTION',value=>value.qualification='EXACT_TOKENIZER_PROOF',value=>value.request.input.push({type:'unknown'})]) {
    const value=JSON.parse(bytes);mutate(value);const bad=Buffer.from(JSON.stringify(value));
    assert.equal(verifyInputCalibration(bad,digest(bad)).type,'rejected');
  }
  const policy=new CasePolicy(config());
  assert.equal(policy.registerInputCalibration({...verifyInputCalibration(bytes,digest(bytes))}).allow,false,'Copying primitive facts discards their verification capability');
  assert.equal(policy.report().outcome,'HARNESS_OBSERVATION_INVALID');
  const oversized=new CasePolicy({...config(),inputTokenCeiling:1100});
  assert.equal(oversized.registerInputCalibration(verifyInputCalibration(bytes,digest(bytes))).allow,false);
});

test('output reserve caps the real returned body and over-reserve usage retains finite rejection',()=>{
  const payload=fresh(),settings={...config(),work:{...config().work,outputReserve:37}};
  const policy=calibratedPolicy(payload,settings),{result}=boundary(policy,payload);
  assert.equal(result.max_output_tokens,37);
  assert.equal(policy.modelUsage({input:1000,output:38,totalTokens:1038}).allow,false);
  assert.equal(policy.report().outcome,'HARNESS_BUDGET_LIMIT');
  assert.equal(boundary(policy,payload).aborts,1);
  assert.equal(policy.report().outcome,'HARNESS_BUDGET_LIMIT');
});

test('official model context maximum remains denial evidence under existing allowance',()=>{
  const policy=new CasePolicy(config());boundary(policy,fresh());
  const observation=policy.report().requestObservations[0];
  assert.equal(observation.modelContextWindow,1050000);assert.equal(observation.modelContextWindowRequired,1052000);
  assert.equal(observation.required,null);assert.equal(observation.inputEstimate,null);assert.equal(observation.allow,false);
});

test('plans accept only bounded absolute digest-pinned offline calibration references',()=>{
  const plan={piPackageRoot:'/fixture/pi',kastAdapter:'/fixture/adapter',workspaceRoot:'/fixture/workspace',outputRoot:'/fixture/output',authPath:'/fixture/auth',modelsStorePath:'/fixture/models',kastAdapterSha256:'0'.repeat(64),piVersion:'1.0.2',cases:[{...config(),mode:'fresh',prompt:'Fixed',wallSeconds:10,inputCalibrations:[{type:'OFFLINE_FULL_PAYLOAD_CALIBRATION',path:'/fixture/receipt',sha256:'1'.repeat(64)}]}]};
  assert.equal(validatePlan(plan),plan);
  for(const mutate of [value=>value.path='relative',value=>value.sha256='unproven',value=>value.inputTokens=1000]) {
    const changed=structuredClone(plan);mutate(changed.cases[0].inputCalibrations[0]);assert.throws(()=>validatePlan(changed));
  }
});


test('calibration overshoot preserves actual cached debits and permanently blocks rehabilitation',()=>{
  const payload=fresh(),policy=calibratedPolicy(payload);boundary(policy,payload);
  assert.equal(policy.modelUsage({input:200,cacheRead:2300,cacheWrite:500,output:100,totalTokens:3100}).reason,'HARNESS_INPUT_CALIBRATION_EXCEEDED');
  const report=policy.report();
  assert.equal(report.measuredInputFloor,3000);assert.equal(report.usage.totalTokens,3100);assert.equal(report.phaseUsage.WORK.totalTokens,3100);
  assert.equal(report.inputCalibrationFailure.calibratedInputCeiling,1200);assert.equal(report.inputCalibrationFailure.observedInput,3000);
  assert.equal(boundary(policy,payload).aborts,1);
  assert.equal(policy.modelUsage({input:0,output:0,totalTokens:0},'aborted').reason,'HARNESS_INPUT_CALIBRATION_EXCEEDED');
  assert.equal(policy.report().outcome,'HARNESS_INPUT_CALIBRATION_EXCEEDED');
  assert.equal(policy.registerInputCalibration(verifyInputCalibration(receipt(payload,{ceiling:4000}),digest(receipt(payload,{ceiling:4000})))).allow,false);
});

test('zero finalized usage cannot fabricate input accounting or replace finite failures',()=>{
  for(const stopReason of ['stop','error','aborted']) {
    const policy=calibratedPolicy(fresh());boundary(policy,fresh());
    const result=policy.modelUsage({input:0,output:0,totalTokens:0},stopReason);
    assert.equal(result.allow,false);assert.equal(policy.providerInFlight,false);
    assert.equal(result.reason,{stop:'HARNESS_OBSERVATION_INVALID',error:'MODEL_ERROR',aborted:'HARNESS_CANCELLED'}[stopReason]);
    assert.equal(boundary(policy,fresh()).aborts,1);assert.equal(policy.report().usage.totalTokens,0);
  }
  const denied=new CasePolicy(config());boundary(denied,fresh());
  assert.equal(denied.modelUsage({input:0,output:0,totalTokens:0},'aborted').reason,'HARNESS_INPUT_BOUND_UNAVAILABLE');
});

test('complete generated declarations parse within finite structural depth and reject over-depth input',()=>{
  const nested=levels=>{
    let value={type:'string'};
    for(let i=0;i<levels;i++)value={type:'object',properties:{value}};
    return value;
  };
  const body=fresh();
  body.tools=[{type:'function',name:'query_symbols',parameters:nested(16)}];
  assert.equal(inspectProviderPayload(body,1048576).type,'admitted-payload');
  body.tools[0].parameters=nested(33);
  assert.equal(inspectProviderPayload(body,1048576).type,'rejected');
});
