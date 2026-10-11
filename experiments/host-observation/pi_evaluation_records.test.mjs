import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { createEvaluationRecords, summarizeGuard, summarizeSessionEvent, withPrivateReplaySession } from './pi_evaluation_records.mjs';

function owned(t) {
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'pi-record-proof-'));
  t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));return directory;
}
const mode=file=>fs.statSync(file).mode&0o777;
const read=directory=>['guard.jsonl','events.jsonl'].flatMap(name=>
  fs.readFileSync(path.join(directory,name),'utf8').split('\n').filter(Boolean).map(line=>JSON.parse(line)));
const allowedDecision={allow:true,reason:'ADMITTED',phase:'WORK'};
const failed=(stage,reason)=>({type:'failure',failure:{stage,reason}});
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
async function completed(...args) {
  const result=await withPrivateReplaySession(...args);
  assert.equal(result.type,'completed',JSON.stringify(result));return result.value;
}

test('SDK event projection drops source, prompt, model text, arbitrary errors and credentials',()=>{
  const secret='PRIVATE-PAYLOAD-UNIQUE-SECRET';
  const event={type:'message_end',message:{role:'assistant',content:[{type:'text',text:secret}],
    errorMessage:secret,stopReason:'error',usage:{input:10,output:2,totalTokens:12,extra:secret}},
    delta:secret,token:secret,payload:{source:secret}};
  assert.deepEqual(summarizeSessionEvent(event),{stage:'SESSION_EVENT',event:'message_end',role:'assistant',
    stopReason:'error',usage:{input:10,cacheRead:null,cacheWrite:null,output:2,reasoning:null,totalTokens:12}});
  for(const type of ['message_update','tool_execution_update','agent_end','entry_appended','queue_update',
    'compaction_end','auto_retry_end','bash_execution_update',secret]) {
    const value=summarizeSessionEvent({...event,type,toolName:secret,success:false,willRetry:false});
    assert.equal(JSON.stringify(value).includes(secret),false);
  }
  assert.deepEqual(summarizeSessionEvent({type:secret}),{stage:'SESSION_EVENT',event:'OBSERVATION_INVALID'});
});

