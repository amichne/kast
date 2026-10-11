import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { preflightProviderRequests } from './pi_evaluation_preflight.mjs';

const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const qualification='INDEPENDENT_REQUEST_ADMISSION_NOT_SEQUENCE_OR_LIVE_PROOF';
const tool=()=>({type:'function',name:'query_symbols',description:'PRIVATE-DECLARATION',parameters:{type:'object'}});
const body=()=>({model:'gpt-6.1-sol',instructions:'PRIVATE-INSTRUCTIONS',tools:[tool()],
  input:[{role:'user',content:'PRIVATE-PROMPT'}],reasoning:{effort:'high'},max_output_tokens:100});
const caseConfig=(name='fresh')=>({name,mode:'fresh',prompt:'PRIVATE-PROMPT',wallSeconds:10,
  work:{reportedTokens:1000,requests:1,tools:1,outputReserve:100},
  delivery:{reportedTokens:2000,requests:1,outputReserve:100},
  inputTokenCeiling:300,declarationByteCeiling:4096,maximumToolTextBytes:4096,expectedSemanticCalls:1});
// Synthetic finalized observations exercise the production private reader and
// policy. They are not live provider receipts or tokenizer qualification.
const receipt=request=>({type:'FULL_PAYLOAD_CALIBRATION',provider:'openai-codex',model:'gpt-6.1-sol',request,
  usage:{input:100,cacheRead:20,cacheWrite:10,output:10,totalTokens:140},calibratedInputCeiling:200,
  method:'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE',qualification:'CALIBRATION_NOT_TOKENIZER_PROOF'});
function fixture(t,configure=()=>{}) {
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'pi-preflight-'));fs.chmodSync(directory,0o700);
  t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
  const write=(name,value,type)=>{
    const bytes=Buffer.from(JSON.stringify(value)),file=path.join(directory,name);
    fs.writeFileSync(file,bytes,{mode:0o600});return {type,path:file,sha256:sha(bytes)};
  };
  const fresh=body(),restored=body();
  restored.input.push({type:'additional_tools',role:'developer',tools:[tool()]},
    {type:'function_call',call_id:'received',name:'query_symbols',arguments:'PRIVATE-ARGUMENTS'},
    {type:'function_call_output',call_id:'received',output:'PRIVATE-RECEIVED-RESULT'});
  const freshCase=caseConfig(),savedCase={...caseConfig('saved'),mode:'received-result',
    receivedSessionFile:path.join(directory,'unread-private-session'),receivedResultEntryId:'received',receivedContextSha256:'a'.repeat(64),receivedSessionSha256:'b'.repeat(64)};
  delete savedCase.prompt;
  freshCase.inputCalibrations=[write('fresh-calibration',receipt(fresh),'OFFLINE_FULL_PAYLOAD_CALIBRATION')];
  savedCase.inputCalibrations=[write('saved-calibration',receipt(restored),'OFFLINE_FULL_PAYLOAD_CALIBRATION')];
  const plan={piPackageRoot:'/not-loaded/sdk',kastAdapter:'/not-loaded/adapter',workspaceRoot:'/not-loaded/workspace',
    outputRoot:'/not-created/output',authPath:'/not-read/auth',modelsStorePath:'/not-read/models',
    kastAdapterSha256:'b'.repeat(64),piVersion:'1.0.0',cases:[freshCase,savedCase]};
  const requests=[{case:'fresh',phase:'WORK',body:write('fresh-body',fresh,'FULL_PROVIDER_PAYLOAD')},
    {case:'saved',phase:'DELIVERY',body:write('saved-body',restored,'FULL_PROVIDER_PAYLOAD')}];
  const manifest={type:'FULL_PROVIDER_REQUEST_PREFLIGHT',planSha256:null,requests};
  const state={directory,write,fresh,restored,plan,manifest};configure(state);
  const planRef=write('plan',plan,'OFFLINE_FULL_PAYLOAD_CALIBRATION');manifest.planSha256=planRef.sha256;
  const manifestRef=write('manifest',manifest,'OFFLINE_FULL_PAYLOAD_CALIBRATION');
  return {...state,options:{planPath:planRef.path,manifestPath:manifestRef.path,manifestSha256:manifestRef.sha256}};
}

