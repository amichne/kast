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
const chunks=[];let bytes=0,stopped=false;
const reject=reason=>{if(stopped)return;stopped=true;process.stderr.write(reason+'\\n');process.exitCode=70;client.destroy();};
client.on('connect',()=>{
 const argv=process.argv.slice(2),directory=process.cwd(),input=[],block=Buffer.alloc(16384);let size=0,truncated=false;
 for(;;){const count=fs.readSync(0,block,0,Math.min(block.length,65537-size),null);if(count===0)break;input.push(Buffer.from(block.subarray(0,count)));size+=count;if(size>65536){truncated=true;break;}}
 const raw=Buffer.concat(input);let request;
 if(truncated)request={type:'stdin_rejected',reason:'BYTE_LIMIT',argv,directory,stdinBase64:raw.toString('base64'),stdinBytesRead:size,stdinTruncated:true};
 else try{request={argv,stdin:new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(raw),directory};}
 catch{request={type:'stdin_rejected',reason:'INVALID_UTF8',argv,directory,stdinBase64:raw.toString('base64'),stdinBytesRead:size,stdinTruncated:false};}
 client.end(JSON.stringify(request));
});
client.on('data',chunk=>{if(stopped)return;bytes+=chunk.length;if(bytes>1048576){reject('REPLAY_DRIVER_RESPONSE_LIMIT');return;}chunks.push(chunk);});
client.on('end',()=>{
 if(stopped)return;let text;
 try{text=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(Buffer.concat(chunks));}catch{reject('REPLAY_DRIVER_RESPONSE_INVALID_UTF8');return;}
 try{const result=JSON.parse(text);process.stdout.write(result.stdout);process.stderr.write(result.stderr);process.exitCode=result.exitStatus;}
 catch{reject('REPLAY_DRIVER_FAILURE');}
});
client.on('error',()=>reject('REPLAY_DRIVER_UNAVAILABLE'));
`;
export async function createReplayShell({directory,socket,budget,admit,transition}) {
 fs.mkdirSync(directory,{mode:0o700});
 fs.writeFileSync(path.join(directory,'kast'),driverSource(process.execPath),{mode:0o700});
 const invocations=[],rejections=[];let failed=false,modelFailure=null;
 const modelRejected=reason=>{modelFailure??=Object.freeze({reason});};
 const server=net.createServer({allowHalfOpen:true},client=>{
  const allowed=budget.invocation();
  // The rejection envelope carries a bounded stdin prefix as base64. Normal
  // request documents keep their smaller 65536-byte allowance.
  const maximumFrameBytes=196608,chunks=[];let inputBytes=0,requestTruncated=false;
  client.on('data',chunk=>{
   const remaining=maximumFrameBytes+1-Math.min(inputBytes,maximumFrameBytes+1);inputBytes+=chunk.length;
   if(remaining>0)chunks.push(chunk.subarray(0,remaining));
   if(inputBytes>maximumFrameBytes)requestTruncated=true;
  });
  client.on('end',async()=>{
   // Debit at driver ingress, before decoding or production parsing.
   if(!allowed){client.end(JSON.stringify({stdout:'',stderr:'REPLAY_INVOCATION_LIMIT\n',exitStatus:75}));return;}
   const raw=Buffer.concat(chunks);
   const reject=(reason,code)=>{
    const kept=reason==='DRIVER_REQUEST_TOO_LARGE'?raw.subarray(0,65537):raw;
    modelRejected(reason);rejections.push({reason,requestBytesBase64:kept.toString('base64'),requestBytes:inputBytes,requestTruncated:inputBytes>kept.length});
    client.end(JSON.stringify({stdout:'',stderr:code+'\n',exitStatus:2}));
   };
   if(requestTruncated){reject('DRIVER_REQUEST_TOO_LARGE','REPLAY_DRIVER_REQUEST_LIMIT');return;}
   let text,request;
   try{text=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(raw);}catch{reject('DRIVER_REQUEST_INVALID_UTF8','REPLAY_DRIVER_REQUEST_INVALID_UTF8');return;}
   try{request=JSON.parse(text);}catch{reject('DRIVER_REQUEST_REJECTED','REPLAY_DRIVER_REQUEST_REJECTED');return;}
   try{
    if(request?.type==='stdin_rejected'&&Object.keys(request).sort().join(',')==='argv,directory,reason,stdinBase64,stdinBytesRead,stdinTruncated,type'
      &&request.directory===directory&&Array.isArray(request.argv)&&request.argv.length<=256&&request.argv.every(value=>typeof value==='string')
      &&['INVALID_UTF8','BYTE_LIMIT'].includes(request.reason)&&typeof request.stdinBase64==='string'){
     const stdin=Buffer.from(request.stdinBase64,'base64');
     if(stdin.toString('base64')!==request.stdinBase64||request.stdinBytesRead!==stdin.length
       ||(request.reason==='BYTE_LIMIT'?(stdin.length!==65537||request.stdinTruncated!==true):(stdin.length>65536||request.stdinTruncated!==false))){reject('DRIVER_REQUEST_REJECTED','REPLAY_DRIVER_REQUEST_REJECTED');return;}
     let invalid=false;try{new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(stdin);}catch{invalid=true;}
     if(request.reason==='INVALID_UTF8'&&!invalid){reject('DRIVER_REQUEST_REJECTED','REPLAY_DRIVER_REQUEST_REJECTED');return;}
     const reason=request.reason==='INVALID_UTF8'?'STDIN_INVALID_UTF8':'STDIN_BUDGET';
     modelRejected(reason);
     const result={type:'DRIVER_INPUT_REJECTED',cause:reason,stdout:'',stderr:`REPLAY_DRIVER_${reason}\n`,exitStatus:2,nativeCalls:0};
     invocations.push({request:{argv:request.argv,directory,stdinEncoding:request.reason==='INVALID_UTF8'?'INVALID_UTF8':'UNAVAILABLE',stdinBytesBase64:request.stdinBase64,stdinBytesRead:request.stdinBytesRead,stdinTruncated:request.stdinTruncated},admission:result,result});
     client.end(JSON.stringify(result));return;
    }
    if(inputBytes>65536){reject('DRIVER_REQUEST_TOO_LARGE','REPLAY_DRIVER_REQUEST_LIMIT');return;}
    if(!request||Object.keys(request).sort().join(',')!=='argv,directory,stdin'||request.directory!==directory
     ||!Array.isArray(request.argv)||request.argv.length>256||request.argv.some(value=>typeof value!=='string')||typeof request.stdin!=='string'){
     reject('DRIVER_REQUEST_REJECTED','REPLAY_DRIVER_REQUEST_REJECTED');return;
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
 return {invocations,rejections,captures,get failed(){return failed;},get modelFailure(){return modelFailure;},
  operations:{exec:async(command,cwd,{onData,signal,timeout})=>{
   if(!budget.shell())return {exitCode:75};
   const capture={command,stdout:'',stderr:'',exitStatus:null,startedMs:performance.now(),durationMs:null};captures.push(capture);
   const env={PATH:directory,HOME:directory,TMPDIR:directory,LANG:'C.UTF-8',KAST_REPLAY_SOCKET:socket};
   const child=spawn('/usr/bin/sandbox-exec',['-p',replayShellProfile(directory,socket),'/bin/bash','--noprofile','--norc','-c',command],{cwd:directory,env,stdio:['ignore','pipe','pipe']});
   const stop=()=>child.kill('SIGKILL');signal?.addEventListener('abort',stop,{once:true});
   const timer=setTimeout(stop,Math.min((timeout??60)*1000,60000));
   const streams={stdout:{chunks:[],bytes:0,kept:0,truncated:false},stderr:{chunks:[],bytes:0,kept:0,truncated:false}};
   const receive=(name,chunk)=>{
    const stream=streams[name],remaining=Math.max(0,65537-stream.kept);stream.bytes+=chunk.length;
    if(remaining>0){const kept=chunk.subarray(0,remaining);stream.chunks.push(kept);stream.kept+=kept.length;onData(kept);}
    if(stream.bytes>65536){stream.truncated=true;modelRejected('SHELL_OUTPUT_BUDGET');stop();}
   };
   child.stdout.on('data',chunk=>receive('stdout',chunk));
   child.stderr.on('data',chunk=>receive('stderr',chunk));
   try{return await new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',code=>{capture.exitStatus=code;resolve({exitCode:code});});});}
   finally{
    for(const [name,stream]of Object.entries(streams)){
     const bytes=Buffer.concat(stream.chunks);capture[`${name}Bytes`]=stream.bytes;capture[`${name}Truncated`]=stream.truncated;
     try{capture[name]=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);}catch{capture[`${name}Encoding`]='INVALID_UTF8';capture[`${name}BytesBase64`]=bytes.toString('base64');modelRejected(name==='stdout'?'SHELL_STDOUT_INVALID_UTF8':'SHELL_STDERR_INVALID_UTF8');}
     if(stream.truncated)capture[`${name}BytesBase64`]=bytes.toString('base64');
    }
    capture.durationMs=performance.now()-capture.startedMs;clearTimeout(timer);signal?.removeEventListener('abort',stop);
   }
  }},
  dispose:()=>new Promise(resolve=>server.close(resolve))};
}