test('guard allowlist preserves finite admissions and rejects arbitrary names, IDs and nested text',()=>{
  const secret='CREDENTIAL-OR-SOURCE';
  const p=inspectProviderPayload({model:'gpt-6.1-sol',instructions:secret,
    input:[{role:'user',content:secret}],reasoning:{effort:'high'},max_output_tokens:2000},4096);
  const observed={type:'provider_admission',observation:{provider:'openai-codex',model:'gpt-6.1-sol',
    thinking:'high',payload:p},decision:{...allowedDecision,error:secret},prompt:secret};
  assert.deepEqual(summarizeGuard(observed),{stage:'PROVIDER_ADMISSION',provider:'openai-codex',
    model:'gpt-6.1-sol',thinking:'high',payloadType:'admitted-payload',payloadSha256:p.payloadSha256,
    payloadModel:'gpt-6.1-sol',effort:'high',payloadBytes:p.payloadBytes,toolsBytes:2,
    instructionsBytes:Buffer.byteLength(secret),inputBytes:p.inputBytes,contextBytes:p.contextBytes,
    declarationInputBytes:0,inputItems:1,topLevelTools:0,restoredDeclarations:0,outputTokenCap:2000,
    decision:allowedDecision});
  assert.deepEqual(summarizeGuard({type:'provider_response_bytes',bytes:65537,decision:{allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'},content:secret}),{stage:'PROVIDER_RESPONSE_BYTES',bytes:65537,decision:{allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'}});
  assert.deepEqual(summarizeGuard({type:'provider_response_bytes',bytes:64,decision:{allow:true,reason:'RESPONSE_WITHIN_BYTES',phase:'WORK'},content:secret}),{stage:'PROVIDER_RESPONSE_BYTES',bytes:64,decision:{allow:true,reason:'RESPONSE_WITHIN_BYTES',phase:'WORK'}});
  const types=['session_settings','model_tool_proposal','native_reply','model_response','provider_response_bytes','received_context',secret];
  for(const type of types) {
    const value=summarizeGuard({type,provider:secret,model:secret,thinking:secret,toolNames:[secret],
      callId:secret,sessionId:secret,toolName:secret,requestType:secret,content:secret,envelopeType:secret,
      decision:{allow:false,phase:secret,reason:secret},accounting:{error:secret},usage:{input:secret},
      contextSha256:secret,toolResultSha256:secret});
    assert.equal(JSON.stringify(value).includes(secret),false);
  }
});

test('summary counts and hashes are bounded typed fields; hostile objects cannot trigger raw error persistence',()=>{
  const value=summarizeGuard({type:'received_context',sessionId:'raw-id',contextSha256:'a'.repeat(64),
    toolResultSha256:'not-a-digest',newUserMessages:Number.MAX_SAFE_INTEGER+1});
  assert.deepEqual(value,{stage:'RECEIVED_CONTEXT',contextSha256:'a'.repeat(64),toolResultSha256:null,newUserMessages:null});
  assert.equal(summarizeSessionEvent({type:'auto_retry_end',success:'yes',attempt:-1}).attempt,null);
  const hostile={get type(){throw Error('SECRET-ERROR');}};
  assert.deepEqual(summarizeGuard(hostile),{stage:'GUARD',outcome:'OBSERVATION_INVALID'});
});

test('guard and events share aggregate cap including exactly one reserved truncation marker',t=>{
  const directory=owned(t),cap=400;
  const records=createEvaluationRecords(directory,{maximumBytes:cap});t.after(records.close);
  records.lifecycle('WORKER_SETUP','PREPARED');
  records.event({type:'agent_start',payload:'secret'});
  records.guard({type:'native_reply',envelopeType:'complete',textBytes:20,decision:allowedDecision});
  for(let i=0;i<100;i++) {records.event({type:'message_update',message:{role:'assistant',content:'secret'}});
    records.guard({type:'model_tool_proposal',toolName:'query_symbols',requestType:'RUN',decision:allowedDecision});}
  const all=read(directory);
  assert.equal(all.filter(v=>v.outcome==='RECORDS_TRUNCATED').length,1);
  assert.equal(all.some(v=>v.stage==='SESSION_EVENT'),true);
  const total=['guard.jsonl','events.jsonl'].reduce((sum,name)=>sum+fs.statSync(path.join(directory,name)).size,0);
  assert.ok(total<=cap);assert.equal(mode(path.join(directory,'guard.jsonl')),0o600);
  assert.equal(mode(path.join(directory,'events.jsonl')),0o600);
});

test('smallest cap holds only its marker and no exhausted writer appends more bytes',t=>{
  const directory=owned(t);
  const marker={stage:'RECORD_LIMIT',outcome:'RECORDS_TRUNCATED'};
  const cap=Buffer.byteLength(JSON.stringify(marker)+'\n');
  const records=createEvaluationRecords(directory,{maximumBytes:cap});t.after(records.close);
  records.event({type:'agent_start'});records.lifecycle('WORKER_EXECUTION','FAILED');
  assert.deepEqual(read(directory),[marker]);
  assert.equal(fs.statSync(path.join(directory,'guard.jsonl')).size,cap);
  assert.equal(fs.statSync(path.join(directory,'events.jsonl')).size,0);
  for(const maximumBytes of [-1,0,1,65537,NaN])
    assert.deepEqual(createEvaluationRecords(directory,{maximumBytes}),failed('RECORD_CAP','INVALID_CAP'));
});

test('durable lifecycle success and failure remain finite and discard injected prose',t=>{
  const directory=owned(t),records=createEvaluationRecords(directory);t.after(records.close);
  records.lifecycle('WORKER_SETUP','PREPARED');records.lifecycle('REPLAY_ARCHIVE','RESTORED');
  records.lifecycle('INPUT_CALIBRATION','PREPARED');records.lifecycle('INPUT_CALIBRATION','FAILED');
  records.lifecycle('WORKER_EXECUTION','COMPLETED');records.lifecycle('WORKER_EXECUTION','FAILED');
  records.lifecycle('error with source','secret reason');records.lifecycle('__proto__','private text');
  assert.deepEqual(read(directory),[{stage:'WORKER_SETUP',outcome:'PREPARED'},
    {stage:'REPLAY_ARCHIVE',outcome:'RESTORED'},{stage:'INPUT_CALIBRATION',outcome:'PREPARED'},
    {stage:'INPUT_CALIBRATION',outcome:'FAILED'},{stage:'WORKER_EXECUTION',outcome:'COMPLETED'},
    {stage:'WORKER_EXECUTION',outcome:'FAILED'},{stage:'LIFECYCLE',outcome:'OBSERVATION_INVALID'},{stage:'LIFECYCLE',outcome:'OBSERVATION_INVALID'}]);
});

// Synthetic SDK obeys the inspected installed lifecycle: deferred wx creation,
// repair on open, and continuation append to the opened file. No installed SDK,
// authentication, provider, subprocess or native fixture is executed here.
const syntheticSDK={
  create(cwd,dir) {
    const file=path.join(dir,'fresh.jsonl');
    return {file,cwd,append(entry){fs.writeFileSync(file,JSON.stringify(entry)+'\n',{flag:'wx'});}};
  },
  open(file,dir) {
    const original=fs.readFileSync(file,'utf8');
    if(!original.endsWith('\n')) fs.appendFileSync(file,'\n');
    const entries=original.split('\n').filter(Boolean).map(line=>JSON.parse(line));
    return {file,dir,entries,branch(id){this.leaf=id;},buildSessionContext(){return {messages:this.entries
      .filter(e=>e.type==='message').map(e=>e.message)};},append(entry){fs.appendFileSync(file,JSON.stringify(entry)+'\n');}};
  },
};

test('fresh deferred SDK writes inherit restrictive modes and umask is restored',async t=>{
  const directory=owned(t),previous=process.umask(0o022);
  try {
    await completed(syntheticSDK,{directory,workspaceRoot:'/fixture'},async manager=>{
      assert.equal(fs.existsSync(manager.file),false);
      manager.append({role:'user',content:'private prompt'});
      assert.equal(mode(manager.file),0o600);assert.equal(mode(path.dirname(manager.file)),0o700);
    });
    assert.equal(process.umask(),0o022);
  } finally {process.umask(previous);}
});

test('restored continuation opens only exact owned copy, leaves original unchanged and appends privately',async t=>{
  const directory=owned(t),source=path.join(directory,'source.jsonl');
  const messages=[{role:'user',content:'private prompt'},
    {role:'toolResult',toolName:'query_symbols',content:[{type:'text',text:'private source result'}]}];
  const original=messages.map((message,i)=>JSON.stringify({type:'message',id:String(i),message})).join('\n');
  fs.writeFileSync(source,original,{mode:0o400});
  await completed(syntheticSDK,{directory,workspaceRoot:'/fixture',sourceFile:source,sourceSha256:sha(original)},async manager=>{
    assert.notEqual(manager.file,source);manager.branch('1');
    assert.deepEqual(manager.buildSessionContext().messages,messages);
    assert.equal(manager.leaf,'1');manager.append({role:'assistant',content:'continued exact context'});
    assert.equal(mode(manager.file),0o600);
  });
  assert.equal(fs.readFileSync(source,'utf8'),original);assert.equal(mode(source),0o400);
  assert.match(fs.readFileSync(path.join(directory,'private-replay','received-session.jsonl'),'utf8'),/continued exact context/);
});

function replaySource(t,bytes=Buffer.from('{"type":"message","id":"original","message":{"role":"user","content":"synthetic replay"}}\n')) {
  const directory=owned(t),parent=path.join(directory,'source-parent'),output=path.join(directory,'output');
  fs.mkdirSync(parent,{mode:0o755});fs.mkdirSync(output,{mode:0o700});
  const source=path.join(parent,'original.jsonl');fs.writeFileSync(source,bytes,{mode:0o644});
  return {directory,parent,output,source,bytes};
}
async function deniedReplay(fixture,expected) {
  let opens=0,callbacks=0;
  const result=await withPrivateReplaySession({open(){opens++;return {};},create(){opens++;return {};}},
    {directory:fixture.output,workspaceRoot:'/fixture',sourceFile:fixture.source,
      sourceSha256:Object.hasOwn(fixture,'sourceSha256')?fixture.sourceSha256:sha(fixture.bytes)},async()=>{callbacks++;});
  assert.deepEqual(result,expected);assert.equal(opens,0,'SDK open must follow complete source admission');
  assert.equal(callbacks,0);return result;
}

test('ordinary Pi 0644 replay source is copied exactly without hardening the original or its 0755 parent',async t=>{
  const fixture=replaySource(t);let opened=0;
  const sdk={...syntheticSDK,open(file,directory){opened++;return syntheticSDK.open(file,directory);}};
  await completed(sdk,{directory:fixture.output,workspaceRoot:'/fixture',sourceFile:fixture.source,sourceSha256:sha(fixture.bytes)},async manager=>{
    assert.deepEqual(fs.readFileSync(manager.file),fixture.bytes);
    assert.equal(mode(manager.file),0o600);assert.equal(mode(path.dirname(manager.file)),0o700);
    manager.append({type:'message',message:{role:'assistant',content:'synthetic continuation'}});
  });
  assert.equal(opened,1);assert.deepEqual(fs.readFileSync(fixture.source),fixture.bytes);
  assert.equal(mode(fixture.source),0o644);assert.equal(mode(fixture.parent),0o755);
});

test('oversize replay source rejects before source open or copy reads and never opens SDK',async t=>{
  const fixture=replaySource(t);fs.truncateSync(fixture.source,4*1024*1024+1);
  const before=fs.statSync(fixture.source,{bigint:true}),open=fs.openSync,read=fs.readSync;let opens=0,reads=0;
  try {
    fs.openSync=(file,...args)=>{if(file===fixture.source)opens++;return open(file,...args);};
    fs.readSync=(...args)=>{reads++;return read(...args);};
    await deniedReplay(fixture,failed('COPY_SOURCE_INSPECT','SIZE_REJECTED'));
  } finally {fs.openSync=open;fs.readSync=read;}
  assert.equal(opens,0);assert.equal(reads,0);assert.equal(fs.statSync(fixture.source,{bigint:true}).size,before.size);
  assert.deepEqual(fs.readFileSync(fixture.source).subarray(0,fixture.bytes.length),fixture.bytes);
  assert.equal(mode(fixture.source),0o644);
});

test('unsafe replay source links owner and writable modes reject without repairing originals',async t=>{
  for(const unsafe of ['symlink','hardlink','owner','group-write','world-write']) {
    const fixture=replaySource(t),other=path.join(fixture.parent,'other'),lstat=fs.lstatSync;
    const originalSource=fixture.source;
    if(unsafe==='symlink'){fs.symlinkSync(fixture.source,other);fixture.source=other;}
    if(unsafe==='hardlink')fs.linkSync(fixture.source,other);
    if(unsafe==='group-write')fs.chmodSync(fixture.source,0o664);
    if(unsafe==='world-write')fs.chmodSync(fixture.source,0o646);
    const permissions=mode(originalSource);
    try {
      if(unsafe==='owner')fs.lstatSync=(file,options)=>{const stat=lstat(file,options);
        if(file===fixture.source)stat.uid=typeof stat.uid==='bigint'?BigInt(process.getuid()+1):process.getuid()+1;
        return stat;};
      await deniedReplay(fixture,failed('COPY_SOURCE_INSPECT','PATH_UNSAFE'));
    } finally {fs.lstatSync=lstat;}
    assert.deepEqual(fs.readFileSync(originalSource),fixture.bytes);assert.equal(mode(originalSource),permissions);
  }
});

test('replay source replacement before open rejects descriptor identity without opening SDK',async t=>{
  const fixture=replaySource(t),saved=path.join(fixture.parent,'saved-original'),open=fs.openSync;let replaced=false;
  try {
    fs.openSync=(file,...args)=>{
      if(file===fixture.source&&!replaced){replaced=true;fs.renameSync(file,saved);fs.writeFileSync(file,fixture.bytes,{mode:0o644});fs.chmodSync(file,0o644);}
      return open(file,...args);
    };
    await deniedReplay(fixture,failed('COPY_SOURCE_IDENTITY','FILE_CHANGED'));
  } finally {fs.openSync=open;}
  assert.equal(replaced,true);assert.deepEqual(fs.readFileSync(saved),fixture.bytes);assert.equal(mode(saved),0o644);
  assert.deepEqual(fs.readFileSync(fixture.source),fixture.bytes);
});

test('replay source same-size mutation and pathname replacement during read cannot reach SDK open',async t=>{
  for(const change of ['mutation','replacement']) {
    const fixture=replaySource(t,Buffer.alloc(128*1024,0x61)),saved=path.join(fixture.parent,'saved-original');
    const read=fs.readSync;let changed=false;
    try {
      fs.readSync=(...args)=>{const count=read(...args);
        if(!changed){changed=true;
          if(change==='replacement'){fs.renameSync(fixture.source,saved);fs.writeFileSync(fixture.source,fixture.bytes,{mode:0o644});fs.chmodSync(fixture.source,0o644);}
          else {const fd=fs.openSync(fixture.source,'r+');try{fs.writeSync(fd,Buffer.from('b'),0,1,fixture.bytes.length-1);}finally{fs.closeSync(fd);}}
        }return count;};
      await deniedReplay(fixture,failed('COPY_SOURCE_FINAL','FILE_CHANGED'));
    } finally {fs.readSync=read;}
    assert.equal(changed,true);assert.equal(mode(fixture.source),0o644);
    if(change==='replacement'){assert.deepEqual(fs.readFileSync(saved),fixture.bytes);assert.equal(mode(saved),0o644);}
    else {const mutated=fs.readFileSync(fixture.source);assert.deepEqual(mutated.subarray(0,-1),fixture.bytes.subarray(0,-1));assert.equal(mutated.at(-1),0x62);}
  }
});

test('replay source growth during read is bounded by admitted size and rejects before SDK open',async t=>{
  const fixture=replaySource(t),read=fs.readSync;let grown=false,bytesRead=0;
  try {
    fs.readSync=(...args)=>{const count=read(...args);bytesRead+=count;
      if(!grown){grown=true;fs.appendFileSync(fixture.source,Buffer.alloc(4*1024*1024,0x62));}return count;};
    await deniedReplay(fixture,failed('COPY_READ_CEILING','FILE_CHANGED'));
  } finally {fs.readSync=read;}
  assert.equal(grown,true);assert.equal(bytesRead,fixture.bytes.length+1);
  assert.deepEqual(fs.readFileSync(fixture.source).subarray(0,fixture.bytes.length),fixture.bytes);
  assert.equal(mode(fixture.source),0o644);
  assert.equal(fs.existsSync(path.join(fixture.output,'private-replay/received-session.jsonl')),false);
});

test('replay source truncation during bounded read fails as a short read before SDK open',async t=>{
  const fixture=replaySource(t,Buffer.alloc(128*1024,0x61)),read=fs.readSync;let truncated=false,bytesRead=0;
  try {
    fs.readSync=(fd,buffer,offset,length,position)=>{
      const count=read(fd,buffer,offset,Math.min(length,64*1024),position);bytesRead+=count;
      if(!truncated){truncated=true;fs.truncateSync(fixture.source,64*1024);}return count;
    };
    await deniedReplay(fixture,failed('COPY_READ','FILE_CHANGED'));
  } finally {fs.readSync=read;}
  assert.equal(truncated,true);assert.equal(bytesRead,64*1024);
  assert.deepEqual(fs.readFileSync(fixture.source),fixture.bytes.subarray(0,64*1024));
  assert.equal(mode(fixture.source),0o644);
  assert.equal(fs.existsSync(path.join(fixture.output,'private-replay/received-session.jsonl')),false);
});

test('missing malformed and mismatched replay digests fail before destination writes or SDK open',async t=>{
  for(const sourceSha256 of [undefined,null,'not-a-digest','a'.repeat(65),'0'.repeat(64)]) {
    const fixture={...replaySource(t),sourceSha256},open=fs.openSync,write=fs.writeSync;let destinationOpens=0,writes=0;
    const destination=path.join(fixture.output,'private-replay/received-session.jsonl');
    try {
      fs.openSync=(file,...args)=>{if(file===destination)destinationOpens++;return open(file,...args);};
      fs.writeSync=(...args)=>{writes++;return write(...args);};
      const expected=sourceSha256==='0'.repeat(64)?failed('COPY_SOURCE_DIGEST','DIGEST_MISMATCH'):
        failed('COPY_SOURCE_INSPECT','REFERENCE_REJECTED');
      await deniedReplay(fixture,expected);
    } finally {fs.openSync=open;fs.writeSync=write;}
    assert.equal(destinationOpens,0);assert.equal(writes,0);assert.equal(fs.existsSync(destination),false);
    assert.deepEqual(fs.readFileSync(fixture.source),fixture.bytes);assert.equal(mode(fixture.source),0o644);
  }
});

test('replay copy cleanup failure retains the earlier copy failure and never opens SDK',async t=>{
  const fixture=replaySource(t),write=fs.writeSync,unlink=fs.unlinkSync;
  const destination=path.join(fixture.output,'private-replay/received-session.jsonl'),cleanups=[];
  try {
    fs.writeSync=()=>{throw Error('PRIVATE-COPY-WRITE');};
    fs.unlinkSync=file=>{cleanups.push(file);throw Error('PRIVATE-CLEANUP');};
    const result=await deniedReplay(fixture,failed('COPY_WRITE','IO_FAILED'));
    assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  } finally {fs.writeSync=write;fs.unlinkSync=unlink;}
  assert.deepEqual(cleanups,[destination]);assert.deepEqual(fs.readFileSync(fixture.source),fixture.bytes);assert.equal(mode(fixture.source),0o644);
});

test('existing owned archive directories and regular files are hardened before SDK effects',async t=>{
  const directory=owned(t),archive=path.join(directory,'private-replay');
  fs.mkdirSync(archive,{mode:0o755});const old=path.join(archive,'previous.jsonl');
  fs.writeFileSync(old,'private earlier archive',{mode:0o644});
  await completed(syntheticSDK,{directory,workspaceRoot:'/fixture'},async()=>{
    assert.equal(mode(archive),0o700);assert.equal(mode(old),0o600);
    assert.equal(fs.readFileSync(old,'utf8'),'private earlier archive');
  });
});

test('unsafe archive and source symlinks fail closed before SDK open and preserve target',async t=>{
  const directory=owned(t),target=path.join(directory,'target');fs.mkdirSync(target);
  fs.symlinkSync(target,path.join(directory,'private-replay'));
  let calls=0;const sdk={open(){calls++;},create(){calls++;}};
  const mask=process.umask();
  assert.deepEqual(await withPrivateReplaySession(sdk,{directory,workspaceRoot:'/fixture'},async()=>{}),
    failed('PRIVATE_DIRECTORY_INSPECT','PATH_UNSAFE'));
  assert.equal(calls,0);assert.equal(process.umask(),mask);
  fs.unlinkSync(path.join(directory,'private-replay'));
  const original=path.join(directory,'original');fs.writeFileSync(original,'unchanged');
  const source=path.join(directory,'source');fs.symlinkSync(original,source);
  assert.deepEqual(await withPrivateReplaySession(sdk,{directory,workspaceRoot:'/fixture',sourceFile:source,sourceSha256:sha('unchanged')},async()=>{}),
    failed('COPY_SOURCE_INSPECT','PATH_UNSAFE'));
  assert.equal(calls,0);assert.equal(fs.readFileSync(original,'utf8'),'unchanged');
  assert.equal(process.umask(),mask);
});

test('umask restores after SDK setup and continuation failures; existing copy is never overwritten',async t=>{
  const directory=owned(t),mask=process.umask();
  assert.deepEqual(await withPrivateReplaySession({create(){throw Error('setup');}},
    {directory,workspaceRoot:'/fixture'},async()=>{}),failed('SESSION_CREATE','SDK_FAILED'));
  assert.equal(process.umask(),mask);
  assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture'},
    async()=>{throw Error('continuation');}),failed('WORKER_CALLBACK','CALLBACK_FAILED'));
  assert.equal(process.umask(),mask);
  const copy=path.join(directory,'private-replay','received-session.jsonl');fs.writeFileSync(copy,'owned prior');
  const source=path.join(directory,'original');fs.writeFileSync(source,'source');
  assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture',sourceFile:source,sourceSha256:sha('source')},async()=>{}),
    failed('COPY_DESTINATION_OPEN','IO_FAILED'));
  assert.equal(fs.readFileSync(copy,'utf8'),'owned prior');assert.equal(fs.readFileSync(source,'utf8'),'source');
  assert.equal(process.umask(),mask);
});

