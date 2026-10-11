import test from 'node:test';
import assert from 'node:assert/strict';
import { CasePolicy, decodeEnvelope, Outcome } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import negativeDocument from './pi-fixtures/complete-document.json' with {type:'json'};

const config = () => ({name:'payload',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:80000,maximumProviderPayloadBytes:262144,maximumProviderRequests:3,work:{reportedTokens:30000,requests:2,tools:2,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576});
const fresh = () => ({model:'gpt-6.1-sol',store:false,stream:true,instructions:'Fixed instructions',input:[{role:'user',content:[{type:'input_text',text:'Fixed request'}]}],reasoning:{effort:'high',summary:'auto'},text:{verbosity:'low'},include:['reasoning.encrypted_content'],tool_choice:'auto',parallel_tool_calls:true});
function boundary(policy,payload) {
  const handlers = new Map(),records=[];
  let aborts=0;
  evaluationGuard(policy,value=>records.push(value))({on:(name,handler)=>handlers.set(name,handler),getThinkingLevel:()=> 'high'});
  const result=handlers.get('before_provider_request')({payload},{model:{provider:'openai-codex',id:'gpt-6.1-sol'},abort:()=>aborts++});
  return {result,aborts,records};
}

test('full fresh input admits exact bytes without an optional calibration',()=>{
  const policy=new CasePolicy(config()),payload=fresh();
  const {aborts,result}=boundary(policy,payload);
  assert.equal(aborts,0);
  const observation=policy.report().requestObservations[0];
  assert.equal(observation.payloadBytes,Buffer.byteLength(JSON.stringify(result)));
  assert.equal(observation.inputEstimate,Buffer.byteLength(JSON.stringify(result)));
  assert.equal(observation.required,Buffer.byteLength(JSON.stringify(result))+2000);
  assert.equal(observation.calibrationSourceSha256,null);
  assert.equal(observation.inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
  assert.equal(observation.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.equal(observation.boundMethod,'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS');
  assert.equal(policy.report().providerRequests,1);
  assert.equal(policy.report().backendOutputCapQualification,'UNQUALIFIED');
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
  assert.equal(observation.calibrationQualification,'CALIBRATION_NOT_TOKENIZER_PROOF');
  assert.equal(observation.inputEstimateMethod,'VERIFIED_FULL_PAYLOAD_EMPIRICAL_CALIBRATION');
  assert.equal(observation.inputEstimateQualification,'CALIBRATION_NOT_TOKENIZER_PROOF');
  assert.equal(observation.boundQualification,'POST_RESPONSE_TOKEN_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT');
  assert.equal(observation.topLevelTools,1);assert.equal(observation.inputItems,1);
  assert.equal(JSON.stringify(records).includes('Fixed semantic tool'),false);
  assert.equal(JSON.stringify(records).includes('Fixed instructions'),false);
});

test('changed whole payloads admit by exact bytes without claiming a calibration match',()=>{
  const payload=fresh();payload.tools=[tool()];
  const variants=[
    value=>value.instructions+=' changed',
    value=>value.input[0].content[0].text+=' changed',
    value=>value.tools[0].description+=' changed',
    value=>value.input.push({type:'additional_tools',role:'developer',tools:[tool()]}),
  ];
  for(const mutate of variants) {
    const policy=calibratedPolicy(payload),changed=structuredClone(payload);mutate(changed);
    const original=structuredClone(changed),observed=boundary(policy,changed);
    assert.equal(observed.aborts,0);assert.deepEqual(changed,original);
    const admission=policy.report().requestObservations[0];
    assert.equal(admission.allow,true);assert.equal(admission.calibrationSourceSha256,null);
    assert.equal(admission.inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
    assert.equal(admission.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
    assert.equal(admission.inputEstimate,Buffer.byteLength(JSON.stringify(observed.result)));
    assert.equal(admission.required,Buffer.byteLength(JSON.stringify(observed.result))+2000);
    assert.equal(admission.payloadBytes,Buffer.byteLength(JSON.stringify(observed.result)));
    assert.notEqual(admission.payloadSha256,inspectProviderPayload({...payload,max_output_tokens:2000},262144).payloadSha256);
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
  const changedPolicy=calibratedPolicy(payload);changedPolicy.restoreReceivedResult(received(),0);
  assert.equal(boundary(changedPolicy,changed).aborts,0);
  assert.equal(changedPolicy.report().requestObservations[0].calibrationSourceSha256,null);
  assert.equal(changedPolicy.report().bounds.maximumProviderRequests,1);
  changedPolicy.modelUsage({input:1000,output:100,totalTokens:1100},'toolUse');
  assert.equal(boundary(changedPolicy,changed).aborts,1);
  assert.equal(changedPolicy.report().outcome,Outcome.BUDGET);
  assert.equal(changedPolicy.report().providerRequests,1);
});

test('cached measured floor is diagnostic and cumulative actual usage stops after one-response overshoot',()=>{
  const first=fresh(),second=fresh();second.instructions='Independently calibrated next instructions';
  const settings={...config(),maximumReportedTokens:7600};
  const policy=calibratedPolicy(first,settings,{ceiling:4000});
  const secondReceipt=receipt(second);
  assert.equal(policy.registerInputCalibration(verifyInputCalibration(secondReceipt,digest(secondReceipt))).allow,true);
  assert.equal(boundary(policy,first).aborts,0);
  assert.equal(policy.modelUsage({input:200,cacheRead:2300,cacheWrite:500,output:100,totalTokens:3100}).allow,true);
  assert.equal(boundary(policy,second).aborts,0);
  const observation=policy.report().requestObservations[1];
  assert.equal(observation.measuredInputFloor,3000);assert.equal(observation.inputEstimate,3000);
  assert.equal(observation.required,5000);assert.equal(observation.remaining,4500);
  assert.equal(observation.allow,true,'An empirical estimate is not a future spend guarantee');
  assert.equal(policy.modelUsage({input:200,cacheRead:4201,output:100,totalTokens:4501},'toolUse').reason,Outcome.BUDGET);
  assert.equal(policy.report().usage.totalTokens,7601);
  assert.equal(policy.report().phaseUsage.WORK.totalTokens,7601);
  assert.equal(policy.report().bounds.maximumReportedTokens,7600);
  assert.equal(boundary(policy,second).aborts,1);
  assert.equal(policy.report().providerRequests,2);
  assert.equal(policy.report().tokenAccountingQualification,'POST_RESPONSE_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT');
});

test('fresh question dynamic canonical tool result and final answer complete without any prior receipt',()=>{
  const policy=new CasePolicy(config()),handlers=new Map(),records=[];
  let aborts=0,networkCalls=0;
  const networkTransport=()=>{networkCalls++;assert.fail('Pure callback qualification must never send a network request');};
  const context={model:{provider:'openai-codex',id:'gpt-6.1-sol'},abort:()=>aborts++};
  evaluationGuard(policy,value=>records.push(value))({on:(name,handler)=>handlers.set(name,handler),getThinkingLevel:()=> 'high'});
  const emit=(name,event)=>handlers.get(name)(event,context);
  const admitted=[];
  const request=payload=>{
    const priorAborts=aborts,original=structuredClone(payload);
    const body=emit('before_provider_request',{payload});
    assert.deepEqual(payload,original);
    if(aborts===priorAborts)admitted.push(body);
    return body;
  };
  const first=fresh();first.tools=[tool()];
  first.input[0].content[0].text='Are there any matching symbols?';
  request(first);assert.equal(aborts,0);
  // The ID and full result are constructed only after fresh admission, without
  // registering a request receipt or predicting the next serialized payload.
  const callId=`generated-after-admission-${admitted.length}`,argumentsValue={request:{type:'RUN'}};
  emit('message_end',{message:{role:'assistant',stopReason:'toolUse',content:[{type:'toolCall',id:callId,name:'query_symbols',arguments:argumentsValue}],usage:{input:400,cacheRead:600,output:100,reasoning:50,totalTokens:1100}}});
  assert.equal(emit('tool_call',{toolName:'query_symbols',input:argumentsValue,toolCallId:callId}),undefined);
  emit('tool_execution_start',{toolCallId:callId});
  const canonical={type:'complete',document:structuredClone(negativeDocument)};
  const output=JSON.stringify(canonical),content=[{type:'text',text:output}];
  assert.equal(decodeEnvelope(content).type,'complete');
  assert.equal(canonical.document.coverage.exhaustive,true);assert.deepEqual(canonical.document.items,[]);
  emit('tool_result',{toolName:'query_symbols',toolCallId:callId,content,isError:false});
  assert.equal(aborts,0);assert.equal(policy.report().phase,'DELIVERY');
  const next=structuredClone(first);
  next.input.push({type:'function_call',call_id:callId,name:'query_symbols',arguments:JSON.stringify(argumentsValue)},
    {type:'function_call_output',call_id:callId,output});
  const delivered=request(next);
  assert.equal(aborts,0);assert.equal(admitted.length,2);
  assert.deepEqual(delivered,{...next,max_output_tokens:2000});
  const deliveredOutput=delivered.input.find(item=>item.type==='function_call_output');
  assert.equal(deliveredOutput.call_id,callId);assert.equal(deliveredOutput.output,output);
  assert.deepEqual(JSON.parse(deliveredOutput.output),canonical);
  assert.equal(delivered.tools[0].description,first.tools[0].description);
  assert.equal(delivered.instructions,first.instructions);
  emit('message_end',{message:{role:'assistant',stopReason:'stop',content:[{type:'text',text:'No matching symbols were found in the complete result.'}],usage:{input:1600,cacheRead:100,output:100,totalTokens:1800}}});
  const report=policy.report();
  assert.equal(report.outcome,Outcome.FINAL_ANSWER);assert.equal(report.finalAnswer,true);
  assert.equal(report.nativeOutcome,'EXHAUSTIVE_RESULT');assert.equal(report.nativeRepliesObserved,1);
  assert.equal(report.exhaustiveRowsVerified,false,'A scripted reply does not establish a native oracle');
  assert.equal(report.modelProposals[0].callId,callId);assert.equal(report.modelProposals[0].adapterStartObserved,true);
  assert.equal(report.toolTextBytes,Buffer.byteLength(output));assert.equal(report.providerRequests,2);
  assert.equal(report.phaseUsage.WORK.totalTokens,1100);assert.equal(report.phaseUsage.DELIVERY.totalTokens,1800);
  assert.equal(report.usage.totalTokens,2900);assert.equal(report.backendOutputCapQualification,'UNQUALIFIED');
  assert.notEqual(report.requestObservations[0].payloadSha256,report.requestObservations[1].payloadSha256);
  for(let i=0;i<admitted.length;i++){
    assert.equal(report.requestObservations[i].payloadBytes,Buffer.byteLength(JSON.stringify(admitted[i])));
    assert.equal(report.requestObservations[i].calibrationSourceSha256,null);
    assert.equal(report.requestObservations[i].inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
    assert.equal(report.requestObservations[i].inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
    assert.equal(report.requestObservations[i].inputEstimate,Buffer.byteLength(JSON.stringify(admitted[i])));
    assert.equal(report.requestObservations[i].required,Buffer.byteLength(JSON.stringify(admitted[i]))+2000);
  }
  assert.equal(aborts,1,'Final-answer termination is the sole abort');
  assert.equal(networkCalls,0);assert.equal(typeof networkTransport,'function');
  assert.equal(JSON.stringify(records).includes(output),false);
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

test('whole serialized request admits exactly 256KiB and rejects the next byte before transport',()=>{
  for(const bytes of [262144,262145]) {
    const payload=fresh();payload.tools=[tool()];payload.input[0].content[0].text='';
    const fixed=Buffer.byteLength(JSON.stringify({...payload,max_output_tokens:2000}));
    payload.input[0].content[0].text='x'.repeat(bytes-fixed);
    const original=structuredClone(payload),policy=new CasePolicy(config());
    let transportCalls=0;
    const guarded=boundary(policy,payload);
    if(guarded.aborts===0)transportCalls++;
    assert.equal(Buffer.byteLength(JSON.stringify(guarded.result)),bytes);
    assert.deepEqual(payload,original);assert.deepEqual(guarded.result,{...original,max_output_tokens:2000});
    assert.equal(transportCalls,bytes===262144?1:0);
    assert.equal(guarded.aborts,bytes===262144?0:1);
    assert.equal(policy.report().providerRequests,bytes===262144?1:0);
    assert.equal(policy.report().outcome,bytes===262144?Outcome.RUNNING:Outcome.BUDGET);
    assert.equal(policy.report().bounds.maximumProviderPayloadBytes,262144);
    if(bytes===262144){
      assert.equal(policy.report().requestObservations[0].inputEstimate,262144);
      assert.equal(policy.report().requestObservations[0].required,264144);
      assert.equal(policy.report().requestObservations[0].allow,true,'The estimate does not control admission or prove spend');
    }
  }
});

test('plans accept only bounded absolute digest-pinned offline calibration references',()=>{
  const plan={piPackageRoot:'/fixture/pi',kastAdapter:'/fixture/adapter',workspaceRoot:'/fixture/workspace',outputRoot:'/fixture/output',authPath:'/fixture/auth',modelsStorePath:'/fixture/models',kastAdapterSha256:'0'.repeat(64),piVersion:'1.0.2',cases:[{...config(),mode:'fresh',prompt:'Fixed',wallSeconds:10,inputCalibrations:[{type:'OFFLINE_FULL_PAYLOAD_CALIBRATION',path:'/fixture/receipt',sha256:'1'.repeat(64)}]}]};
  assert.equal(validatePlan(plan),plan);
  for(const mutate of [value=>value.path='relative',value=>value.sha256='unproven',value=>value.inputTokens=1000]) {
    const changed=structuredClone(plan);mutate(changed.cases[0].inputCalibrations[0]);assert.throws(()=>validatePlan(changed));
  }
});


test('calibration overshoot preserves cached debits as a diagnostic without circular admission denial',()=>{
  const payload=fresh(),policy=calibratedPolicy(payload);boundary(policy,payload);
  assert.equal(policy.modelUsage({input:200,cacheRead:2300,cacheWrite:500,output:100,totalTokens:3100}).allow,true);
  const report=policy.report();
  assert.equal(report.measuredInputFloor,3000);assert.equal(report.usage.totalTokens,3100);assert.equal(report.phaseUsage.WORK.totalTokens,3100);
  assert.equal(report.inputCalibrationFailure.calibratedInputCeiling,1200);assert.equal(report.inputCalibrationFailure.observedInput,3000);
  const changed=fresh();changed.instructions+=' newly generated';
  assert.equal(boundary(policy,changed).aborts,0);
  assert.equal(policy.report().requestObservations[1].calibrationSourceSha256,null);
  assert.equal(policy.report().requestObservations[1].inputEstimate,3000,'The cached measured floor exceeds this small changed payload');
  assert.equal(policy.report().requestObservations[1].inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
  assert.equal(policy.report().requestObservations[1].inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.equal(policy.report().requestObservations[1].required,5000);
  assert.equal(policy.modelUsage({input:0,output:0,totalTokens:0},'aborted').reason,Outcome.CANCELLED);
  assert.equal(policy.report().inputCalibrationFailure.observedInput,3000);
  assert.equal(boundary(policy,payload).aborts,1);
  assert.equal(policy.report().outcome,Outcome.CANCELLED);
});

test('zero finalized usage cannot fabricate input accounting or replace finite failures',()=>{
  for(const stopReason of ['stop','error','aborted']) {
    const policy=calibratedPolicy(fresh());boundary(policy,fresh());
    const result=policy.modelUsage({input:0,output:0,totalTokens:0},stopReason);
    assert.equal(result.allow,false);assert.equal(policy.providerInFlight,false);
    assert.equal(result.reason,{stop:'HARNESS_OBSERVATION_INVALID',error:'MODEL_ERROR',aborted:'HARNESS_CANCELLED'}[stopReason]);
    assert.equal(boundary(policy,fresh()).aborts,1);assert.equal(policy.report().usage.totalTokens,0);
  }
  const denied=new CasePolicy(config()),unsupported=fresh();unsupported.extra='unknown';boundary(denied,unsupported);
  assert.equal(denied.modelUsage({input:0,output:0,totalTokens:0},'aborted').reason,Outcome.PAYLOAD_UNSUPPORTED);
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
