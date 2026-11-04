import test from 'node:test';import assert from 'node:assert/strict';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import net from 'node:net';import {spawn} from 'node:child_process';
import { createReplayShell,driverSource } from './pi_evaluation_replay_shell.mjs';
import { ReplayBudget } from './pi_evaluation_replay.mjs';
const supported={skip:process.platform!=='darwin'};
async function fixture(run){
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-shell-proof-'))),budget=new ReplayBudget();
 const secret=path.join(root,'oracle');fs.writeFileSync(secret,'HIDDEN_ORACLE');
 const shell=await createReplayShell({directory:path.join(root,'fixture'),socket:path.join(root,'broker.sock'),budget,
  admit:async request=>({type:'ADMITTED',request}),transition:()=>({stdout:'fixture\n',stderr:'diagnostic\n',exitStatus:2})});
 try{await run(shell,budget,secret);}finally{await shell.dispose();fs.rmSync(root,{recursive:true});}
}
test('real shell preserves quoted argv, stdin, stdout, stderr and exit status',supported,()=>fixture(async(shell,budget)=>{
 const result=await shell.operations.exec(`printf '%s' '{"request":"雪\\n"}' | kast source read --ref 'exact:v5:with space'`,null,{onData:()=>{}});
 assert.equal(result.exitCode,2);assert.deepEqual(shell.invocations[0].request.argv,['source','read','--ref','exact:v5:with space']);
 assert.equal(shell.invocations[0].request.stdin,'{"request":"雪\\n"}');assert.equal(shell.captures[0].stdout,'fixture\n');assert.equal(shell.captures[0].stderr,'diagnostic\n');
 assert.equal(budget.report().kastInvocations,1);
}));
test('hidden second command cannot evade driver-entry count',supported,()=>fixture(async(shell,budget)=>{
 await shell.operations.exec('kast query --name X; kast query --name Y',null,{onData:()=>{}});
 assert.equal(budget.report().kastInvocations,2);assert.equal(shell.invocations.length,1);assert.match(shell.captures[0].stderr,/REPLAY_INVOCATION_LIMIT/);
}));
test('restricted shell denies oracle, credentials, network and production executable access',supported,()=>fixture(async(shell,budget,secret)=>{
 const node=JSON.stringify(process.execPath);
 const code=`const fs=require('fs'),net=require('net'),cp=require('child_process');let out=[];for(const p of [${JSON.stringify(secret)},${JSON.stringify(path.join(os.homedir(),'.pi/agent/auth.json'))}]){try{fs.readFileSync(p);out.push('LEAK')}catch{out.push('DENIED')}};try{cp.execFileSync('/usr/bin/true');out.push('EXEC_LEAK')}catch{out.push('EXEC_DENIED')};const s=net.connect(443,'1.1.1.1');s.on('error',()=>{out.push('NETWORK_DENIED');console.log(out.join(','))});`;
 await shell.operations.exec(`${node} -e '${code.replaceAll("'","'\"'\"'")}'`,null,{onData:()=>{}});
 assert.equal(budget.report().kastInvocations,0);assert.match(shell.captures[0].stdout,/DENIED,DENIED,EXEC_DENIED,NETWORK_DENIED/);assert.doesNotMatch(shell.captures[0].stdout,/LEAK/);
}));

test('a driver entry with no stdin bytes consumes the invocation grant before parsing',supported,async()=>{
 const {default:net}=await import('node:net');
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-entry-proof-'))),socket=path.join(root,'broker.sock');
 let entered;const entry=new Promise(resolve=>{entered=resolve;});let entries=0;
 const shell=await createReplayShell({directory:path.join(root,'fixture'),socket,
  budget:{shell:()=>true,invocation:()=>{entries++;entered();return entries===1;}},
  admit:async()=>{throw Error('NO_REQUEST_BYTES');},transition:()=>{throw Error('NO_TRANSITION');}});
 const blockedInput=net.createConnection(socket);
 try{
  await entry;assert.equal(entries,1);assert.equal(shell.invocations.length,0);
  await shell.operations.exec('kast query --name Second',null,{onData:()=>{}});
  assert.equal(entries,2);assert.equal(shell.invocations.length,0);assert.match(shell.captures[0].stderr,/REPLAY_INVOCATION_LIMIT/);
 }finally{blockedInput.destroy();await shell.dispose();fs.rmSync(root,{recursive:true});}
});