test('callback persistence failure remains sticky even if an SDK listener swallows it',t=>{
  const directory=owned(t),records=createEvaluationRecords(directory);t.after(records.close);
  const original=fs.writeFileSync;
  try {
    fs.writeFileSync=()=>{throw Error('arbitrary secret failure');};
    assert.deepEqual(records.event({type:'agent_start'}),failed('RECORD_WRITE','IO_FAILED'));
  } finally {fs.writeFileSync=original;}
  assert.deepEqual(records.status(),failed('RECORD_WRITE','IO_FAILED'));
  assert.deepEqual(records.lifecycle('WORKER_EXECUTION','COMPLETED'),failed('RECORD_WRITE','IO_FAILED'));
  assert.deepEqual(read(directory),[]);
});

test('typed negative guard summaries preserve rejection without arbitrary error text',()=>{
  const rejection={allow:false,reason:'HARNESS_BUDGET_LIMIT',phase:'STOPPED'};
  const value=summarizeGuard({type:'native_reply',envelopeType:'rejected',hostIsError:true,
    deliveryStop:'BYTE_LIMIT',physicalRpcsReported:1,textBytes:10,callId:'secret call identity',
    content:'secret tool result',decision:{...rejection,error:'secret provider failure'}});
  assert.deepEqual(value,{stage:'NATIVE_REPLY',envelopeType:'rejected',hostIsError:true,
    deliveryStop:'BYTE_LIMIT',physicalRpcsReported:1,textBytes:10,decision:rejection});
});

