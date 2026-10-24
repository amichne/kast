import { spawn } from 'node:child_process';

export const WorkerIpcExitCodes=Object.freeze({CHANNEL_CLOSED:70,SEND_FAILED:71,SEND_TIMEOUT:72});
export const WorkerDeadlineEnvironmentKey='PI_EVALUATION_WORKER_DEADLINE_MS';
const controllerFailure=(operation,reason)=>Object.freeze({stage:'WORKER_CONTROLLER',operation,reason});
const resultDelivery=(stage,outcome)=>Object.freeze({stage,outcome});
// IPC is a separate trust boundary. Admit only exact failure variants emitted
// by the worker, calibration reader and private records/archive owners.
const failureVariants=new Set();
const admit=(stage,operations,reasons)=>{
  for(const operation of operations)for(const reason of reasons)failureVariants.add(`${stage}/${operation}/${reason}`);
};
admit('WORKER_SETUP',['PLAN_READ','PLAN_DECODE','OUTPUT_DIRECTORY','RECORDS_CREATE','ADAPTER_READ','INSTALLATION_READ',
  'PROTECTED_READ','CREDENTIAL_DECODE','SDK_LOAD','MODEL_RUNTIME','SETTINGS_CREATE','SETTINGS_POLICY','MODEL_LOOKUP',
  'RESOURCE_CREATE','RESOURCE_LOAD','RESOURCE_EXTENSIONS','SESSION_CREATE','SESSION_IDENTITY','EXTENSIONS_BIND',
  'EVENT_SUBSCRIBE','RECEIVED_WORKSPACE','RECEIVED_ENTRY','RECEIVED_BRANCH','RECEIVED_POLICY'],['EFFECT_FAILED']);
for(const stage of ['WORKER_SETUP','WORKER_EXECUTION']) {
  admit(stage,['SESSION_ABORT','SESSION_DISPOSE','SESSION_SHUTDOWN','SESSION_SHUTDOWN_OBSERVER',
    'SESSION_SHUTDOWN_OBSERVER_RELEASE','TRANSPORT_CLOSE','SESSION_BOUNDARY'],['EFFECT_FAILED']);
  admit(stage,['SESSION_SHUTDOWN'],['EXTENSION_HANDLER_FAILED']);
}
admit('WORKER_EXECUTION',['SESSION_DRIVE','PROTECTED_VERIFY','REPORT_CREATE','REPORT_WRITE'],['EFFECT_FAILED']);
for(const stage of ['WORKER_SETUP','INPUT_CALIBRATION','REPLAY_ARCHIVE','WORKER_EXECUTION'])
  admit(stage,['WORKER_BOUNDARY'],['EFFECT_FAILED']);
admit('WORKER_IPC',['RESULT_SEND'],['CHANNEL_CLOSED','SEND_FAILED','SEND_TIMEOUT']);
for(const [operation,reason] of [
  ['SETTINGS_POLICY','SETTINGS_UNAVAILABLE'],['MODEL_LOOKUP','MODEL_UNAVAILABLE'],
  ['RESOURCE_EXTENSIONS','EXTENSION_LOAD_FAILED'],['SESSION_CREATE','SESSION_UNAVAILABLE'],
  ['SESSION_IDENTITY','MODEL_EFFORT_UNAVAILABLE'],['WORKER_OWNERSHIP','OWNED_WORKER_REQUIRED'],
  ['INSTALLATION_PIN','PIN_MISMATCH'],['CREDENTIAL_VALIDITY','CREDENTIAL_UNAVAILABLE'],
  ['RECEIVED_WORKSPACE','WORKSPACE_MISMATCH'],['RECEIVED_ENTRY','RECEIVED_RESULT_UNAVAILABLE'],
  ['RECEIVED_CONTEXT','CONTEXT_MISMATCH'],['RECEIVED_POLICY','RECEIVED_RESULT_REJECTED'],
  ['RESTORED_CONTEXT','CONTEXT_MISMATCH'],
])admit('WORKER_SETUP',[operation],[reason]);
admit('INPUT_CALIBRATION',['PARENT_INSPECT','FILE_INSPECT','FILE_OPEN','OPEN_IDENTITY','FILE_READ',
  'READ_CEILING','FINAL_IDENTITY','FINAL_PATH','PARENT_FINAL','FILE_CLOSE'],['MISSING_FILE','IO_FAILED']);