test('the broker rejects a forged fixture root before the privileged adapter',supported,async()=>{
 const {default:net}=await import('node:net');
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-root-proof-'))),socket=path.join(root,'broker.sock');
 const budget=new ReplayBudget();let admissions=0;
 const shell=await createReplayShell({directory:path.join(root,'fixture'),socket,budget,
  admit:async()=>{admissions++;throw Error('FORGED_ROOT_REACHED_ADAPTER');},transition:()=>{throw Error('NO_TRANSITION');}});
 try{
  const response=await new Promise((resolve,reject)=>{const client=net.createConnection(socket);let output='';client.once('error',reject);client.on('data',data=>{output+=data;});client.on('end',()=>resolve(JSON.parse(output)));client.on('connect',()=>client.end(JSON.stringify({argv:['query','--request','/private/oracle'],stdin:'',directory:'/'})));});
  assert.equal(response.exitStatus,2);assert.match(response.stderr,/REPLAY_DRIVER_REQUEST_REJECTED/);assert.equal(admissions,0);assert.equal(budget.report().kastInvocations,1);
  assert.equal(shell.modelFailure.reason,'DRIVER_REQUEST_REJECTED');
  assert.equal(shell.rejections.length,1);
  assert.equal(JSON.parse(Buffer.from(shell.rejections[0].requestBytesBase64,'base64').toString('utf8')).directory,'/');
 }finally{await shell.dispose();fs.rmSync(root,{recursive:true});}
});

const nextPoll=()=>new Promise(resolve=>setImmediate(()=>setImmediate(resolve)));
async function brokerProbe(payload,{split=false}={}) {
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-broker-utf8-'))),directory=path.join(root,'fixture'),socket=path.join(root,'broker.sock');
 const budget=new ReplayBudget();let admissions=0;
 const shell=await createReplayShell({directory,socket,budget,admit:async()=>{admissions++;return {type:'REJECTED',stdout:'',stderr:'',exitStatus:2};},transition:()=>{throw Error('NO_TRANSITION');}});
 try{
  const bytes=payload(directory);
  const reply=await new Promise((resolve,reject)=>{
   const client=net.createConnection(socket),chunks=[];client.once('error',reject);client.on('data',chunk=>chunks.push(chunk));client.on('end',()=>resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))));
   client.once('connect',async()=>{if(!split)client.end(bytes);else{const cut=bytes.indexOf(Buffer.from('雪'))+1;client.write(bytes.subarray(0,cut));await nextPoll();client.end(bytes.subarray(cut));}});
  });
  return {reply,admissions,invocations:structuredClone(shell.invocations),rejections:structuredClone(shell.rejections),modelFailure:shell.modelFailure,failed:shell.failed,entries:budget.report().kastInvocations};
 }finally{await shell.dispose();fs.rmSync(root,{recursive:true});}
}

test('broker preserves split UTF-8 stdin and rejects invalid byte sequences before admission',async()=>{
 const stdin='{"unicode":"雪😀é"}\n';
 for(const split of [false,true]){
  const result=await brokerProbe(directory=>Buffer.from(JSON.stringify({argv:['query','--request','-'],stdin,directory})),{split});
  assert.equal(result.admissions,1);assert.equal(result.entries,1);assert.equal(result.failed,false);
  assert.equal(result.invocations[0].request.stdin,stdin);assert.equal(result.modelFailure,null);
 }
 const invalid=await brokerProbe(directory=>Buffer.concat([Buffer.from(`{"argv":[],"stdin":"`),Buffer.from([0xff]),Buffer.from(`","directory":${JSON.stringify(directory)}}`)]));
 assert.equal(invalid.admissions,0);assert.equal(invalid.entries,1);assert.equal(invalid.failed,false);
 assert.equal(invalid.reply.exitStatus,2);assert.equal(invalid.modelFailure.reason,'DRIVER_REQUEST_INVALID_UTF8');
 assert.ok(Buffer.from(invalid.rejections[0].requestBytesBase64,'base64').includes(0xff));
});