test('private pinned fresh and restored complete bodies admit independently with bounded sanitized provenance',t=>{
  const f=fixture(t),before=f.manifest.requests.map(request=>fs.readFileSync(request.body.path));
  const result=preflightProviderRequests(f.options);
  assert.equal(result.type,'completed');assert.equal(result.allow,true);
  assert.equal(result.mode,'NO_INFERENCE');assert.equal(result.qualification,qualification);
  assert.equal(result.planSha256,f.manifest.planSha256);assert.equal(result.manifestSha256,f.options.manifestSha256);
  assert.deepEqual(result.requests.map(r=>[r.case,r.phase,r.allowance,r.required,r.outputTokenCap,r.reason]),
    [['fresh','WORK',1000,300,100,'ADMITTED'],['saved','DELIVERY',2000,300,100,'ADMITTED']]);
  for(let i=0;i<result.requests.length;i++) {
    const row=result.requests[i];assert.equal(row.sourceSha256,f.manifest.requests[i].body.sha256);
    assert.match(row.payloadSha256,/^[a-f0-9]{64}$/);assert.equal(row.method,'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS');
    assert.equal(row.boundQualification,'POST_RESPONSE_TOKEN_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT');
    assert.equal(row.calibrationMethod,'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE');
    assert.equal(row.calibrationQualification,'CALIBRATION_NOT_TOKENIZER_PROOF');
    assert.equal(row.calibratedInputCeiling,200);assert.equal(row.inputTokenCeiling,300);
    assert.equal(row.inputEstimate,200);assert.equal(row.inputEstimateMethod,'VERIFIED_FULL_PAYLOAD_EMPIRICAL_CALIBRATION');
    assert.equal(row.inputEstimateQualification,'CALIBRATION_NOT_TOKENIZER_PROOF');
    assert.equal(row.maximumProviderPayloadBytes,262144);assert.equal(row.maximumProviderResponseBytes,65536);
    assert.equal(row.maximumProviderRequests,i===0?3:1);assert.equal(row.maximumReportedTokens,3000);
    assert.equal(row.wallSeconds,10);assert.equal(row.outputCapRequested,100);
    assert.equal(row.outputCapApplied,true);assert.equal(row.backendOutputCapQualification,'UNQUALIFIED');
    assert.deepEqual(fs.readFileSync(f.manifest.requests[i].body.path),before[i]);
  }
  const encoded=JSON.stringify(result);assert.equal(encoded.includes('PRIVATE-'),false);
  assert.equal(encoded.includes(f.directory),false);assert.equal(encoded.includes('/not-'),false);
});

