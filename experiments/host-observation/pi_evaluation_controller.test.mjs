import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { runOwnedWorker, WorkerDeadlineEnvironmentKey } from './pi_evaluation_controller.mjs';
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
  for(const timing of [{wallMillis:0},{wallMillis:2147483648},{graceMillis:-1},{terminateMillis:NaN},
    {wallMillis:Symbol('invalid')},{wallMillis:{[Symbol.toPrimitive](){throw Error('Unexpected timing coercion');}}},
    {graceMillis:{}},{terminateMillis:Symbol('invalid')}]) {
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
  assert.equal(policy.toolCall('query_symbols',{request:{type:'RUN',source:{type:'ALL_DECLARATIONS'},terminalStep:{type:'EMIT'}}},'synthetic-call').allow,true);
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
policy.toolCall('query_symbols',{request:{type:'RUN',source:{type:'ALL_DECLARATIONS'},terminalStep:{type:'EMIT'}}},'synthetic-call');
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

test('production terminal IPC callback failure and timeout leave finite exits and reap owned children',async t=>{
  // Hold the parent cancellation timer: the real child's publication deadline
  // must settle first. Cancel-first precedence is proved in the cleanup cases.
  t.mock.timers.enable({apis:['setTimeout']});
  const script=`const {publishWorkerResult}=await import(process.argv[1]);
process.send=process.argv[2]==='callback'?(_message,callback)=>{callback(Error('PRIVATE-SEND-ERROR'));return false;}:()=>false;
await publishWorkerResult({type:'report',report:{outcome:'SYNTHETIC'}});`;
  for(const [mode,code,reason] of [['callback',71,'SEND_FAILED'],['timeout',72,'SEND_TIMEOUT']]) {
    const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
      new URL('./pi_evaluation_worker.mjs',import.meta.url).href,mode],{...options,wallMillis:mode==='timeout'?300:5000,graceMillis:1000});
    assert.equal(result.type,'worker_failed');assert.equal(result.exitCode,code);assert.equal(result.ownedChildReaped,true);
    assert.equal(result.report,undefined);assert.deepEqual(result.failure,{stage:'WORKER_IPC',operation:'RESULT_SEND',reason});
    assert.equal(result.cancellationRequested,false);assert.equal(result.cleanupFailure,undefined);
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

test('real owned child preserves exact 300k report with 1200ms false-backpressure callback inside parent deadline',async()=>{
  const script=`const {publishWorkerResult}=await import(process.argv[1]);const originalSend=process.send.bind(process);
process.send=(message,callback)=>{originalSend(message,error=>setTimeout(()=>callback(error),1200));return false;};
await publishWorkerResult({type:'report',report:{case:'delayed-callback',payload:'R'.repeat(300000),outcome:'INTENTIONAL_REJECTION'}});`;
  const before=Date.now(),expected={case:'delayed-callback',payload:'R'.repeat(300000),outcome:'INTENTIONAL_REJECTION'};
  const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
    new URL('./pi_evaluation_worker.mjs',import.meta.url).href],options);
  assert.equal(result.type,'report');assert.deepEqual(result.report,expected);assert.equal(result.exitCode,0);
  assert.equal(result.ownedChildReaped,true);assert.equal(result.cancellationRequested,false);assert.equal(result.ipcCleanupFailure,undefined);
  assert.equal(Date.now()-before>=1200,true);
  assert.equal(createHash('sha256').update(JSON.stringify(result.report)).digest('hex'),
    createHash('sha256').update(JSON.stringify(expected)).digest('hex'));
  assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'REPORT_RECEIVED'});
});