test('owned archive refuses existing linked files and summaries refuse symlink directory',async t=>{
  const directory=owned(t),archive=path.join(directory,'private-replay');fs.mkdirSync(archive);
  const original=path.join(directory,'original');fs.writeFileSync(original,'original',{mode:0o644});
  fs.linkSync(original,path.join(archive,'linked.jsonl'));
  assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture'},async()=>{}),
    failed('ARCHIVE_INSPECT','PATH_UNSAFE'));
  assert.equal(mode(original),0o644);
  const link=path.join(directory,'directory-link');fs.symlinkSync(archive,link);
  assert.deepEqual(createEvaluationRecords(link),failed('PRIVATE_DIRECTORY_INSPECT','PATH_UNSAFE'));
  assert.equal(fs.existsSync(path.join(archive,'guard.jsonl')),false);
});

test('real guard callbacks flow through persisted allowlist with an actual policy rejection',t=>{
  const directory=owned(t),records=createEvaluationRecords(directory);t.after(records.close);
  const policy=new CasePolicy({name:'synthetic',work:{reportedTokens:20000,requests:1,outputReserve:2000,tools:1},
    delivery:{reportedTokens:20000,requests:1,outputReserve:2000},inputTokenCeiling:100,
    declarationByteCeiling:4096,expectedSemanticCalls:1,maximumToolTextBytes:4096});
  const handlers=new Map();let tools=[],aborted=0;
  evaluationGuard(policy,records.guard)({on:(name,handler)=>handlers.set(name,handler),
    setActiveTools:value=>{tools=value;},getActiveTools:()=>tools,getThinkingLevel:()=> 'high'});
  const ctx={model:{provider:'openai-codex',id:'gpt-6.1-sol'},abort:()=>{aborted++;}};
  handlers.get('session_start')({},ctx);
  handlers.get('tool_call')({toolName:'secret tool name',toolCallId:'secret ID',
    input:{request:{type:'RUN'},source:'secret payload'}},ctx);
  const summaries=read(directory);
  assert.equal(aborted,1);
  assert.deepEqual(summaries,[{stage:'SESSION_SETTINGS',provider:'openai-codex',model:'gpt-6.1-sol',
    thinking:'high',toolNames:['query_symbols','check_diagnostics','health_check']},
    {stage:'TOOL_PROPOSAL',toolName:'OBSERVATION_INVALID',requestType:'RUN',
      decision:{allow:false,reason:'HARNESS_TOOL_BLOCKED',phase:'STOPPED'}}]);
});

