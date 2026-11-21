import test from 'node:test';
import assert from 'node:assert/strict';
import { runOwnedWorker, WorkerDeadlineEnvironmentKey } from './owned_worker.mjs';

const options={cwd:process.cwd(),env:{},wallMillis:5000,graceMillis:0,terminateMillis:0};
const child=async(messages,onEvent=()=>assert.fail('Failure IPC must not cross the raw observer boundary'))=>{
  const script=`async function sendAll(){for(const message of JSON.parse(process.argv[1]))
await new Promise((resolve,reject)=>process.send(message,error=>error?reject(error):resolve()));process.disconnect();}
sendAll().catch(()=>{process.exitCode=1;});`;
  return runOwnedWorker(process.execPath,['-e',script,JSON.stringify(messages)],{...options,onEvent});
};
const controllerFailure=(operation,reason)=>({stage:'WORKER_CONTROLLER',operation,reason});

test('owned deadline reaches the worker without adding a reserved Kast configuration key',async()=>{
  const script=`process.send({type:'report',report:{deadline:Number(process.env[process.argv[1]]),
kastKeys:Object.keys(process.env).filter(key=>key.startsWith('KAST_'))}},()=>process.disconnect());`;
  const before=Date.now();
  const received=await runOwnedWorker(process.execPath,['-e',script,WorkerDeadlineEnvironmentKey],options);
  assert.equal(received.type,'report');
  assert.deepEqual(received.report.kastKeys,[]);
  assert.ok(received.report.deadline>=before+options.wallMillis);
  assert.equal(received.ownedChildReaped,true);
});

test('finite worker calibration and records failure IPC survives owned child exit',async()=>{
  for(const failure of [
    {stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'},
    ...['WORKER_SETUP','WORKER_EXECUTION'].flatMap(stage=>[
      {stage,operation:'SESSION_SHUTDOWN',reason:'EXTENSION_HANDLER_FAILED'},
      ...['SESSION_SHUTDOWN','SESSION_SHUTDOWN_OBSERVER','SESSION_SHUTDOWN_OBSERVER_RELEASE']
        .map(operation=>({stage,operation,reason:'EFFECT_FAILED'})),
    ]),
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
  for(const timing of [{wallMillis:0},{wallMillis:2147483648},{graceMillis:-1},{terminateMillis:NaN},
    {wallMillis:Symbol('invalid')},{wallMillis:{[Symbol.toPrimitive](){throw Error('Unexpected timing coercion');}}},
    {graceMillis:{}},{terminateMillis:Symbol('invalid')}]) {
    const result=await runOwnedWorker(process.execPath,[],{...options,...timing});
    assert.equal(result.type,'worker_failed');assert.deepEqual(result.failure,controllerFailure('CONTROLLER_POLICY','INVALID_TIMING'));
    assert.equal(result.ownedChildReaped,false);
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

test('complete received report and first domain failure survive later finite IPC cleanup failures',async()=>{
  const script=`const message=JSON.parse(process.argv[1]);
process.send(message,error=>{if(error)process.exitCode=1;else{process.exitCode=Number(process.argv[2]);process.disconnect();}});`;
  for(const [code,reason] of [[70,'CHANNEL_CLOSED'],[71,'SEND_FAILED'],[72,'SEND_TIMEOUT']]) {
    const report={outcome:'INTENTIONAL_REJECTION',case:'complete-receipt'};
    const result=await runOwnedWorker(process.execPath,['-e',script,JSON.stringify({type:'report',report}),String(code)],options);
    assert.equal(result.type,'report');assert.deepEqual(result.report,report);assert.equal(result.ownedChildReaped,true);
    assert.equal(result.failure,undefined);assert.deepEqual(result.ipcCleanupFailure,{stage:'WORKER_IPC',operation:'RESULT_SEND',reason});
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'REPORT_RECEIVED'});
  }
  const original={stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'};
  const preserved=await runOwnedWorker(process.execPath,['-e',script,JSON.stringify({type:'worker_failure',failure:original}),'72'],options);
  assert.deepEqual(preserved.failure,original);assert.equal(preserved.type,'worker_failed');assert.equal(preserved.ownedChildReaped,true);
  assert.deepEqual(preserved.ipcCleanupFailure,{stage:'WORKER_IPC',operation:'RESULT_SEND',reason:'SEND_TIMEOUT'});
  assert.deepEqual(preserved.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'WORKER_FAILURE_RECEIVED'});
});

test('parent deadline overrides child environment and already elapsed setup is charged before spawn completion',async()=>{
  const script=`process.send({type:'report',report:{deadline:Number(process.env[process.argv[1]]),started:Date.now()}},()=>process.disconnect());`;
  const before=Date.now(),pending=runOwnedWorker(process.execPath,['-e',script,WorkerDeadlineEnvironmentKey],{...options,
    env:{[WorkerDeadlineEnvironmentKey]:String(before+60000)}}),dispatched=Date.now();
  const received=await pending;
  assert.equal(received.type,'report');assert.ok(received.report.deadline>=before+5000);
  assert.ok(received.report.deadline<=dispatched+5000);assert.ok(received.report.deadline<received.report.started+5000);
  const now=Date.now,epoch=now();let current=epoch,result;
  const env={get SYNTHETIC_SETUP(){current+=5000;return 'elapsed';}};
  try {
    Date.now=()=>current;
    result=await runOwnedWorker(process.execPath,['-e',"process.on('SIGTERM',()=>{});setInterval(()=>{},1000);"],
      {...options,env,wallMillis:5000,graceMillis:1000});
  } finally {Date.now=now;}
  assert.equal(result.type,'cancelled');assert.equal(result.cancellationRequested,true);assert.equal(result.ownedChildReaped,true);
  assert.ok(now()-epoch<4000,'Already consumed setup budget must not receive a new 5s parent timer');
  assert.equal(result.report,undefined);assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'TERMINAL_RESULT_UNAVAILABLE'});
});

test('a child exiting without a terminal receipt leaves finite delivery evidence',async()=>{
  const result=await runOwnedWorker(process.execPath,['-e','process.disconnect();'],options);
  assert.equal(result.type,'worker_failed');assert.equal(result.exitCode,0);assert.equal(result.ownedChildReaped,true);
  assert.deepEqual(result.failure,controllerFailure('RESULT_DELIVERY','REPORT_UNAVAILABLE'));
  assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'TERMINAL_RESULT_UNAVAILABLE'});
});

