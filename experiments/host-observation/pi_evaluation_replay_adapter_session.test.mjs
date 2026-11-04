import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { startReplayAdapter } from './pi_evaluation_replay_adapter_session.mjs';

const child=body=>({command:[process.execPath,'--input-type=module','-e',body]});
const prefix=`import readline from 'node:readline';const lines=readline.createInterface({input:process.stdin});const emit=v=>process.stdout.write(JSON.stringify(v)+'\\n');`;
const echo=child(prefix+`emit({type:'ready'});lines.on('line',line=>{const request=JSON.parse(line);if(request.type==='render')emit({type:'rendered',text:request.document});else emit({type:'admission',receipt:{type:'ADMITTED',argv:request.argv,stdin:request.stdin,directory:request.directory}});});`);
const options={readyMillis:2000,responseMillis:2000,shutdownMillis:50};
const input={argv:['query','--ref','exact:v5:with 雪'],stdin:'{"large":9223372036854775807,"text":"雪\\n"}',directory:'/fixture'};

test('one owned child preserves raw strings and serial admission/render receipts',async()=>{
 const started=await startReplayAdapter(echo,options);assert.equal(started.type,'ready');
 const admitted=await started.session.admit(input);
 assert.deepEqual(admitted,{type:'admission',receipt:{type:'ADMITTED',...input}});
 const document='{"integer":9223372036854775807,"decimal":1.230000,"negative":-0,"text":"雪\\n\\\"","empty":[]}';
 assert.deepEqual(await started.session.render(document,'TOON'),{type:'rendered',text:document});
 const end=await started.session.close();assert.equal(end.type,'closed');assert.equal(end.ownedChildReaped,true);assert.equal(end.exitCode,0);
 assert.deepEqual(await started.session.close(),end);
});

test('UTF-8 codepoints split across child chunks survive framing',async()=>{
 const body=prefix+`emit({type:'ready'});lines.on('line',()=>{const bytes=Buffer.from(JSON.stringify({type:'rendered',text:'雪😀'})+'\\n');const cut=bytes.indexOf(Buffer.from('雪'))+1;process.stdout.write(bytes.subarray(0,cut));setImmediate(()=>process.stdout.write(bytes.subarray(cut)));});`;
 const started=await startReplayAdapter(child(body),options);
 assert.deepEqual(await started.session.render('{}','JSON'),{type:'rendered',text:'雪😀'});
 assert.equal((await started.session.close()).ownedChildReaped,true);
});

test('excess requests fail before another child effect and preserve the first failure',async()=>{
 const started=await startReplayAdapter(echo,options);
 assert.equal((await started.session.admit(input)).type,'admission');
 const failure=await started.session.admit(input);
 assert.equal(failure.type,'failure');assert.equal(failure.failure.reason,'REQUEST_LIMIT');assert.equal(failure.ownedChildReaped,true);
 assert.deepEqual((await started.session.render('{}','JSON')).failure,failure.failure);
 assert.deepEqual((await started.session.close()).failure,failure.failure);
});

test('parallel requests reject their illegal transition and reap the owned child',async()=>{
 const started=await startReplayAdapter(child(prefix+`emit({type:'ready'});lines.on('line',()=>{});`),options);
 const first=started.session.render('{}','JSON'),second=started.session.admit(input);
 const results=await Promise.all([first,second]);
 for(const result of results){assert.equal(result.type,'failure');assert.equal(result.failure.reason,'REQUEST_IN_FLIGHT');assert.equal(result.ownedChildReaped,true);}
});

test('cancellation stops and reaps a child without changing the first terminal cause',async()=>{
 const controller=new AbortController();
 const started=await startReplayAdapter(echo,{...options,signal:controller.signal});controller.abort();
 const end=await started.session.close();assert.equal(end.failure.reason,'CANCELLED');assert.equal(end.ownedChildReaped,true);
 const cancelled=await startReplayAdapter(echo,{...options,signal:controller.signal});assert.equal(cancelled.spawned,false);
});

