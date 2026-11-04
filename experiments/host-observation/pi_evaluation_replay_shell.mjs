import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { spawn } from 'node:child_process';

const literal=value=>JSON.stringify(value);
// Seatbelt is the existing macOS restricted executor used by host-observation.
// This profile grants only the shell runtime, fixture files, and one broker socket.
export function replayShellProfile(directory,socket,node=process.execPath) {
 const reads=['/System','/usr/lib','/usr/share/locale',path.dirname(node),directory];
 return `(version 1)(allow default)(deny network*)(deny file-read* file-write*)(deny process-exec)(allow file-read-metadata)`+
  `(allow file-read* ${reads.map(p=>`(subpath ${literal(p)})`).join(' ')} (literal "/") (literal "/dev/null") (literal "/dev/urandom") (literal "/bin/bash") (literal "/private/etc/localtime"))`+
  `(allow file-write* (subpath ${literal(directory)}) (literal "/dev/null"))`+
  `(allow file-read* file-write* (literal ${literal(socket)}))`+
  `(allow network-outbound (remote unix-socket (path ${literal(socket)})))`+
  `(deny file-write* (literal ${literal(path.join(directory,'kast'))}))`+
  `(allow process-exec (literal "/bin/bash") (literal ${literal(node)}) (literal ${literal(path.join(directory,'kast'))}))`;
}
export const driverSource=node=>`#!${node}
import fs from 'node:fs';
import net from 'node:net';
const client=net.createConnection(process.env.KAST_REPLAY_SOCKET);
let data='';client.on('connect',()=>client.end(JSON.stringify({argv:process.argv.slice(2),stdin:fs.readFileSync(0,'utf8'),directory:process.cwd()})));
client.on('data',chunk=>{data+=chunk;if(data.length>1048576)client.destroy();});
client.on('end',()=>{try{const result=JSON.parse(data);process.stdout.write(result.stdout);process.stderr.write(result.stderr);process.exitCode=result.exitStatus;}catch{process.stderr.write('REPLAY_DRIVER_FAILURE\\n');process.exitCode=70;}});
client.on('error',()=>{process.stderr.write('REPLAY_DRIVER_UNAVAILABLE\\n');process.exitCode=70;});
`;
export async function createReplayShell({directory,socket,budget,admit,transition}) {
 fs.mkdirSync(directory,{mode:0o700});
 fs.writeFileSync(path.join(directory,'kast'),driverSource(process.execPath),{mode:0o700});
 const invocations=[];let failed=false;
 const server=net.createServer({allowHalfOpen:true},client=>{
  const allowed=budget.invocation();
  let input='';client.on('data',chunk=>{input+=chunk;if(input.length>65536){failed=true;client.destroy();}});
  client.on('end',async()=>{
   // Debit at driver ingress, before decoding or production parsing.
   if(!allowed){client.end(JSON.stringify({stdout:'',stderr:'REPLAY_INVOCATION_LIMIT\n',exitStatus:75}));return;}
   try{
    const request=JSON.parse(input);
    if(!request||Object.keys(request).sort().join(',')!=='argv,directory,stdin'||request.directory!==directory
     ||!Array.isArray(request.argv)||request.argv.length>256||request.argv.some(value=>typeof value!=='string')||typeof request.stdin!=='string'){
     client.end(JSON.stringify({stdout:'',stderr:'REPLAY_DRIVER_REQUEST_REJECTED\n',exitStatus:2}));return;
    }
    invocations.push({request});
    const admission=await admit(request);invocations.at(-1).admission=admission;
    const result=admission.type==='ADMITTED'||admission.type==='admitted'?await transition(admission):admission;
    invocations.at(-1).result=result;client.end(JSON.stringify(result));
   }catch{failed=true;client.end(JSON.stringify({stdout:'',stderr:'REPLAY_ADAPTER_UNAVAILABLE\n',exitStatus:70}));}
  });client.on('error',()=>{failed=true;});
 });
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(socket,resolve);});
 const captures=[];
 return {invocations,captures,get failed(){return failed;},
  operations:{exec:async(command,cwd,{onData,signal,timeout})=>{
   if(!budget.shell())return {exitCode:75};
   const capture={command,stdout:'',stderr:'',exitStatus:null,startedMs:performance.now(),durationMs:null};captures.push(capture);
   const env={PATH:directory,HOME:directory,TMPDIR:directory,LANG:'C.UTF-8',KAST_REPLAY_SOCKET:socket};
   const child=spawn('/usr/bin/sandbox-exec',['-p',replayShellProfile(directory,socket),'/bin/bash','--noprofile','--norc','-c',command],{cwd:directory,env,stdio:['ignore','pipe','pipe']});
   const stop=()=>child.kill('SIGKILL');signal?.addEventListener('abort',stop,{once:true});
   const timer=setTimeout(stop,Math.min((timeout??60)*1000,60000));
   child.stdout.on('data',chunk=>{capture.stdout+=chunk;onData(chunk);if(Buffer.byteLength(capture.stdout)>65536)stop();});
   child.stderr.on('data',chunk=>{capture.stderr+=chunk;onData(chunk);if(Buffer.byteLength(capture.stderr)>65536)stop();});
   try{return await new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',code=>{capture.exitStatus=code;resolve({exitCode:code});});});}
   finally{capture.durationMs=performance.now()-capture.startedMs;clearTimeout(timer);signal?.removeEventListener('abort',stop);}
  }},
  dispose:()=>new Promise(resolve=>server.close(resolve))};
}