test('broker applies its request ceiling to UTF-8 bytes rather than decoded characters',async()=>{
 const result=await brokerProbe(directory=>Buffer.from(JSON.stringify({argv:['query'],stdin:'雪'.repeat(22000),directory})));
 assert.equal(result.entries,1);assert.equal(result.admissions,0);assert.equal(result.failed,false);
 assert.equal(result.reply.exitStatus,2);assert.equal(result.modelFailure.reason,'DRIVER_REQUEST_TOO_LARGE');
 assert.equal(result.rejections[0].requestTruncated,true);
 assert.ok(Buffer.from(result.rejections[0].requestBytesBase64,'base64').length<=65537);
});

async function driverProbe(reply,{split=false,stdin=Buffer.alloc(0),onRequest=()=>{}}={}) {
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-driver-utf8-'))),socket=path.join(root,'broker.sock'),driver=path.join(root,'kast.mjs');
 fs.writeFileSync(driver,driverSource(process.execPath),{mode:0o700});
 const server=net.createServer({allowHalfOpen:true},client=>{const request=[];client.on('error',()=>{});client.on('data',chunk=>request.push(chunk));client.on('end',async()=>{onRequest(JSON.parse(Buffer.concat(request).toString('utf8')));if(!split)client.end(reply);else{const cut=reply.indexOf(Buffer.from('雪'))+1;client.write(reply.subarray(0,cut));await nextPoll();client.end(reply.subarray(cut));}});});
 await new Promise(resolve=>server.listen(socket,resolve));
 try{return await new Promise((resolve,reject)=>{
  const child=spawn(process.execPath,[driver],{cwd:root,env:{KAST_REPLAY_SOCKET:socket},stdio:['pipe','pipe','pipe']}),stdout=[],stderr=[];
  const timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('DRIVER_PROBE_DEADLINE'));},5000);
  child.stdin.on('error',()=>{});child.stdin.end(stdin);child.stdout.on('data',chunk=>stdout.push(chunk));child.stderr.on('data',chunk=>stderr.push(chunk));child.once('error',reject);
  child.once('close',exitStatus=>{clearTimeout(timer);resolve({stdout:Buffer.concat(stdout).toString('utf8'),stderr:Buffer.concat(stderr).toString('utf8'),exitStatus});});
 });}finally{await new Promise(resolve=>server.close(resolve));fs.rmSync(root,{recursive:true});}
}

test('actual driver preserves split Unicode replies and rejects invalid UTF-8 bytes',async()=>{
 const reply=Buffer.from(JSON.stringify({stdout:'雪😀\n',stderr:'diagnostic\n',exitStatus:0}));
 for(const split of [false,true])assert.deepEqual(await driverProbe(reply,{split}),{stdout:'雪😀\n',stderr:'diagnostic\n',exitStatus:0});
 const invalid=await driverProbe(Buffer.concat([Buffer.from('{"stdout":"'),Buffer.from([0xff]),Buffer.from('","stderr":"","exitStatus":0}')]));
 assert.equal(invalid.exitStatus,70);assert.equal(invalid.stdout,'');assert.match(invalid.stderr,/REPLAY_DRIVER_RESPONSE_INVALID_UTF8/);
 const excess=await driverProbe(Buffer.from(JSON.stringify({stdout:'雪'.repeat(360000),stderr:'',exitStatus:0})));
 assert.equal(excess.exitStatus,70);assert.equal(excess.stdout,'');assert.match(excess.stderr,/REPLAY_DRIVER_RESPONSE_LIMIT/);
});

test('restricted shell rejects invalid UTF-8 stdin after the driver-entry debit',supported,()=>fixture(async(shell,budget)=>{
 await shell.operations.exec("printf '\\377' | kast query --request -",null,{onData:()=>{}});
 assert.equal(budget.report().kastInvocations,1);assert.equal(shell.failed,false);assert.equal(shell.modelFailure.reason,'STDIN_INVALID_UTF8');
 assert.equal(shell.invocations[0].request.stdinEncoding,'INVALID_UTF8');
 assert.deepEqual(Buffer.from(shell.invocations[0].request.stdinBytesBase64,'base64'),Buffer.from([0xff]));
 assert.equal(shell.invocations[0].admission.type,'DRIVER_INPUT_REJECTED');assert.equal(shell.invocations[0].admission.nativeCalls,0);
 assert.equal(shell.captures[0].exitStatus,2);assert.match(shell.captures[0].stderr,/REPLAY_DRIVER_STDIN_INVALID_UTF8/);
}));

