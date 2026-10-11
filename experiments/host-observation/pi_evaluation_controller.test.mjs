import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { runOwnedWorker } from './pi_evaluation_controller.mjs';
import { CasePolicy, decodeEnvelope, Outcome } from './pi_evaluation_policy.mjs';

const options={cwd:process.cwd(),env:{},wallMillis:5000,graceMillis:0,terminateMillis:0};
const child=async(messages,onEvent=()=>assert.fail('Failure IPC must not cross the raw observer boundary'))=>{
  const script=`async function sendAll(){for(const message of JSON.parse(process.argv[1]))
await new Promise((resolve,reject)=>process.send(message,error=>error?reject(error):resolve()));process.disconnect();}
sendAll().catch(()=>{process.exitCode=1;});`;
  return runOwnedWorker(process.execPath,['-e',script,JSON.stringify(messages)],{...options,onEvent});
};
const controllerFailure=(operation,reason)=>({stage:'WORKER_CONTROLLER',operation,reason});

test('finite worker calibration and records failure IPC survives owned child exit',async()=>{
  for(const failure of [
    {stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'},
    {stage:'INPUT_CALIBRATION',operation:'VERIFY_DIGEST',reason:'DIGEST_MISMATCH'},
    {stage:'RECORD_CLOSE',reason:'IO_FAILED'},
    ...[['COPY_SOURCE_INSPECT','SIZE_REJECTED'],['COPY_SOURCE_INSPECT','REFERENCE_REJECTED'],
      ['COPY_SOURCE_IDENTITY','FILE_CHANGED'],['COPY_READ','FILE_CHANGED'],['COPY_READ_CEILING','FILE_CHANGED'],
      ['COPY_SOURCE_FINAL','FILE_CHANGED'],['COPY_SOURCE_DIGEST','DIGEST_MISMATCH'],['COPY_CLEANUP','IO_FAILED']]
      .map(([stage,reason])=>({stage,reason})),
  ]) {
    const result=await child([{type:'worker_failure',failure}]);
    assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,failure);
    assert.equal(result.report,undefined);assert.equal(result.ownedChildReaped,true);assert.equal(result.exitCode,0);
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'WORKER_FAILURE_RECEIVED'});
  }
});

test('first closed worker failure cannot become a later success-shaped report',async()=>{
  const first={stage:'WORKER_SETUP',operation:'RECEIVED_CONTEXT',reason:'CONTEXT_MISMATCH'};
  const result=await child([{type:'worker_failure',failure:first},{type:'report',report:{outcome:'PRIVATE-LATE-REPORT'}},
    {type:'worker_failure',failure:{stage:'RECORD_CLOSE',reason:'IO_FAILED'}}]);
  assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,first);
  assert.equal(result.report,undefined);assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
});

