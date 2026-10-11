import fs from 'node:fs';
import path from 'node:path';
import { Outcome, READ_TOOLS } from './pi_evaluation_policy.mjs';
import { isAdmittedPayload, PayloadFailure } from './pi_evaluation_payload.mjs';

const maximumSummaryBytes = 64 * 1024;
const marker = Object.freeze({stage:'RECORD_LIMIT',outcome:'RECORDS_TRUNCATED'});
const encode = value => JSON.stringify(value)+'\n';
const markerLine = encode(marker);
const invalid = stage => ({stage,outcome:'OBSERVATION_INVALID'});
const choice = (value, values) => values.includes(value) ? value : 'OBSERVATION_INVALID';
const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
const bool = value => typeof value === 'boolean' ? value : null;
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) ? value : null;
const phases = ['WORK','DELIVERY','STOPPED'];
const reasons = [...Object.values(Outcome),'ADMITTED','SAVED_RESULT_DELIVERY','ZERO_USAGE','ACCOUNTED',
  'READ_TOOL_REPLY','DECLARED_ALREADY_DELIVERED_PROOF','DECLARED_EVIDENCE_ONLY_DELIVERY',
  'QUALIFIED_RESULT_RECEIVED','RESULT_RECEIVED','TOOL_PROPOSAL_PENDING'];
const deliveryStops = ['DELIVERED','CANCELLED','TIME_LIMIT','PAGE_LIMIT','BYTE_LIMIT','DELIVERY_UNAVAILABLE',
  'BUDGET_INCREASE_REQUIRED','MALFORMED_PAGE','IDENTITY_MISMATCH','NON_ADVANCING'];
const stopReasons = ['stop','length','toolUse','error','aborted'];
const sessionEvents = ['agent_start','agent_end','agent_settled','turn_start','turn_end','message_start',
  'message_update','message_end','tool_execution_start','tool_execution_update','tool_execution_end',
  'queue_update','compaction_start','compaction_end','entry_appended','session_info_changed',
  'thinking_level_changed','auto_retry_start','auto_retry_end','summarization_retry_scheduled',
  'summarization_retry_attempt_start','summarization_retry_finished','bash_execution_update'];
const usage = value => ({input:count(value?.input),cacheRead:count(value?.cacheRead),
  cacheWrite:count(value?.cacheWrite),output:count(value?.output),reasoning:count(value?.reasoning),
  totalTokens:count(value?.totalTokens)});
const decision = value => ({allow:bool(value?.allow),reason:choice(value?.reason,reasons),
  phase:choice(value?.phase,phases)});
const settings = value => ({provider:choice(value?.provider,['openai-codex']),
  model:choice(value?.model,['gpt-6.1-sol']),thinking:choice(value?.thinking,['high'])});