test('actual driver reads only the bounded stdin prefix and explicitly reports truncation',async()=>{
 const reply=Buffer.from(JSON.stringify({stdout:'',stderr:'',exitStatus:2}));
 let exact,excess;
 await driverProbe(reply,{stdin:Buffer.alloc(65536,65),onRequest:request=>{exact=request;}});
 assert.equal(exact.stdin,'A'.repeat(65536));assert.equal(exact.type,undefined);
 await driverProbe(reply,{stdin:Buffer.alloc(131072,65),onRequest:request=>{excess=request;}});
 assert.equal(excess.type,'stdin_rejected');assert.equal(excess.reason,'BYTE_LIMIT');
 assert.equal(excess.stdinBytesRead,65537);assert.equal(excess.stdinTruncated,true);
 assert.deepEqual(Buffer.from(excess.stdinBase64,'base64'),Buffer.alloc(65537,65));
 let bom;
 await driverProbe(reply,{stdin:Buffer.from('\uFEFF{"request":{}}\n'),onRequest:request=>{bom=request;}});
 assert.equal(bom.stdin,'\uFEFF{"request":{}}\n');
});

test('restricted stdin budget exhaustion preserves its prefix and consumes one entry without admission',supported,()=>fixture(async(shell,budget)=>{
 const code="process.stdout.write(Buffer.alloc(65537,65))";
 await shell.operations.exec(`${JSON.stringify(process.execPath)} -e '${code}' | kast query --request -`,null,{onData:()=>{}});
 assert.equal(budget.report().kastInvocations,1);assert.equal(shell.failed,false);assert.equal(shell.modelFailure.reason,'STDIN_BUDGET');
 const invocation=shell.invocations[0];assert.equal(invocation.admission.type,'DRIVER_INPUT_REJECTED');assert.equal(invocation.admission.nativeCalls,0);
 assert.equal(invocation.request.stdinBytesRead,65537);assert.equal(invocation.request.stdinTruncated,true);
 assert.deepEqual(Buffer.from(invocation.request.stdinBytesBase64,'base64'),Buffer.alloc(65537,65));
}));

test('restricted capture keeps a controlled split Unicode sequence exact',supported,()=>fixture(async(shell,budget)=>{
 // The owned child releases its suffix only after the parent receives and
 // acknowledges the first stdout chunk through an explicit signal.
 const code="const b=Buffer.from('雪😀\\n'),w=require('fs').watch('.',()=>{});process.once('SIGUSR2',()=>{w.close();process.stdout.write(b.subarray(1));});process.stdout.write(Buffer.concat([Buffer.from(process.pid+'\\n'),b.subarray(0,1)]));";
 let released=false,pid;const observed=[];
 await shell.operations.exec(`${JSON.stringify(process.execPath)} -e '${code.replaceAll("'","'\"'\"'")}'`,null,{timeout:5,onData:chunk=>{
  observed.push(chunk);const text=Buffer.concat(observed).toString('ascii'),newline=text.indexOf('\n');
  if(!released&&newline>0){pid=Number(text.slice(0,newline));released=true;process.kill(pid,'SIGUSR2');}
 }});
 assert.equal(shell.captures[0].stdout,`${pid}\n雪😀\n`);
 assert.equal(budget.report().kastInvocations,0);assert.equal(shell.modelFailure,null);
}));

for(const [stream,redirect] of [['stdout',''],['stderr',' >&2']])test(`restricted capture preserves invalid ${stream} bytes with a finite model failure`,supported,()=>fixture(async(shell,budget)=>{
 await shell.operations.exec(`printf '\\377'${redirect}`,null,{onData:()=>{}});
 const capture=shell.captures[0];
 assert.equal(capture[stream],'');assert.equal(capture[`${stream}Encoding`],'INVALID_UTF8');
 assert.deepEqual(Buffer.from(capture[`${stream}BytesBase64`],'base64'),Buffer.from([0xff]));
 assert.equal(shell.modelFailure.reason,`SHELL_${stream.toUpperCase()}_INVALID_UTF8`);assert.equal(shell.failed,false);assert.equal(budget.report().kastInvocations,0);
}));

test('restricted capture preserves a leading UTF-8 BOM as data',supported,()=>fixture(async shell=>{
 await shell.operations.exec("printf '\\357\\273\\277'",null,{onData:()=>{}});
 assert.equal(shell.captures[0].stdout,'\uFEFF');assert.equal(shell.modelFailure,null);
}));