test('record open and close effects retain finite failures before a success receipt',t=>{
  const directory=owned(t);
  fs.writeFileSync(path.join(directory,'events.jsonl'),'existing');
  assert.deepEqual(createEvaluationRecords(directory),failed('RECORD_OPEN','IO_FAILED'));
  fs.unlinkSync(path.join(directory,'guard.jsonl'));fs.unlinkSync(path.join(directory,'events.jsonl'));
  const records=createEvaluationRecords(directory);const original=fs.closeSync;
  try {
    fs.closeSync=fd=>{original(fd);throw Error('secret close text');};
    assert.deepEqual(records.close(),failed('RECORD_CLOSE','IO_FAILED'));
  } finally {fs.closeSync=original;}
  assert.deepEqual(records.status(),failed('RECORD_CLOSE','IO_FAILED'));
  assert.deepEqual(records.guard({type:'session_settings'}),failed('RECORD_CLOSE','IO_FAILED'));
});

test('copy read and SDK open failures retain exact stage and original file',async t=>{
  const directory=owned(t),source=path.join(directory,'original');fs.writeFileSync(source,'original content');
  const readOriginal=fs.readSync;
  try {
    fs.readSync=()=>{throw Error('secret read text');};
    assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture',sourceFile:source,sourceSha256:sha('original content')},async()=>{}),
      failed('COPY_READ','IO_FAILED'));
  } finally {fs.readSync=readOriginal;}
  assert.equal(fs.readFileSync(source,'utf8'),'original content');
  assert.equal(fs.existsSync(path.join(directory,'private-replay','received-session.jsonl')),false);
  assert.deepEqual(await withPrivateReplaySession({open(){throw Error('secret SDK text');}},
    {directory,workspaceRoot:'/fixture',sourceFile:source,sourceSha256:sha('original content')},async()=>{}),failed('SESSION_OPEN','SDK_FAILED'));
  assert.equal(fs.readFileSync(source,'utf8'),'original content');
});