test('unknown extra raw and mismatched failure data rejects at the closed IPC boundary',async()=>{
  for(const message of [
    {type:'worker_failure',failure:{stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PRIVATE-ERROR'}},
    {type:'worker_failure',failure:{stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH',error:'PRIVATE-CREDENTIAL'}},
    {type:'worker_failure',failure:{stage:'RECORD_CLOSE',reason:'IO_FAILED'},payload:'PRIVATE-SOURCE'},
    {type:'worker_failure',failure:'PRIVATE-RAW-ERROR'},
    {type:'worker_failure',failure:{stage:'INPUT_CALIBRATION',operation:'REPORT_WRITE',reason:'PIN_MISMATCH'}},
    {type:'worker_failure',failure:{stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PRIVATE-'.repeat(10000)}},
  ]) {
    const result=await child([message]);assert.equal(result.type,'worker_failed');
    assert.deepEqual(result.failure,controllerFailure('IPC_FAILURE','FAILURE_REJECTED'));
    assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  }
});

test('failed owned spawn resolves finite data instead of rejecting raw process errors',async()=>{
  const result=await runOwnedWorker('/synthetic-absent-worker-3bd6e31b',[],options);
  assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,controllerFailure('CHILD_SPAWN','SPAWN_FAILED'));
  assert.equal(result.ownedChildReaped,false);assert.equal(JSON.stringify(result).includes('synthetic-absent'),false);
});

test('owned unresponsive child cancellation is reaped without signalling an unrelated process',async()=>{
  const cancel=new AbortController(),events=[];
  const script="process.on('message',()=>{});process.on('SIGTERM',()=>{});process.send({type:'ready'});setInterval(()=>{},1000);";
  const result=await runOwnedWorker(process.execPath,['-e',script],{...options,signal:cancel.signal,onEvent:event=>{
    events.push(event.type);if(event.type==='ready')cancel.abort();
  }});
  assert.equal(result.type,'cancelled');assert.equal(result.ownedChildReaped,true);assert.equal(result.signal,'SIGKILL');
  assert.deepEqual(events,['ready','controller_cancel']);assert.equal(result.failure,undefined);
});

test('owned child signal failure remains finite while escalation reaps the same child',async()=>{
  const cancel=new AbortController(),original=process.kill,signalled=[];let ownedPid,result;
  const script="process.on('message',()=>{});process.on('SIGTERM',()=>{});process.send({type:'ready',pid:process.pid});setInterval(()=>{},1000);";
  try {
    process.kill=(pid,signal)=>{
      assert.equal(pid,-ownedPid,'Only the owned detached child group may be signalled');signalled.push([pid,signal]);
      if(signal==='SIGTERM')throw Object.assign(Error('PRIVATE-KILL-CREDENTIAL'),{code:'EPERM'});
      return original(pid,signal);
    };
    result=await runOwnedWorker(process.execPath,['-e',script],{...options,signal:cancel.signal,onEvent:event=>{
      if(event.type==='ready'){ownedPid=event.pid;cancel.abort();}
    }});
  } finally {process.kill=original;}
  assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,controllerFailure('CHILD_SIGTERM','KILL_FAILED'));
  assert.equal(result.ownedChildReaped,true);assert.equal(result.signal,'SIGKILL');assert.equal(result.cancellationRequested,true);
  assert.equal(signalled.some(([,signal])=>signal==='SIGKILL'),true);
  assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
});

test('unsupported timing bounds resolve finite policy rejection before spawning',async()=>{
  for(const timing of [{wallMillis:0},{wallMillis:2147483648},{graceMillis:-1},{terminateMillis:NaN}]) {
    const result=await runOwnedWorker(process.execPath,[],{...options,...timing});
    assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,controllerFailure('CONTROLLER_POLICY','INVALID_TIMING'));
    assert.equal(result.ownedChildReaped,false);
  }
});

test('production terminal helper flushes exact 300k and 1MB reports before owned child exit',async()=>{
  const script=`const {publishWorkerResult}=await import(process.argv[1]);
const size=Number(process.argv[2]);const report={case:'terminal-proof',payload:'R'.repeat(size),usage:{totalTokens:12345}};
await publishWorkerResult({type:'report',report});`;
  const sha=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
  for(const size of [300000,1000000]) {
    const expected={case:'terminal-proof',payload:'R'.repeat(size),usage:{totalTokens:12345}};
    const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
      new URL('./pi_evaluation_worker.mjs',import.meta.url).href,String(size)],options);
    assert.equal(result.type,'report');assert.equal(result.exitCode,0);assert.equal(result.ownedChildReaped,true);
    assert.deepEqual(result.report,expected);assert.equal(sha(result.report),sha(expected));
    assert.equal(result.failure,undefined);assert.equal(result.cancellationRequested,false);
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'REPORT_RECEIVED'});
  }
});

test('production terminal helper preserves a synthetic policy-accepted large rejection summary',async()=>{
  const fixtureUrl=new URL('./pi-fixtures/rejected-document.json',import.meta.url);
  const config={name:'synthetic-large-rejection',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:80000,wallSeconds:120,
    work:{reportedTokens:30000,requests:2,tools:2,outputReserve:2000},
    delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:300000};
  const document=JSON.parse(readFileSync(fixtureUrl,'utf8'));
  document.rejection.detail.originalCoverage.limitations=Array(14000).fill('BYTE_LIMIT_REACHED');
  const text=JSON.stringify({type:'rejected_document',document}),policy=new CasePolicy(config);
  assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN'}},'synthetic-call').allow,true);
  const decision=policy.toolResult(decodeEnvelope([{type:'text',text}]),Buffer.byteLength(text),'synthetic-call',false);
  assert.equal(decision.allow,false);assert.equal(decision.reason,Outcome.REJECTION);
  const expected=policy.report();
  assert.equal(expected.outcome,Outcome.REJECTION);assert.equal(expected.nativeOutcome,'REJECTED');
  assert.equal(expected.semanticRejection.limitations.length,14000);
  assert.equal(Buffer.byteLength(text)<=300000,true);assert.equal(Buffer.byteLength(JSON.stringify(expected))>290000,true);
  // Repeated limitations are an explicitly synthetic accepted boundary shape;
  // this transport proof makes no native producer or normal pilot claim.
  const script=`const {readFileSync}=await import('node:fs');
const {publishWorkerResult}=await import(process.argv[1]);const {CasePolicy,decodeEnvelope}=await import(process.argv[2]);
const document=JSON.parse(readFileSync(new URL(process.argv[3]),'utf8'));
document.rejection.detail.originalCoverage.limitations=Array(14000).fill('BYTE_LIMIT_REACHED');
const text=JSON.stringify({type:'rejected_document',document});const policy=new CasePolicy(JSON.parse(process.argv[4]));
policy.toolCall('query_symbols',{request:{type:'RUN'}},'synthetic-call');
policy.toolResult(decodeEnvelope([{type:'text',text}]),Buffer.byteLength(text),'synthetic-call',false);
await publishWorkerResult({type:'report',report:policy.report()});`;
  const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
    new URL('./pi_evaluation_worker.mjs',import.meta.url).href,new URL('./pi_evaluation_policy.mjs',import.meta.url).href,
    fixtureUrl.href,JSON.stringify(config)],options);
  assert.equal(result.type,'report');assert.equal(result.exitCode,0);assert.equal(result.ownedChildReaped,true);
  assert.deepEqual(result.report,expected);
  assert.equal(createHash('sha256').update(JSON.stringify(result.report)).digest('hex'),
    createHash('sha256').update(JSON.stringify(expected)).digest('hex'));
  assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'REPORT_RECEIVED'});
});

