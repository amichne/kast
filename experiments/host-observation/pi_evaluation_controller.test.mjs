import test from 'node:test';
import assert from 'node:assert/strict';
import { runOwnedWorker } from './pi_evaluation_controller.mjs';

const options={cwd:process.cwd(),env:{},wallMillis:5000,graceMillis:0,terminateMillis:0};
const child=async(messages,onEvent=()=>assert.fail('Failure IPC must not cross the raw observer boundary'))=>{
  const script=`async function sendAll(){for(const message of ${JSON.stringify(messages)})
await new Promise((resolve,reject)=>process.send(message,error=>error?reject(error):resolve()));process.disconnect();}
sendAll().catch(()=>{process.exitCode=1;});`;
  return runOwnedWorker(process.execPath,['-e',script],{...options,onEvent});
};
const controllerFailure=(operation,reason)=>({stage:'WORKER_CONTROLLER',operation,reason});

test('finite worker calibration and records failure IPC survives owned child exit',async()=>{
  for(const failure of [
    {stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'},
    {stage:'INPUT_CALIBRATION',operation:'VERIFY_DIGEST',reason:'DIGEST_MISMATCH'},
    {stage:'RECORD_CLOSE',reason:'IO_FAILED'},
  ]) {
    const result=await child([{type:'worker_failure',failure}]);
    assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,failure);
    assert.equal(result.report,undefined);assert.equal(result.ownedChildReaped,true);assert.equal(result.exitCode,0);
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