// Projection is the privacy policy: no recursive copy, arbitrary strings or IDs.
export function summarizeGuard(value) {
  try {
    switch(value?.type) {
      case 'session_settings': return {stage:'SESSION_SETTINGS',...settings(value),
        toolNames:Array.isArray(value.toolNames) && value.toolNames.length <= READ_TOOLS.length
          ? value.toolNames.map(name=>choice(name,READ_TOOLS)) : ['OBSERVATION_INVALID']};
      case 'provider_admission': {
        const o=value.observation,p=o?.payload;
        if(!isAdmittedPayload(p)) return {stage:'PROVIDER_ADMISSION',...settings(o),
          payloadType:'rejected',payloadFailure:choice(p?.reason,Object.values(PayloadFailure)),
          decision:decision(value.decision)};
        return {stage:'PROVIDER_ADMISSION',...settings(o),payloadType:'admitted-payload',
          payloadSha256:digest(p.payloadSha256),payloadModel:choice(p.payloadModel,['gpt-6.1-sol']),
          effort:choice(p.effort,['high']),payloadBytes:count(p.payloadBytes),toolsBytes:count(p.toolsBytes),
          instructionsBytes:count(p.instructionsBytes),inputBytes:count(p.inputBytes),
          contextBytes:count(p.contextBytes),declarationInputBytes:count(p.declarationInputBytes),
          inputItems:count(p.inputItems),topLevelTools:count(p.topLevelTools),
          restoredDeclarations:count(p.restoredDeclarations),outputTokenCap:count(p.outputTokenCap),
          decision:decision(value.decision)};
      }
      case 'model_tool_proposal': return {stage:'TOOL_PROPOSAL',toolName:choice(value.toolName,READ_TOOLS),
        requestType:choice(value.requestType,['RUN','RESUME','READ_RESULT']),decision:decision(value.decision)};
      case 'native_reply': return {stage:'NATIVE_REPLY',envelopeType:choice(value.envelopeType,
        ['complete','qualified','rejected_document','rejected','query_delivery','invalid']),
        hostIsError:bool(value.hostIsError),deliveryStop:value.deliveryStop == null ? null
          : choice(value.deliveryStop,deliveryStops),physicalRpcsReported:count(value.physicalRpcsReported),
        textBytes:count(value.textBytes),decision:decision(value.decision)};
      case 'model_response': return {stage:'MODEL_RESPONSE',usage:usage(value.usage),
        stopReason:choice(value.stopReason,stopReasons),accounting:decision(value.accounting),
        decision:decision(value.decision)};
      case 'received_context': return {stage:'RECEIVED_CONTEXT',contextSha256:digest(value.contextSha256),
        toolResultSha256:digest(value.toolResultSha256),newUserMessages:count(value.newUserMessages)};
      default: return invalid('GUARD');
    }
  } catch { return invalid('GUARD'); }
}

export function summarizeSessionEvent(value) {
  try {
    const event=choice(value?.type,sessionEvents);
    const summary={stage:'SESSION_EVENT',event};
    if(['message_start','message_update','message_end'].includes(event)) {
      summary.role=choice(value.message?.role,['user','assistant','toolResult','custom','bashExecution',
        'branchSummary','compactionSummary']);
      if(event==='message_end' && summary.role==='assistant') {
        summary.stopReason=choice(value.message?.stopReason,stopReasons);
        summary.usage=usage(value.message?.usage);
      }
    }
    if(event.startsWith('tool_execution_')) {
      summary.toolName=choice(value.toolName,READ_TOOLS);
      if(event==='tool_execution_end') summary.isError=bool(value.isError);
    }
    if(event==='auto_retry_end') summary.success=bool(value.success);
    if(['auto_retry_start','auto_retry_end','summarization_retry_scheduled'].includes(event))
      summary.attempt=count(value.attempt);
    if(event==='compaction_end') {summary.aborted=bool(value.aborted);summary.willRetry=bool(value.willRetry);}
    if(event==='agent_end') summary.willRetry=bool(value.willRetry);
    return summary;
  } catch { return invalid('SESSION_EVENT'); }
}

// Expected effects fail as finite data. The first failure is immutable and is
// retained through cleanup; filesystem/SDK exception text never crosses here.
const failures=new WeakSet();
const failure=(stage,reason)=>{
  const result=Object.freeze({type:'failure',failure:Object.freeze({stage,reason})});
  failures.add(result);return result;
};
const rejected=value=>failures.has(value);
const prepared=Object.freeze({type:'prepared'});
function effect(stage,run) {
  try {return {type:'completed',value:run()};} catch {return failure(stage,'IO_FAILED');}
}
function privateDirectory(directory) {
  try {fs.mkdirSync(directory,{mode:0o700});}
  catch(error) {if(error.code!=='EEXIST') return failure('PRIVATE_DIRECTORY_CREATE','IO_FAILED');}
  const observed=effect('PRIVATE_DIRECTORY_INSPECT',()=>fs.lstatSync(directory));
  if(rejected(observed)) return observed;
  const stat=observed.value;
  if(!stat.isDirectory() || stat.isSymbolicLink() || stat.uid!==process.getuid())
    return failure('PRIVATE_DIRECTORY_INSPECT','PATH_UNSAFE');
  const hardened=effect('PRIVATE_DIRECTORY_MODE',()=>fs.chmodSync(directory,0o700));
  return rejected(hardened) ? hardened : prepared;
}

