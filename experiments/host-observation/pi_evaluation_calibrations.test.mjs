import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
import { loadInputCalibrations, readPrivateCalibrationFile } from './pi_evaluation_calibrations.mjs';

const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const policy=ceiling=>new CasePolicy({name:'synthetic',work:{reportedTokens:2000,requests:1,outputReserve:100,tools:1},
  delivery:{reportedTokens:2000,requests:1,outputReserve:100},inputTokenCeiling:ceiling??300,
  declarationByteCeiling:4096,expectedSemanticCalls:1,maximumToolTextBytes:4096});
const request={model:'gpt-6.1-sol',instructions:'PRIVATE-INSTRUCTIONS',input:[{role:'user',content:'PRIVATE-PROMPT'}],
  reasoning:{effort:'high'},max_output_tokens:100};
const receipt=(ceiling=200)=>({type:'FULL_PAYLOAD_CALIBRATION',provider:'openai-codex',model:'gpt-6.1-sol',request,
  usage:{input:100,cacheRead:20,cacheWrite:10,output:10,totalTokens:140},calibratedInputCeiling:ceiling,
  method:'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE',qualification:'CALIBRATION_NOT_TOKENIZER_PROOF'});
const failure=(operation,reason)=>({type:'failure',failure:{stage:'INPUT_CALIBRATION',operation,reason}});
function owned(t) {
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'pi-calibration-proof-'));fs.chmodSync(directory,0o700);
  t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));return directory;
}
function write(directory,name,value=receipt()) {
  const bytes=Buffer.from(JSON.stringify(value)),file=path.join(directory,name);
  fs.writeFileSync(file,bytes,{mode:0o600});
  return {type:'OFFLINE_FULL_PAYLOAD_CALIBRATION',path:file,sha256:sha(bytes)};
}
function expectFailure(result,operation,reason,count=0) {
  assert.deepEqual(result.failure,failure(operation,reason).failure);
  assert.equal(result.type,'failure');assert.equal(result.registeredCount,count);
  assert.equal(result.provenance.length,count);
}

test('optional absent calibration refs are explicit no-inference empty success',()=>{
  assert.deepEqual(loadInputCalibrations(undefined,policy()),{type:'loaded',mode:'NO_INFERENCE',count:0,provenance:[]});
});

test('valid private pinned receipt registers genuine capability and exposes only bounded provenance',t=>{
  const directory=owned(t),reference=write(directory,'receipt.json'),rule=policy();
  const result=loadInputCalibrations([reference],rule);
  assert.deepEqual(result,{type:'loaded',mode:'NO_INFERENCE',count:1,provenance:[{
    sourceSha256:reference.sha256,payloadSha256:inspectProviderPayload(request,4096).payloadSha256,
    method:'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE',qualification:'CALIBRATION_NOT_TOKENIZER_PROOF',
    calibratedInputCeiling:200,measuredInput:130}]});
  assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  assert.equal(JSON.stringify(result).includes(reference.path),false);
  const admitted=rule.beforeProvider({provider:'openai-codex',model:'gpt-6.1-sol',thinking:'high',
    payload:inspectProviderPayload(request,4096)});
  assert.equal(admitted.allow,true,'Real policy consumes the verified capability, not copied primitive facts');
  const privateBytes=readPrivateCalibrationFile(reference);
  assert.equal(privateBytes.type,'completed');assert.equal(privateBytes.sourceSha256,reference.sha256);
  assert.deepEqual(privateBytes.bytes,fs.readFileSync(reference.path));
});

test('missing files and mismatched digest have finite distinct outcomes without policy registration',t=>{
  const directory=owned(t),missing={type:'OFFLINE_FULL_PAYLOAD_CALIBRATION',path:path.join(directory,'missing'),sha256:'0'.repeat(64)};
  expectFailure(loadInputCalibrations([missing],policy()),'FILE_INSPECT','MISSING_FILE');
  const reference=write(directory,'receipt');reference.sha256='0'.repeat(64);
  expectFailure(loadInputCalibrations([reference],policy()),'VERIFY_DIGEST','DIGEST_MISMATCH');
});

test('oversize receipt is rejected before open or allocation of a read buffer',t=>{
  const directory=owned(t),reference=write(directory,'large');fs.truncateSync(reference.path,4*1024*1024+1);
  const original=fs.openSync;let opens=0;
  try {fs.openSync=(...args)=>{opens++;return original(...args);};
    expectFailure(loadInputCalibrations([reference],policy()),'FILE_INSPECT','SIZE_REJECTED');
  } finally {fs.openSync=original;}
  assert.equal(opens,0);
});

test('source symlink hardlink file mode and parent mode reject without modifying originals',t=>{
  const directory=owned(t),reference=write(directory,'source');
  const link=path.join(directory,'link');fs.symlinkSync(reference.path,link);
  expectFailure(loadInputCalibrations([{...reference,path:link}],policy()),'FILE_INSPECT','UNSAFE_FILE');
  fs.unlinkSync(link);fs.linkSync(reference.path,link);
  expectFailure(loadInputCalibrations([reference],policy()),'FILE_INSPECT','UNSAFE_FILE');fs.unlinkSync(link);
  fs.chmodSync(reference.path,0o644);
  expectFailure(loadInputCalibrations([reference],policy()),'FILE_INSPECT','PRIVATE_MODE_REQUIRED');
  assert.equal(fs.statSync(reference.path).mode&0o777,0o644);
  fs.chmodSync(reference.path,0o400);assert.equal(loadInputCalibrations([reference],policy()).type,'loaded');
  fs.chmodSync(directory,0o755);
  expectFailure(loadInputCalibrations([reference],policy()),'PARENT_INSPECT','PRIVATE_PATH_REQUIRED');
  assert.equal(fs.statSync(directory).mode&0o777,0o755);
});

