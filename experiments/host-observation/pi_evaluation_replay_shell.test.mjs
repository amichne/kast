import test from 'node:test';import assert from 'node:assert/strict';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import { createReplayShell } from './pi_evaluation_replay_shell.mjs';
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
 }finally{await shell.dispose();fs.rmSync(root,{recursive:true});}
});