test('callback sticky failure wins over cleanup failure and survives private replay outcome',async t=>{
  const directory=owned(t),records=createEvaluationRecords(directory);
  const writeOriginal=fs.writeFileSync,closeOriginal=fs.closeSync;
  const result=await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture'},async()=>{
    try {fs.writeFileSync=()=>{throw Error('secret write');};return records.guard({type:'session_settings'});}
    finally {fs.writeFileSync=writeOriginal;}
  });
  assert.deepEqual(result,failed('RECORD_WRITE','IO_FAILED'));
  try {fs.closeSync=fd=>{closeOriginal(fd);throw Error('secret close');};
    assert.strictEqual(records.close(),result);
  } finally {fs.closeSync=closeOriginal;}
  assert.strictEqual(records.status(),result);
});

test('private directory and archive effects expose finite operation-specific failures',async t=>{
  for(const [operation,stage] of [['mkdirSync','PRIVATE_DIRECTORY_CREATE'],['lstatSync','PRIVATE_DIRECTORY_INSPECT'],
    ['chmodSync','PRIVATE_DIRECTORY_MODE'],['readdirSync','ARCHIVE_SCAN']]) {
    const directory=owned(t),original=fs[operation];
    try {
      fs[operation]=()=>{throw Error('unshareable filesystem prose');};
      assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture'},async()=>{}),
        failed(stage,'IO_FAILED'));
    } finally {fs[operation]=original;}
  }
});