for(const [name,change] of [
  ['instructions',request=>request.instructions+=' changed'],
  ['context',request=>request.input[0].content+=' changed'],
  ['top-level declaration',request=>request.tools[0].description+=' changed'],
  ['restored declaration',request=>request.input[1].tools[0].description+=' changed'],
  ['received result',request=>request.input.at(-1).output+=' changed'],
]) test(`changed ${name} admits within byte and request limits without reusing an empirical estimate`,t=>{
  const f=fixture(t,state=>{
    const request=structuredClone(state.restored);change(request);
    state.manifest.requests[1].body=state.write('changed-body',request,'FULL_PROVIDER_PAYLOAD');
  });
  const result=preflightProviderRequests(f.options);
  assert.equal(result.type,'completed');assert.equal(result.allow,true);
  assert.equal(result.requests[1].reason,'ADMITTED');
  assert.equal(result.requests[1].calibratedInputCeiling,null);
  const estimateBytes=fs.readFileSync(f.manifest.requests[1].body.path).length;
  assert.equal(result.requests[1].inputEstimate,estimateBytes);
  assert.equal(result.requests[1].inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
  assert.equal(result.requests[1].inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.equal(result.requests[1].required,estimateBytes+100);
  assert.equal(result.requests[1].calibrationSourceSha256,null);
});

test('unknown full payload fields and input variants deny rather than being trimmed',t=>{
  for(const change of [request=>{request.unknown='PRIVATE-UNKNOWN';},
    request=>{request.input.push({type:'unknown',secret:'PRIVATE-UNKNOWN'});}]) {
    const f=fixture(t,state=>{const request=body();change(request);
      state.manifest.requests[0].body=state.write('unknown-body',request,'FULL_PROVIDER_PAYLOAD');});
    const result=preflightProviderRequests(f.options);
    assert.equal(result.type,'completed');assert.equal(result.requests[0].reason,'HARNESS_PAYLOAD_UNSUPPORTED');
    assert.equal(JSON.stringify(result).includes('PRIVATE-UNKNOWN'),false);
  }
});

test('absent calibration admits a bounded request and output cap mismatch remains denied unchanged',t=>{
  const uncalibrated=fixture(t,state=>{delete state.plan.cases[0].inputCalibrations;});
  const uncalibratedResult=preflightProviderRequests(uncalibrated.options);
  assert.equal(uncalibratedResult.allow,true);assert.equal(uncalibratedResult.requests[0].reason,'ADMITTED');
  assert.equal(uncalibratedResult.requests[0].calibratedInputCeiling,null);
  const estimateBytes=fs.readFileSync(uncalibrated.manifest.requests[0].body.path).length;
  assert.equal(uncalibratedResult.requests[0].inputEstimate,estimateBytes);
  assert.equal(uncalibratedResult.requests[0].inputEstimateMethod,'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE');
  assert.equal(uncalibratedResult.requests[0].inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.equal(uncalibratedResult.requests[0].required,estimateBytes+100);
  assert.equal(uncalibratedResult.requests[0].calibrationMethod,null);
  assert.equal(uncalibratedResult.requests[0].calibrationQualification,null);
  const wrongCap=fixture(t,state=>{
    const request=body();request.max_output_tokens=99;
    state.manifest.requests[0].body=state.write('wrong-cap',request,'FULL_PROVIDER_PAYLOAD');
  });
  const result=preflightProviderRequests(wrongCap.options);
  assert.equal(result.requests[0].reason,'HARNESS_OBSERVATION_INVALID');
  assert.equal(JSON.parse(fs.readFileSync(wrongCap.manifest.requests[0].body.path)).max_output_tokens,99);
});

test('fresh delivery uses its separate grant and exact cap without simulating earlier usage',t=>{
  const f=fixture(t,state=>{
    state.plan.cases[0].delivery.outputReserve=200;
    const request=body();request.max_output_tokens=200;
    state.manifest.requests.push({case:'fresh',phase:'DELIVERY',
      body:state.write('fresh-delivery-body',request,'FULL_PROVIDER_PAYLOAD')});
  });
  const result=preflightProviderRequests(f.options),delivery=result.requests[2];
  assert.equal(result.allow,true);assert.equal(delivery.phase,'DELIVERY');
  assert.equal(delivery.allowance,2000);assert.equal(delivery.outputReserve,200);
  assert.equal(delivery.outputTokenCap,200);assert.equal(delivery.required,400);
  assert.equal(delivery.maximumReportedTokens,3000,'Independent delivery projection cannot inflate the original global token stop threshold');
  assert.equal(result.qualification,qualification);
});

test('offline reported request response and deadline limits cannot widen beyond execution caps',t=>{
  const f=fixture(t,state=>{
    for(const item of state.plan.cases) {
      item.maximumProviderRequests=99;item.maximumProviderPayloadBytes=1048576;
      item.maximumProviderResponseBytes=1048576;item.wallSeconds=480;
    }
  });
  const result=preflightProviderRequests(f.options);assert.equal(result.allow,true);
  assert.deepEqual(result.requests.map(row=>[row.case,row.maximumProviderRequests,row.maximumProviderPayloadBytes,
    row.maximumProviderResponseBytes,row.wallSeconds,row.maximumReportedTokens]),
    [['fresh',3,262144,65536,120,3000],['saved',1,262144,65536,60,3000]]);
  assert.equal(result.qualification,qualification,'Independent admissions remain explicitly separate from sequence/live proof');
  const narrower=fixture(t,state=>{
    for(const item of state.plan.cases) {
      item.maximumProviderRequests=1;item.maximumProviderPayloadBytes=1024;
      item.maximumProviderResponseBytes=512;item.maximumReportedTokens=777;item.wallSeconds=5;
    }
  });
  const narrowResult=preflightProviderRequests(narrower.options);assert.equal(narrowResult.allow,true);
  assert.deepEqual(narrowResult.requests.map(row=>[row.case,row.maximumProviderRequests,row.maximumProviderPayloadBytes,
    row.maximumProviderResponseBytes,row.wallSeconds,row.maximumReportedTokens]),
    [['fresh',1,1024,512,5,777],['saved',1,1024,512,5,777]],'Explicit smaller limits survive mode normalization');
});

test('manifest shape uniqueness known case phase and required coverage fail closed',t=>{
  for(const change of [
    state=>{state.manifest.extra=true;},
    state=>{state.manifest.requests.push(structuredClone(state.manifest.requests[0]));},
    state=>{state.manifest.requests[0].case='unknown';},
    state=>{state.manifest.requests[1].phase='WORK';},
    state=>{state.manifest.requests.pop();},
    state=>{state.manifest.requests[0].body.type='OFFLINE_FULL_PAYLOAD_CALIBRATION';},
    state=>{state.manifest.requests[0].extra=true;},
    state=>{state.manifest.requests[0].body.extra=true;},
    state=>{state.manifest.requests=Array(65).fill(state.manifest.requests[0]);},
  ]) {
    const f=fixture(t,change),result=preflightProviderRequests(f.options);
    assert.equal(result.type,'failure');assert.equal(result.failure.stage,'PROVIDER_PREFLIGHT');
    assert.equal(result.failure.operation,'MANIFEST');assert.equal(JSON.stringify(result).includes(f.directory),false);
  }
});

test('private reader and calibration failure data propagate without paths or payloads',t=>{
  const f=fixture(t,state=>{state.plan.cases[0].inputCalibrations[0].sha256='0'.repeat(64);});
  const result=preflightProviderRequests(f.options);
  assert.equal(result.type,'failure');assert.deepEqual(result.failure,
    {stage:'INPUT_CALIBRATION',operation:'VERIFY_DIGEST',reason:'DIGEST_MISMATCH'});
  assert.equal(result.source,'CALIBRATION');assert.equal(result.registeredCount,0);
  assert.equal(JSON.stringify(result).includes(f.directory),false);
  const bodyFailureFixture=fixture(t);fs.writeFileSync(bodyFailureFixture.manifest.requests[0].body.path,'{}');
  const bodyFailure=preflightProviderRequests(bodyFailureFixture.options);
  assert.equal(bodyFailure.source,'BODY');assert.deepEqual(bodyFailure.failure,result.failure);
});

test('manifest pins the complete validated plan and malformed JSON is a finite failure',t=>{
  const f=fixture(t);fs.writeFileSync(f.options.planPath,'{}');
  const changed=preflightProviderRequests(f.options);
  assert.equal(changed.source,'PLAN');assert.deepEqual(changed.failure,
    {stage:'INPUT_CALIBRATION',operation:'VERIFY_DIGEST',reason:'DIGEST_MISMATCH'});
  const malformed=fixture(t),bytes=Buffer.from('{');
  fs.writeFileSync(malformed.options.manifestPath,bytes);malformed.options.manifestSha256=sha(bytes);
  assert.deepEqual(preflightProviderRequests(malformed.options).failure,
    {stage:'PROVIDER_PREFLIGHT',operation:'MANIFEST',reason:'JSON_REJECTED'});
  const invalid=fixture(t,state=>{state.plan.cases[0].mode='unknown';});
  assert.deepEqual(preflightProviderRequests(invalid.options).failure,
    {stage:'PROVIDER_PREFLIGHT',operation:'PLAN',reason:'PLAN_REJECTED'});
});

test('CLI emits only sanitized offline admission and rejects unknown flags with finite data',t=>{
  const f=fixture(t),script=fileURLToPath(new URL('./pi_evaluation_preflight.mjs',import.meta.url));
  const run=args=>spawnSync(process.execPath,[script,...args],{encoding:'utf8',timeout:5000,maxBuffer:65536});
  const completed=run(['--plan',f.options.planPath,'--manifest',f.options.manifestPath,'--sha256',f.options.manifestSha256]);
  assert.equal(completed.status,0);assert.equal(completed.stderr,'');
  assert.equal(JSON.parse(completed.stdout).allow,true);assert.equal(completed.stdout.includes(f.directory),false);
  assert.equal(completed.stdout.includes('PRIVATE-'),false);
  const rejected=run(['--unknown']);assert.equal(rejected.status,1);assert.equal(rejected.stderr,'');
  assert.deepEqual(JSON.parse(rejected.stdout).failure,
    {stage:'PROVIDER_PREFLIGHT',operation:'ARGUMENTS',reason:'ARGUMENTS_REJECTED'});
});
