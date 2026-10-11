import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {EventEmitter} from 'node:events';
import {SourceTextModule,createContext} from 'node:vm';

const captured=JSON.parse(readFileSync(new URL('../../../../experiments/host-observation/pi-fixtures/query-delivery-cases.json',import.meta.url)));
const reply=captured.cases.complete.result.initial;
const adapter=readFileSync(new URL('../../../../copilot/extension.mjs',import.meta.url),'utf8');
// Use the adapter's real closed outer decoder, not a second fake admission rule.
const decoder=adapter.slice(adapter.indexOf('function isToolResult('),adapter.indexOf('\nconst reply = await run(["catalog"]);'));
const failures=adapter.match(/const TOOL_RPC_FAILURES = \[[\s\S]*?\];/)[0];
const source=readFileSync(new URL('../../main/js/query-session.mjs',import.meta.url),'utf8');
async function fixture(onWrite){
  const timers=[],children=[],writes=[],observed=[];
  const module=new SourceTextModule(failures+'\n'+decoder+'\n'+source+'\nexport {createQuerySession};',{context:createContext({
    Buffer,TextDecoder,setTimeout:(callback,millis)=>{const timer={callback,millis};timers.push(timer);return timer;},
    clearTimeout:timer=>{if(timer)timer.cleared=true;},
  })});
  await module.link(()=>{throw new Error('No external effects in session rule');});await module.evaluate();
  const spawn=(command,args,options)=>{
    const child=new EventEmitter();child.stdout=new EventEmitter();child.stderr=new EventEmitter();
    child.stdin=new EventEmitter();
    child.stdin.write=input=>{writes.push(JSON.parse(input));onWrite?.(child,input,writes.length);};
    child.stdin.end=()=>queueMicrotask(()=>child.emit('close',0));
    child.kill=()=>{observed.push('killed');queueMicrotask(()=>child.emit('close',null));return true;};
    children.push({child,command,args,options});return child;
  };
  const controller=new AbortController(),environment={KAST_TOOL_RPC_COMMAND:'/selected',KAST_EVALUATION_DEADLINE_MS:'private',JAVA_HOME:'/owned/jbr'};
  const session=module.namespace.createQuerySession('/selected','/owned/root',{callTimeoutMillis:100,maxResponseBytes:262144},controller.signal,spawn,environment);
  return {session,controller,children,writes,timers,observed,environment};
}
const policy={callTimeoutMillis:100,maxResponseBytes:262144};
const send=(child,value=reply)=>child.stdout.emit('data',Buffer.from(JSON.stringify(value)+'\n'));