for(const [operation,reasons] of [
  ['REFERENCES',['REFERENCE_REJECTED','REFERENCES_REJECTED','DUPLICATE_REFERENCE']],
  ['PARENT_INSPECT',['PRIVATE_PATH_REQUIRED']],['FILE_INSPECT',['UNSAFE_FILE','PRIVATE_MODE_REQUIRED','SIZE_REJECTED']],
  ['OPEN_IDENTITY',['FILE_CHANGED']],['FILE_READ',['FILE_CHANGED']],['READ_CEILING',['FILE_CHANGED']],
  ['PARENT_FINAL',['FILE_CHANGED']],['FINAL_IDENTITY',['FILE_CHANGED']],['VERIFY_DIGEST',['DIGEST_MISMATCH']],
  ['VERIFY_RECEIPT',['RECEIPT_REJECTED']],['POLICY_REGISTER',['POLICY_EFFECT_FAILED','POLICY_DENIED']],
])admit('INPUT_CALIBRATION',[operation],reasons);
for(const stage of ['PRIVATE_DIRECTORY_CREATE','PRIVATE_DIRECTORY_INSPECT','PRIVATE_DIRECTORY_MODE',
  'RECORD_OPEN','RECORD_CLOSE','RECORD_WRITE','COPY_SOURCE_INSPECT','COPY_SOURCE_OPEN','COPY_DESTINATION_OPEN',
  'COPY_SOURCE_IDENTITY','COPY_READ','COPY_READ_CEILING','COPY_SOURCE_FINAL','COPY_WRITE','COPY_CLOSE','COPY_CLEANUP','ARCHIVE_SCAN','ARCHIVE_INSPECT','ARCHIVE_MODE'])admit(stage,[''],['IO_FAILED']);
for(const [stage,reasons] of [
  ['PRIVATE_DIRECTORY_INSPECT',['PATH_UNSAFE']],['RECORD_CAP',['INVALID_CAP']],['RECORD_WRITE',['ALREADY_CLOSED']],
  ['COPY_SOURCE_INSPECT',['PATH_UNSAFE','SIZE_REJECTED','REFERENCE_REJECTED']],
  ['COPY_SOURCE_IDENTITY',['FILE_CHANGED']],['COPY_READ',['FILE_CHANGED']],
  ['COPY_READ_CEILING',['FILE_CHANGED']],['COPY_SOURCE_FINAL',['FILE_CHANGED']],
  ['COPY_SOURCE_DIGEST',['DIGEST_MISMATCH']],['COPY_WRITE',['NO_PROGRESS']],['ARCHIVE_INSPECT',['PATH_UNSAFE']],
  ['SESSION_OPEN',['SDK_FAILED']],['SESSION_CREATE',['SDK_FAILED']],
  ['UMASK_SET',['EFFECT_FAILED']],['UMASK_RESTORE',['EFFECT_FAILED']],['WORKER_CALLBACK',['CALLBACK_FAILED']],
])admit(stage,[''],reasons);
function exactFields(value,fields) {
  return value!==null&&typeof value==='object'&&!Array.isArray(value)&&Object.getPrototypeOf(value)===Object.prototype
    &&Object.getOwnPropertySymbols(value).length===0&&Object.getOwnPropertyNames(value).length===fields.length
    &&fields.every(field=>Object.hasOwn(value,field)&&Object.hasOwn(Object.getOwnPropertyDescriptor(value,field),'value'));
}
function decodeWorkerFailure(message) {
  const rejected=()=>controllerFailure('IPC_FAILURE','FAILURE_REJECTED');
  try {
    if(!exactFields(message,['type','failure'])||message.type!=='worker_failure')return rejected();
    const value=message.failure,hasOperation=value!==null&&typeof value==='object'&&Object.hasOwn(value,'operation');
    if(!exactFields(value,hasOperation?['stage','operation','reason']:['stage','reason']))return rejected();
    if(typeof value.stage!=='string'||value.stage.length>64||typeof value.reason!=='string'||value.reason.length>64
        ||(hasOperation&&(typeof value.operation!=='string'||value.operation.length>64)))return rejected();
    if(!failureVariants.has(`${value.stage}/${hasOperation?value.operation:''}/${value.reason}`))return rejected();
    return Object.freeze(hasOperation?{stage:value.stage,operation:value.operation,reason:value.reason}:{stage:value.stage,reason:value.reason});
  } catch {return rejected();}
}