test('copy write and close signals remain finite and protect original bytes',async t=>{
  for(const [operation,stage] of [['writeSync','COPY_WRITE'],['closeSync','COPY_CLOSE']]) {
    const directory=owned(t),source=path.join(directory,'source');fs.writeFileSync(source,'exact source');
    const original=fs[operation];
    try {
      fs[operation]=(...args)=>{
        if(operation==='closeSync') original(...args);
        throw Error('private copy error');
      };
      assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture',sourceFile:source,sourceSha256:sha('exact source')},async()=>{}),
        failed(stage,'IO_FAILED'));
    } finally {fs[operation]=original;}
    assert.equal(fs.readFileSync(source,'utf8'),'exact source');
  }
});

test('umask set and restore failures are finite and first callback failure wins',async t=>{
  const directory=owned(t),original=process.umask,mask=original();
  try {
    process.umask=()=>{throw Error('unshareable umask prose');};
    assert.deepEqual(await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture'},async()=>{}),
      failed('UMASK_SET','EFFECT_FAILED'));
  } finally {process.umask=original;original(mask);}
  for(const [callback,expected] of [[async()=> 'done',failed('UMASK_RESTORE','EFFECT_FAILED')],
    [async()=>{throw Error('private callback');},failed('WORKER_CALLBACK','CALLBACK_FAILED')]]) {
    let calls=0;
    try {
      process.umask=value=>{if(++calls===2) throw Error('private restoration');return original(value);};
      const result=await withPrivateReplaySession(syntheticSDK,{directory,workspaceRoot:'/fixture'},callback);
      assert.deepEqual(result,expected);assert.equal(calls,2);
    } finally {process.umask=original;original(mask);}
  }
});