test('session reuses one child and cleans only its owned environment and resources',async()=>{
  const f=await fixture(child=>send(child));
  const requests=[{request:{type:'RUN'}},{request:{type:'READ_RESULT'}}];
  for(const request of requests)assert.deepEqual(JSON.parse(JSON.stringify(await f.session.invoke(request,policy))),reply);
  await f.session.close();
  assert.equal(f.children.length,1);assert.deepEqual(Array.from(f.children[0].args),['query-session']);
  assert.equal(f.children[0].options.cwd,'/owned/root');
  assert.deepEqual(JSON.parse(JSON.stringify(f.children[0].options.env)),{JAVA_HOME:'/owned/jbr'});
  assert.deepEqual(f.environment,{KAST_TOOL_RPC_COMMAND:'/selected',KAST_EVALUATION_DEADLINE_MS:'private',JAVA_HOME:'/owned/jbr'});
  assert.deepEqual(f.writes,requests);assert.ok(f.timers.every(timer=>timer.cleared));
});
for(const [name,produce,reason] of [
  ['invalid UTF8',child=>child.stdout.emit('data',Buffer.from([0xff,10])),'INVALID_REPLY'],
  ['malformed JSON',child=>child.stdout.emit('data',Buffer.from('{\n')),'INVALID_REPLY'],
  ['unknown failure',child=>send(child,{type:'rejected',failure:'NOT_A_CANONICAL_FAILURE'}),'INVALID_RESULT'],
  ['duplicate records',child=>child.stdout.emit('data',Buffer.from(JSON.stringify(reply)+'\n'+JSON.stringify(reply)+'\n')),'UNEXPECTED_REPLY'],
  ['oversize record',child=>child.stdout.emit('data',Buffer.alloc(262145)),'OUTPUT_OVERFLOW'],
  ['truncated record',child=>{child.stdout.emit('data',Buffer.from('{'));child.emit('close',0);},'MISSING_REPLY'],
  ['nonzero exit',child=>child.emit('close',7),'NONZERO_EXIT'],
])test(`${name} terminates one child without retry`,async()=>{
  const f=await fixture(produce),sizes=[];
  await assert.rejects(f.session.invoke({request:{type:'RUN'}},{...policy,onResponseBytes:n=>sizes.push(n)}),new RegExp(reason));
  await f.session.close();assert.equal(f.writes.length,1);assert.equal(f.children.length,1);assert.equal(sizes.length,1);
});
test('cancellation before invocation launches zero children',async()=>{
  const f=await fixture();f.controller.abort();
  await assert.rejects(f.session.invoke({request:{type:'RUN'}},policy),/CANCELLED/);
  await f.session.close();assert.equal(f.children.length,0);
});
for(const stop of ['cancel','timeout'])test(`${stop} while awaiting is bounded and does not resubmit`,async()=>{
  const f=await fixture();const pending=f.session.invoke({request:{type:'RUN'}},policy);
  if(stop==='cancel')f.controller.abort();else f.timers[0].callback();
  await assert.rejects(pending,stop==='cancel'?/CANCELLED/:/TIMEOUT/);await f.session.close();
  assert.equal(f.writes.length,1);assert.deepEqual(f.observed,['killed']);
});
test('a received first outcome survives cancellation before any further request',async()=>{
  const f=await fixture(child=>send(child));
  const initial=await f.session.invoke({request:{type:'RUN'}},policy);f.controller.abort();
  await assert.rejects(f.session.invoke({request:{type:'READ_RESULT'}},policy),/CANCELLED/);
  await f.session.close();assert.deepEqual(JSON.parse(JSON.stringify(initial)),reply);assert.equal(f.writes.length,1);
});
test('concurrent request is rejected before any additional write',async()=>{
  const f=await fixture();const pending=f.session.invoke({request:{type:'RUN'}},policy);
  await assert.rejects(f.session.invoke({request:{type:'READ_RESULT'}},policy),/CONCURRENT_REQUEST/);
  f.controller.abort();await assert.rejects(pending,/CANCELLED/);await f.session.close();assert.equal(f.writes.length,1);
});
test('bounded graceful cleanup kills an uncooperative idle child',async()=>{
  const f=await fixture(child=>send(child));await f.session.invoke({request:{type:'RUN'}},policy);
  f.children[0].child.stdin.end=()=>{};
  const closing=f.session.close();f.timers.at(-1).callback();await closing;
  assert.deepEqual(f.observed,['killed']);assert.ok(f.timers.every(timer=>timer.cleared));
});

test('cleanup releases owned pipes when an inherited descriptor would postpone child close',async()=>{
  const f=await fixture(child=>send(child));await f.session.invoke({request:{type:'RUN'}},policy);
  const child=f.children[0].child,destroyed=[];
  child.stdin.end=()=>{};
  child.kill=()=>{f.observed.push('killed');child.emit('exit',null);return true;};
  child.stdin.destroy=()=>destroyed.push('stdin');
  child.stderr.destroy=()=>destroyed.push('stderr');
  child.stdout.destroy=()=>{destroyed.push('stdout');queueMicrotask(()=>child.emit('close',null));};
  const closing=f.session.close();f.timers.at(-1).callback();await closing;
  assert.deepEqual(destroyed,['stdin','stdout','stderr']);assert.deepEqual(f.observed,['killed']);
});