export function createEvaluationRecords(directory,{maximumBytes=maximumSummaryBytes}={}) {
  if(!Number.isSafeInteger(maximumBytes) || maximumBytes < Buffer.byteLength(markerLine)
      || maximumBytes > maximumSummaryBytes) return failure('RECORD_CAP','INVALID_CAP');
  const directoryResult=privateDirectory(directory);
  if(rejected(directoryResult)) return directoryResult;
  const files={guard:path.join(directory,'guard.jsonl'),event:path.join(directory,'events.jsonl')};
  const descriptors={};
  let state={type:'open',written:0};
  const remember=value=>{
    if(rejected(value) && state.type!=='failed') state={type:'failed',result:value};
    return state.type==='failed' ? state.result : value;
  };
  const close=()=>{
    if(state.type==='closed') return {type:'closed'};
    for(const [channel,fd] of Object.entries(descriptors)) {
      remember(effect('RECORD_CLOSE',()=>fs.closeSync(fd)));delete descriptors[channel];
    }
    if(state.type==='failed') return state.result;
    state={type:'closed'};return {type:'closed'};
  };
  for(const [channel,file] of Object.entries(files)) {
    const opened=effect('RECORD_OPEN',()=>fs.openSync(file,'wx',0o600));
    if(rejected(opened)) {remember(opened);return close();}
    descriptors[channel]=opened.value;
  }
  const status=()=>state.type==='failed' ? state.result :
    {type:state.type==='open'?'ready':state.type};
  const write=(channel,summary)=>{
    if(state.type==='failed') return state.result;
    if(state.type==='closed') return remember(failure('RECORD_WRITE','ALREADY_CLOSED'));
    if(state.type==='truncated') return {type:'truncated'};
    const line=encode(summary),bytes=Buffer.byteLength(line);
    if(state.written+bytes > maximumBytes-Buffer.byteLength(markerLine)) {
      const recorded=effect('RECORD_WRITE',()=>fs.writeFileSync(descriptors.guard,markerLine));
      if(rejected(recorded)) return remember(recorded);
      state={type:'truncated',written:state.written+Buffer.byteLength(markerLine)};
      return {type:'truncated'};
    }
    const recorded=effect('RECORD_WRITE',()=>fs.writeFileSync(descriptors[channel],line));
    if(rejected(recorded)) return remember(recorded);
    state={type:'open',written:state.written+bytes};return {type:'recorded'};
  };
  return Object.freeze({
    type:'records',status,close,
    guard:value=>write('guard',summarizeGuard(value)),
    event:value=>write('event',summarizeSessionEvent(value)),
    lifecycle:(stage,outcome)=>{
      const outcomes={WORKER_SETUP:['PREPARED','FAILED'],INPUT_CALIBRATION:['PREPARED','FAILED'],
        REPLAY_ARCHIVE:['FRESH','RESTORED','FAILED'],WORKER_EXECUTION:['COMPLETED','FAILED']};
      const allowed=typeof stage==='string' && Object.hasOwn(outcomes,stage) && outcomes[stage].includes(outcome);
      return write('guard',allowed ? {stage,outcome} : invalid('LIFECYCLE'));
    },
  });
}