test('first terminal IPC authority survives duplicate conflicting malformed and unknown later frames',async()=>{
  const first={case:'first-authority',outcome:'INTENTIONAL_REJECTION'},semantic={stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'};
  for(const [later,cleanup] of [
    [{type:'report',report:{case:'second-authority'}},controllerFailure('IPC_FAILURE','TERMINAL_ALREADY_RECEIVED')],
    [{type:'report',report:null},controllerFailure('IPC_FAILURE','REPORT_REJECTED')],
    [{type:'worker_failure',failure:semantic},semantic],
    [{type:'worker_failure',failure:{...semantic,error:'PRIVATE-ERROR'}},controllerFailure('IPC_FAILURE','FAILURE_REJECTED')],
    [{type:'unknown',error:'PRIVATE-ERROR'},controllerFailure('IPC_FAILURE','MESSAGE_REJECTED')],
  ]) {
    const result=await child([{type:'report',report:first},later,{type:'report',report:{case:'third-authority'}}]);
    assert.equal(result.type,'report');assert.deepEqual(result.report,first);assert.equal(result.failure,undefined);
    assert.deepEqual(result.cleanupFailure,cleanup);assert.equal(result.ownedChildReaped,true);
    assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'REPORT_RECEIVED'});
    assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  }
  const rejected=await child([{type:'unknown',payload:'PRIVATE-ERROR'},{type:'report',report:first}]);
  assert.equal(rejected.type,'worker_failed');assert.equal(rejected.report,undefined);
  assert.deepEqual(rejected.failure,controllerFailure('IPC_FAILURE','MESSAGE_REJECTED'));
  assert.deepEqual(rejected.cleanupFailure,controllerFailure('IPC_FAILURE','TERMINAL_ALREADY_RECEIVED'));
  assert.equal(JSON.stringify(rejected).includes('PRIVATE-'),false);
});