test('production terminal IPC callback failure and timeout leave finite exits and reap owned children',async()=>{
  const script=`const {publishWorkerResult}=await import(process.argv[1]);
process.send=process.argv[2]==='callback'?(_message,callback)=>{callback(Error('PRIVATE-SEND-ERROR'));return false;}:()=>false;
await publishWorkerResult({type:'report',report:{outcome:'SYNTHETIC'}},process,{sendTimeoutMillis:1});`;
  for(const [mode,code,reason] of [['callback',71,'SEND_FAILED'],['timeout',72,'SEND_TIMEOUT']]) {
    const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
      new URL('./pi_evaluation_worker.mjs',import.meta.url).href,mode],options);
    assert.equal(result.type,'worker_failed');assert.equal(result.exitCode,code);assert.equal(result.ownedChildReaped,true);
    assert.equal(result.report,undefined);assert.deepEqual(result.failure,{stage:'WORKER_IPC',operation:'RESULT_SEND',reason});
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'IPC_SEND_FAILED'});
    assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  }
});

test('primitive null and array reports fail closed with finite delivery evidence',async()=>{
  for(const report of [null,0,[]]) {
    const result=await child([{type:'report',report}]);
    assert.equal(result.type,'worker_failed');assert.equal(result.report,undefined);assert.equal(result.ownedChildReaped,true);
    assert.deepEqual(result.failure,controllerFailure('IPC_FAILURE','REPORT_REJECTED'));
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'TERMINAL_RESULT_UNAVAILABLE'});
  }
});

test('reserved IPC failure exits reject a partial report and preserve the first domain failure',async()=>{
  const script=`const message=JSON.parse(process.argv[1]);
process.send(message,error=>{if(error)process.exitCode=1;else{process.exitCode=Number(process.argv[2]);process.disconnect();}});`;
  for(const [code,reason] of [[70,'CHANNEL_CLOSED'],[71,'SEND_FAILED'],[72,'SEND_TIMEOUT']]) {
    const result=await runOwnedWorker(process.execPath,['-e',script,JSON.stringify({type:'report',report:{partial:true}}),String(code)],options);
    assert.equal(result.type,'worker_failed');assert.equal(result.report,undefined);assert.equal(result.ownedChildReaped,true);
    assert.deepEqual(result.failure,{stage:'WORKER_IPC',operation:'RESULT_SEND',reason});
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'IPC_SEND_FAILED'});
  }
  const original={stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'};
  const preserved=await runOwnedWorker(process.execPath,['-e',script,JSON.stringify({type:'worker_failure',failure:original}),'72'],options);
  assert.deepEqual(preserved.failure,original);assert.equal(preserved.type,'worker_failed');assert.equal(preserved.ownedChildReaped,true);
});

test('a child exiting without a terminal receipt leaves finite delivery evidence',async()=>{
  const result=await runOwnedWorker(process.execPath,['-e','process.disconnect();'],options);
  assert.equal(result.type,'worker_failed');assert.equal(result.exitCode,0);assert.equal(result.ownedChildReaped,true);
  assert.deepEqual(result.failure,controllerFailure('RESULT_DELIVERY','REPORT_UNAVAILABLE'));
  assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'TERMINAL_RESULT_UNAVAILABLE'});
});