test('foreign-owned file and parent symlink reject at the private identity boundary',t=>{
  const directory=owned(t),reference=write(directory,'receipt'),original=fs.lstatSync;
  try {
    fs.lstatSync=(file,options)=>{const stat=original(file,options);
      if(file===reference.path) stat.uid=BigInt(process.getuid()+1);return stat;};
    expectFailure(loadInputCalibrations([reference],policy()),'FILE_INSPECT','UNSAFE_FILE');
  } finally {fs.lstatSync=original;}
  const link=path.join(directory,'parent-link');fs.symlinkSync(directory,link);
  expectFailure(loadInputCalibrations([{...reference,path:path.join(link,'receipt')}],policy()),
    'PARENT_INSPECT','PRIVATE_PATH_REQUIRED');
});

test('bounded exact refs reject duplicates and invalid refs before policy effects',t=>{
  const directory=owned(t),reference=write(directory,'receipt');
  for(const refs of [[reference,reference],[reference,{...reference,path:path.join(directory,'other')}],
    [reference,{...reference,sha256:'1'.repeat(64)}]])
    expectFailure(loadInputCalibrations(refs,policy()),'REFERENCES','DUPLICATE_REFERENCE');
  for(const refs of [null,{},Array(65).fill(reference),[{...reference,extra:'unexpected'}],
    [{...reference,path:'relative'}],[{...reference,sha256:'invalid'}]])
    assert.equal(loadInputCalibrations(refs,policy()).type,'failure');
});

test('digest-valid invalid receipt and real policy ceiling denial remain distinct finite failures',t=>{
  const directory=owned(t),bad=write(directory,'invalid',{type:'unknown',payload:'PRIVATE-SOURCE'});
  expectFailure(loadInputCalibrations([bad],policy()),'VERIFY_RECEIPT','RECEIPT_REJECTED');
  const valid=write(directory,'valid');const denied=policy(150);
  expectFailure(loadInputCalibrations([valid],denied),'POLICY_REGISTER','POLICY_DENIED');
  assert.equal(denied.report().outcome,'HARNESS_OBSERVATION_INVALID');
});

test('distinct receipts for same request fail real policy duplicate and preserve prior provenance',t=>{
  const directory=owned(t),first=write(directory,'first'),second=write(directory,'second',receipt(250));
  const rule=policy(),result=loadInputCalibrations([first,second],rule);
  expectFailure(result,'POLICY_REGISTER','POLICY_DENIED',1);
  assert.equal(result.provenance[0].sourceSha256,first.sha256);
  assert.equal(rule.report().outcome,'HARNESS_OBSERVATION_INVALID');
});

test('growth during admitted read rejects at bounded EOF probe with no capability registration',t=>{
  const directory=owned(t),reference=write(directory,'receipt'),original=fs.readSync;let first=true;
  try {
    fs.readSync=(...args)=>{const n=original(...args);
      if(first) {first=false;fs.appendFileSync(reference.path,'x');}return n;};
    expectFailure(loadInputCalibrations([reference],policy()),'READ_CEILING','FILE_CHANGED');
  } finally {fs.readSync=original;}
});

test('same-size identity change after read rejects and raw I/O text is never returned',t=>{
  const directory=owned(t),reference=write(directory,'receipt'),original=fs.fstatSync;let reads=0;
  try {
    fs.fstatSync=(...args)=>{const stat=original(...args);if(++reads===2) stat.ino+=1n;return stat;};
    expectFailure(loadInputCalibrations([reference],policy()),'FINAL_IDENTITY','FILE_CHANGED');
  } finally {fs.fstatSync=original;}
  const open=fs.openSync;
  try {fs.openSync=()=>{throw Error('PRIVATE-ERROR-AND-CREDENTIAL');};
    const result=loadInputCalibrations([reference],policy());
    expectFailure(result,'FILE_OPEN','IO_FAILED');assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  } finally {fs.openSync=open;}
});

test('descriptor close failure rejects before verification or policy registration',t=>{
  const directory=owned(t),reference=write(directory,'receipt'),original=fs.closeSync;
  try {fs.closeSync=fd=>{original(fd);throw Error('PRIVATE-CLOSE-TEXT');};
    expectFailure(loadInputCalibrations([reference],policy()),'FILE_CLOSE','IO_FAILED');
  } finally {fs.closeSync=original;}
});

test('private parent proof remains valid through read and source permissions are never repaired',t=>{
  const directory=owned(t),reference=write(directory,'receipt'),original=fs.readSync;let first=true;
  try {
    fs.readSync=(...args)=>{const count=original(...args);
      if(first) {first=false;fs.chmodSync(directory,0o755);}return count;};
    expectFailure(loadInputCalibrations([reference],policy()),'PARENT_FINAL','FILE_CHANGED');
  } finally {fs.readSync=original;}
  assert.equal(fs.statSync(directory).mode&0o777,0o755);
});