for(const [name,body,expected] of [
 ['unexpected startup',prefix+`emit({type:'rendered',text:'wrong'});`,'READY_REJECTED'],
 ['extra frame',prefix+`emit({type:'ready'});emit({type:'ready'});`,'FRAME_EXCESS'],
 ['malformed frame',`process.stdout.write('not-json\\n');`,'FRAME_INVALID'],
 ['invalid UTF-8',`process.stdout.write(Buffer.from([0xff,0x0a]));`,'FRAME_INVALID'],
 ['partial frame',`process.stdout.write('{"type":"ready"}');`,'INCOMPLETE_FRAME'],
 ['child exit',`process.exitCode=3;`,'CHILD_EXIT'],
 ['bounded stderr',`process.stderr.write('x'.repeat(100));`,'STDERR_LIMIT'],
 ['bounded stdout',`process.stdout.write('x'.repeat(100));`,'FRAME_TOO_LARGE'],
])test(name+' produces finite failure with reaping evidence',async()=>{
 const started=await startReplayAdapter(child(body),{...options,maximumFrameBytes:64,maximumStderrBytes:64});
 const result=started.type==='ready'?await started.session.close():started;
 assert.equal(result.type,'failure');assert.equal(result.failure.reason,expected);assert.equal(result.ownedChildReaped,true);
});

test('wrong response shape and adapter rejection preserve finite causes',async()=>{
 for(const [response,reason,cause] of [[{type:'admission',receipt:{}},'FRAME_UNEXPECTED',undefined],[{type:'rejected',cause:'ENCODING_REJECTED'},'ADAPTER_REJECTED','ENCODING_REJECTED']]){
  const started=await startReplayAdapter(child(prefix+`emit({type:'ready'});lines.on('line',()=>emit(${JSON.stringify(response)}));`),options);
  const result=await started.session.render('{}','JSON');assert.equal(result.failure.reason,reason);assert.equal(result.failure.cause,cause);assert.equal(result.ownedChildReaped,true);
 }
});

test('request size and malformed configuration reject before child effects',async()=>{
 assert.equal((await startReplayAdapter({command:['relative']},options)).spawned,false);
 for(const invalid of [{...options,shutdownMillis:0},{...options,cwd:1},{...options,signal:{}},null,{...options,maximumFrameBytes:1048577}])assert.equal((await startReplayAdapter(echo,invalid)).failure.reason,'LIMIT_REJECTED');
 const started=await startReplayAdapter(echo,{...options,maximumFrameBytes:64});
 const result=await started.session.render('x'.repeat(100),'JSON');assert.equal(result.failure.reason,'FRAME_TOO_LARGE');assert.equal(result.ownedChildReaped,true);
});

test('readiness and response waits have finite deadlines',async()=>{
 const notReady=await startReplayAdapter(child(`process.stdin.resume();`),{...options,readyMillis:10});
 assert.equal(notReady.failure.reason,'READY_TIMEOUT');assert.equal(notReady.ownedChildReaped,true);
 const started=await startReplayAdapter(child(prefix+`emit({type:'ready'});lines.on('line',()=>{});`),{...options,responseMillis:10});
 const result=await started.session.render('{}','JSON');assert.equal(result.failure.reason,'RESPONSE_TIMEOUT');assert.equal(result.ownedChildReaped,true);
});

test('uncooperative child receives bounded termination and reaping preserves cancellation',async()=>{
 const controller=new AbortController();
 const body=prefix+`process.on('SIGTERM',()=>{});setInterval(()=>{},1000);emit({type:'ready'});`;
 const started=await startReplayAdapter(child(body),{...options,signal:controller.signal});
 controller.abort();
 const end=await started.session.close();assert.equal(end.type,'failure');assert.equal(end.failure.reason,'CANCELLED');assert.equal(end.ownedChildReaped,true);assert.equal(end.signal,'SIGKILL');
});

const manifestPath=process.env.KAST_REPLAY_ADAPTER_MANIFEST;
test('real JVM session reuses production admission and preserves exact renderer output',{skip:!manifestPath},async()=>{
 assert.ok(path.isAbsolute(manifestPath));
 const manifest=JSON.parse(fs.readFileSync(manifestPath,'utf8'));
 const started=await startReplayAdapter(manifest,{format:'JSON'});assert.equal(started.type,'ready',JSON.stringify(started));
 const receipt=await started.session.admit({argv:['query','--name','OrderService','--in','services/orders','--source-set','main','--fields','name,signature'],stdin:'',directory:'/fixture'});
 assert.equal(receipt.type,'admission');assert.equal(receipt.receipt.type,'ADMITTED');assert.equal(receipt.receipt.arguments.request.source.declarationName,'OrderService');
 const raw='{"integer":9223372036854775807,"null":null,"text":"雪\\n\\\"","empty":[]}';
 const rendered=await started.session.render(raw,'JSON');assert.deepEqual(rendered,{type:'rendered',text:raw});
 const closed=await started.session.close();assert.equal(closed.type,'closed');assert.equal(closed.ownedChildReaped,true);
});