// A real, bounded child boundary. Only this owned process group can be killed.
// Cancellation is first cooperative; an unresponsive worker is terminated and
// reaped. No existing IDE/provider/configuration lifecycle is managed here.
export function runOwnedWorker(command,args,{cwd,env,signal,wallMillis,graceMillis=5000,terminateMillis=1000,onEvent=()=>{}}) {
  const invalidTiming=()=>Promise.resolve({type:'worker_failed',failure:controllerFailure('CONTROLLER_POLICY','INVALID_TIMING'),
    resultDelivery:resultDelivery('PARENT_CHILD_CONSTRUCTION','TERMINAL_RESULT_UNAVAILABLE'),
    ownedChildReaped:false,cancellationRequested:false,stderrBytes:0});
  if(!Number.isSafeInteger(wallMillis)||wallMillis<=0||wallMillis>2147483647
      ||!Number.isSafeInteger(graceMillis)||graceMillis<0||graceMillis>2147483647
      ||!Number.isSafeInteger(terminateMillis)||terminateMillis<0||terminateMillis>2147483647)return invalidTiming();
  let deadlineEpochMillis;
  try {deadlineEpochMillis=Date.now()+wallMillis;}catch{return invalidTiming();}
  if(!Number.isSafeInteger(deadlineEpochMillis)||deadlineEpochMillis<=0)return invalidTiming();
  return new Promise(resolve=>{
    let child;
    try {child=spawn(command,args,{cwd,env:{...env,[WorkerDeadlineEnvironmentKey]:String(deadlineEpochMillis)},
      detached:process.platform!=='win32',stdio:['ignore','ignore','pipe','ipc']});}
    catch {resolve({type:'worker_failed',failure:controllerFailure('CHILD_SPAWN','SPAWN_FAILED'),
      resultDelivery:resultDelivery('PARENT_CHILD_CONSTRUCTION','TERMINAL_RESULT_UNAVAILABLE'),
      ownedChildReaped:false,cancellationRequested:false,stderrBytes:0});return;}
    // The first admitted terminal receipt seals authority. Later frames and
    // effects can add one finite diagnostic but can never replace that proof.
    let terminal=Object.freeze({type:'awaiting'}),cleanupFailure,closed=false,cancelling=false,stderrBytes=0;
    const rememberCleanup=failure=>{cleanupFailure??=failure;};
    const retain=(failure,receipt=resultDelivery('PARENT_CHILD_EXIT','TERMINAL_RESULT_UNAVAILABLE'))=>{
      if(terminal.type==='awaiting')terminal=Object.freeze({type:'failure',failure,receipt});
      else rememberCleanup(failure);
    };
    const ownsChild=()=>Number.isSafeInteger(child.pid)&&child.pid>0;
    const timers=[];
    const kill=signalName=>{
      if(!ownsChild())return;
      try {if(process.platform==='win32') child.kill(signalName);else process.kill(-child.pid,signalName);}
      catch(error){if(error?.code!=='ESRCH')retain(controllerFailure(signalName==='SIGTERM'?'CHILD_SIGTERM':'CHILD_SIGKILL','KILL_FAILED'));}
    };
    const observe=event=>{
      try {onEvent(event);}catch {retain(controllerFailure('CONTROLLER_OBSERVER','OBSERVER_FAILED'));}
    };
    const cancel=()=>{
      if(closed||cancelling) return;
      cancelling=true;observe({type:'controller_cancel'});
      if(child.connected) {
        try {child.send({type:'cancel'},error=>{if(error)retain(controllerFailure('CHILD_CANCEL','SEND_FAILED'));});}
        catch {retain(controllerFailure('CHILD_CANCEL','SEND_FAILED'));}
      }
      timers.push(setTimeout(()=>{
        kill('SIGTERM');timers.push(setTimeout(()=>kill('SIGKILL'),terminateMillis));
      },graceMillis));
    };
    child.stderr.on('data',data=>{stderrBytes+=data.length;if(stderrBytes>65536) cancel();});
    child.on('message',message=>{
      if(message?.type==='worker_failure')retain(decodeWorkerFailure(message),resultDelivery('PARENT_IPC_MESSAGE','WORKER_FAILURE_RECEIVED'));
      else if(message?.type==='report') {
        const rejected=message.report===null||typeof message.report!=='object'||Array.isArray(message.report)
          ||Object.getPrototypeOf(message.report)!==Object.prototype;
        if(terminal.type!=='awaiting')rememberCleanup(controllerFailure('IPC_FAILURE',rejected?'REPORT_REJECTED':'TERMINAL_ALREADY_RECEIVED'));
        else if(cancelling||Date.now()>=deadlineEpochMillis)cancel();
        else if(rejected)
          retain(controllerFailure('IPC_FAILURE','REPORT_REJECTED'));
        else terminal=Object.freeze({type:'report',report:message.report,
          receipt:resultDelivery('PARENT_IPC_MESSAGE','REPORT_RECEIVED')});
      }
      // Production emits only terminal frames. The bounded ready observation
      // supports the existing owned-child lifecycle observer without admitting
      // arbitrary messages or payloads as control evidence.
      else if(message?.type==='ready'&&(exactFields(message,['type'])
          ||(exactFields(message,['type','pid'])&&Number.isSafeInteger(message.pid)&&message.pid>0)))observe(message);
      else retain(controllerFailure('IPC_FAILURE','MESSAGE_REJECTED'));
    });
    child.once('error',()=>{retain(controllerFailure(ownsChild()?'CHILD_RUNTIME':'CHILD_SPAWN',ownsChild()?'PROCESS_FAILED':'SPAWN_FAILED'));});
    const cleanup=()=>{for(const timer of timers)clearTimeout(timer);signal?.removeEventListener('abort',cancel);};
    child.once('close',(code,childSignal)=>{
      closed=true;cleanup();kill('SIGKILL');
      const ipcReason=Object.entries(WorkerIpcExitCodes).find(([,exitCode])=>exitCode===code)?.[0];
      const ipcCleanupFailure=ipcReason?Object.freeze({stage:'WORKER_IPC',operation:'RESULT_SEND',reason:ipcReason}):undefined;
      if(ipcCleanupFailure)retain(ipcCleanupFailure,resultDelivery('PARENT_CHILD_EXIT','IPC_SEND_FAILED'));
      if(terminal.type==='awaiting'&&!cancelling)
        retain(controllerFailure('RESULT_DELIVERY','REPORT_UNAVAILABLE'));
      const delivery=terminal.type==='awaiting'?resultDelivery('PARENT_CHILD_EXIT','TERMINAL_RESULT_UNAVAILABLE'):terminal.receipt;
      resolve({type:terminal.type==='failure'?'worker_failed':terminal.type==='report'?'report':'cancelled',
        report:terminal.type==='report'?terminal.report:undefined,...(terminal.type==='failure'?{failure:terminal.failure}:{}),
        ...(cleanupFailure?{cleanupFailure}:{}),...(ipcCleanupFailure?{ipcCleanupFailure}:{}),
        resultDelivery:delivery,exitCode:code,signal:childSignal,
        ownedChildReaped:ownsChild(),cancellationRequested:cancelling,stderrBytes});
    });
    timers.push(setTimeout(cancel,Math.min(wallMillis,Math.max(0,deadlineEpochMillis-Date.now()))));
    signal?.addEventListener('abort',cancel,{once:true});if(signal?.aborted)cancel();
  });
}

// Public SDK continuation consumes the existing transcript. Never prompt, trim,
// summarize, replay tool calls or change tool parameters during continuation.
export async function driveSession(session,{mode,prompt}) {
  try {
    if(mode==='received-result') {
      if(session.agent.state.messages.at(-1)?.role!=='toolResult'||prompt!==undefined) throw Error('Received-result continuation requires exact tool-result leaf and no new prompt');
      await session.agent.continue();await session.agent.waitForIdle();
    } else if(mode==='fresh'&&typeof prompt==='string'&&prompt.length>0) {
      await session.prompt(prompt,{source:'rpc',expandPromptTemplates:false});
    } else throw Error('Explicit fresh/received-result mode required');
  } finally {session.dispose();}
}