function copyReplay(source,destination) {
  const inspected=effect('COPY_SOURCE_INSPECT',()=>fs.lstatSync(source));
  if(rejected(inspected)) return inspected;
  if(!inspected.value.isFile() || inspected.value.isSymbolicLink())
    return failure('COPY_SOURCE_INSPECT','PATH_UNSAFE');
  const opened=effect('COPY_SOURCE_OPEN',()=>fs.openSync(source,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW));
  if(rejected(opened)) return opened;
  const input=opened.value;
  let output,result=prepared;
  try {
    const destinationResult=effect('COPY_DESTINATION_OPEN',()=>fs.openSync(destination,'wx',0o600));
    if(rejected(destinationResult)) result=destinationResult;
    else {
      output=destinationResult.value;
      const buffer=Buffer.alloc(64*1024);
      while(!rejected(result)) {
        const read=effect('COPY_READ',()=>fs.readSync(input,buffer,0,buffer.length,null));
        if(rejected(read)) {result=read;break;}
        if(read.value===0) break;
        let offset=0;
        while(offset<read.value) {
          const wrote=effect('COPY_WRITE',()=>fs.writeSync(output,buffer,offset,read.value-offset));
          if(rejected(wrote)) {result=wrote;break;}
          if(wrote.value===0) {result=failure('COPY_WRITE','NO_PROGRESS');break;}
          offset+=wrote.value;
        }
      }
    }
  } finally {
    for(const fd of [input,output].filter(value=>value!==undefined)) {
      const closed=effect('COPY_CLOSE',()=>fs.closeSync(fd));
      if(!rejected(result) && rejected(closed)) result=closed;
    }
  }
  return result;
}

function prepareReplay(SessionManager,{directory,workspaceRoot,sourceFile}) {
  for(const current of [directory,path.join(directory,'private-replay')]) {
    const result=privateDirectory(current);if(rejected(result)) return result;
  }
  const archive=path.join(directory,'private-replay');
  const contents=effect('ARCHIVE_SCAN',()=>fs.readdirSync(archive));
  if(rejected(contents)) return contents;
  for(const name of contents.value) {
    const file=path.join(archive,name),observed=effect('ARCHIVE_INSPECT',()=>fs.lstatSync(file));
    if(rejected(observed)) return observed;
    const stat=observed.value;
    if(!stat.isFile() || stat.isSymbolicLink() || stat.nlink!==1 || stat.uid!==process.getuid())
      return failure('ARCHIVE_INSPECT','PATH_UNSAFE');
    const hardened=effect('ARCHIVE_MODE',()=>fs.chmodSync(file,0o600));
    if(rejected(hardened)) return hardened;
  }
  if(sourceFile!==undefined) {
    const ownedCopy=path.join(archive,'received-session.jsonl');
    const copied=copyReplay(sourceFile,ownedCopy);if(rejected(copied)) return copied;
    try {return {type:'prepared',manager:SessionManager.open(ownedCopy,archive)};}
    catch {return failure('SESSION_OPEN','SDK_FAILED');}
  }
  try {return {type:'prepared',manager:SessionManager.create(workspaceRoot,archive)};}
  catch {return failure('SESSION_CREATE','SDK_FAILED');}
}

// SessionManager.open may repair/migrate, and branch continuation appends to its
// existing file. It must receive only our private copy. Fresh SDK files inherit
// 0600 from this isolated worker's umask; do not precreate its deferred wx file.
export async function withPrivateReplaySession(SessionManager,options,run) {
  let previousMask;
  try {previousMask=process.umask(0o077);} catch {return failure('UMASK_SET','EFFECT_FAILED');}
  let result;
  try {
    const preparedReplay=prepareReplay(SessionManager,options);
    if(rejected(preparedReplay)) result=preparedReplay;
    else {
      try {
        const value=await run(preparedReplay.manager);
        result=rejected(value) ? value : {type:'completed',value};
      } catch {result=failure('WORKER_CALLBACK','CALLBACK_FAILED');}
    }
  } finally {
    try {process.umask(previousMask);}
    catch {if(!rejected(result)) result=failure('UMASK_RESTORE','EFFECT_FAILED');}
  }
  return result;
}