test('parent deadline overrides child environment and already elapsed setup is charged before spawn completion',async()=>{
  const script=`process.send({type:'report',report:{deadline:Number(process.env.KAST_EVALUATION_DEADLINE_MS),started:Date.now()}},()=>process.disconnect());`;
  const before=Date.now(),pending=runOwnedWorker(process.execPath,['-e',script],{...options,
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

test('never-callback child remains bounded and post-cancellation report cannot manufacture earlier receipt',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:Date.now()});let controls=0;
  const script=`const {publishWorkerResult}=await import(process.argv[1]);const originalSend=process.send.bind(process);
process.on('SIGTERM',()=>{});setInterval(()=>{},1000);
process.on('message',message=>{if(message.type==='cancel')originalSend({type:'report',report:{outcome:'TOO_LATE'}},()=>originalSend({type:'ready'}));});
process.send=()=>false;originalSend({type:'ready'});await publishWorkerResult({type:'report',report:{outcome:'NOT_SENT'}});`;
  const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
    new URL('./pi_evaluation_worker.mjs',import.meta.url).href],{...options,wallMillis:1000,graceMillis:100,terminateMillis:0,onEvent:event=>{
      if(event.type==='ready'){if(++controls===1)t.mock.timers.tick(1000);else{t.mock.timers.tick(100);t.mock.timers.tick(1);}}
    }});
  assert.equal(controls,2);assert.equal(result.type,'cancelled');assert.equal(result.report,undefined);assert.equal(result.ownedChildReaped,true);
  assert.equal(result.cancellationRequested,true);assert.equal(result.signal,'SIGKILL');
  assert.deepEqual(result.resultDelivery,{stage:'PARENT_CHILD_EXIT',outcome:'TERMINAL_RESULT_UNAVAILABLE'});
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

test('timely real child terminal receipt survives timed cancel send kill runtime and observer cleanup permutations',async t=>{
  const semantic={stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'};
  const script=`const {publishWorkerResult}=await import(process.argv[1]);const mode=process.argv[2];
const first=process.argv[3]==='report'?{type:'report',report:{case:'terminal-authority',payload:'R'.repeat(300000),outcome:'INTENTIONAL_REJECTION'}}:
{type:'failure',failure:{stage:'WORKER_SETUP',operation:'INSTALLATION_PIN',reason:'PIN_MISMATCH'}};
const send=process.send.bind(process),hold=setInterval(()=>{},1000);process.on('SIGTERM',()=>{});
process.on('message',message=>{if(message.type==='cancel'&&mode!=='kill'){clearInterval(hold);process.disconnect();}});
process.send=(message,_callback)=>{send(message,()=>send({type:'ready',pid:process.pid}));return false;};
await publishWorkerResult(first);`;
  for(const first of [{type:'report',report:{case:'terminal-authority',payload:'R'.repeat(300000),outcome:'INTENTIONAL_REJECTION'}},
    {type:'failure',failure:semantic}]) {
    for(const [mode,expectedCleanup] of [
      ['cancel-send',controllerFailure('CHILD_CANCEL','SEND_FAILED')],
      ['kill',controllerFailure('CHILD_SIGTERM','KILL_FAILED')],
      ['runtime',controllerFailure('CHILD_RUNTIME','PROCESS_FAILED')],
      ['observer',controllerFailure('CONTROLLER_OBSERVER','OBSERVER_FAILED')],
    ]) {
      const processes=createRequire(import.meta.url)('node:child_process'),spawn=processes.spawn,kill=process.kill,signals=[];let ownedPid,result;
      t.mock.timers.enable({apis:['Date','setTimeout'],now:Date.now()});
      try {
        processes.spawn=(...args)=>{
          const owned=spawn(...args),send=owned.send;
          owned.send=function(message,callback){
          if(message.type!=='cancel')return send.call(this,message,callback);
          if(mode==='runtime')this.emit('error',Error('PRIVATE-RUNTIME'));
          if(mode==='cancel-send')this.once('disconnect',()=>callback(Error('PRIVATE-CANCEL')));
          return send.call(this,message,error=>{
            if(mode!=='cancel-send')callback(error);
            if(mode==='kill'){t.mock.timers.tick(100);t.mock.timers.tick(1);}
          });
          };return owned;
        };syncBuiltinESMExports();
        process.kill=(pid,signal)=>{
          signals.push([pid,signal]);
          if(mode==='kill'&&signal==='SIGTERM')throw Object.assign(Error('PRIVATE-KILL'),{code:'EPERM'});
          return kill(pid,signal);
        };
        result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
          new URL('./pi_evaluation_worker.mjs',import.meta.url).href,mode,first.type],
        {...options,wallMillis:1000,graceMillis:100,terminateMillis:0,onEvent:event=>{
          if(event.type==='ready'){ownedPid=event.pid;t.mock.timers.tick(1000);}
          if(event.type==='controller_cancel'&&mode==='observer')throw Error('PRIVATE-OBSERVER');
        }});
      } finally {processes.spawn=spawn;syncBuiltinESMExports();process.kill=kill;t.mock.timers.reset();}
      assert.equal(result.type,first.type==='report'?'report':'worker_failed');
      assert.deepEqual(result.report,first.report);assert.deepEqual(result.failure,first.failure);
      assert.deepEqual(result.cleanupFailure,expectedCleanup);assert.equal(result.cancellationRequested,true);
      assert.equal(result.ownedChildReaped,true);assert.ok(signals.every(([pid])=>pid===-ownedPid));
      assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:first.type==='report'?'REPORT_RECEIVED':'WORKER_FAILURE_RECEIVED'});
      assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
    }
  }
});

test('sealed report observes malformed and conflicting late frames after cancellation without replacement',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:Date.now()});
  const script=`const {publishWorkerResult}=await import(process.argv[1]);const send=process.send.bind(process),hold=setInterval(()=>{},1000);
process.on('message',message=>{if(message.type==='cancel')send({type:'report',report:null},()=>
send({type:'report',report:{case:'late-conflict'}},()=>{clearInterval(hold);process.disconnect();}));});
process.send=(message,_callback)=>{send(message,()=>send({type:'ready'}));return false;};
await publishWorkerResult({type:'report',report:{case:'first-authority',outcome:'INTENTIONAL_REJECTION'}});`;
  const result=await runOwnedWorker(process.execPath,['--input-type=module','-e',script,
    new URL('./pi_evaluation_worker.mjs',import.meta.url).href],{...options,wallMillis:1000,graceMillis:100,onEvent:event=>{
      if(event.type==='ready')t.mock.timers.tick(1000);
    }});
  assert.equal(result.type,'report');assert.deepEqual(result.report,{case:'first-authority',outcome:'INTENTIONAL_REJECTION'});
  assert.deepEqual(result.cleanupFailure,controllerFailure('IPC_FAILURE','REPORT_REJECTED'));
  assert.equal(result.cancellationRequested,true);assert.equal(result.ownedChildReaped,true);
  assert.deepEqual(result.resultDelivery,{stage:'PARENT_IPC_MESSAGE',outcome:'REPORT_RECEIVED'});
});
