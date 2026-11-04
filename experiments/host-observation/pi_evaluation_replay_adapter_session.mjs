import { spawn } from 'node:child_process';
import { PassThrough } from 'node:stream';
import os from 'node:os';
import path from 'node:path';

const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const exact=(value,keys)=>object(value)&&Object.keys(value).length===keys.length&&keys.every(key=>Object.hasOwn(value,key));
const bounded=value=>Number.isSafeInteger(value)&&value>0&&value<=2147483647;
const adapterCauses=new Set(['INVALID_REQUEST','INVALID_UTF8','FRAME_TOO_LARGE','INCOMPLETE_FRAME','REQUEST_LIMIT','ENCODING_REJECTED','ADMISSION_UNAVAILABLE','RESPONSE_TOO_LARGE','INPUT_IO']);
const failure=(operation,reason,extra={})=>Object.freeze({stage:'REPLAY_ADAPTER',operation,reason,...extra});

// Each session owns one non-detached child. Its worker remains the process-group
// owner. The protocol carries canonical JSON only inside an opaque string.
export async function startReplayAdapter(manifest,options={}) {
 if(!object(options))return {type:'failure',failure:failure('CONFIG','LIMIT_REJECTED'),ownedChildReaped:true,spawned:false};
 const {signal,maximumFrameBytes=1048576,maximumStderrBytes=65536,readyMillis=15000,responseMillis=15000,shutdownMillis=1000,format='TOON',cwd}=options;
 const early=(operation,reason)=>({type:'failure',failure:failure(operation,reason),ownedChildReaped:true,spawned:false});
 if(!object(manifest)||!Array.isArray(manifest.command)||manifest.command.length===0||manifest.command.some(value=>typeof value!=='string'||value.length===0)||!path.isAbsolute(manifest.command[0]))return early('CONFIG','MANIFEST_REJECTED');
 if(![maximumFrameBytes,maximumStderrBytes,readyMillis,responseMillis,shutdownMillis].every(bounded)||maximumFrameBytes>1048576||maximumStderrBytes>65536||shutdownMillis>1073741823||!['TOON','JSON'].includes(format)||(cwd!==undefined&&(typeof cwd!=='string'||!path.isAbsolute(cwd)))||(signal!==undefined&&(typeof signal?.aborted!=='boolean'||typeof signal?.addEventListener!=='function'||typeof signal?.removeEventListener!=='function')))return early('CONFIG','LIMIT_REJECTED');
 if(signal?.aborted)return early('CANCEL','CANCELLED');
 let child;
 try{child=spawn(manifest.command[0],[...manifest.command.slice(1),'stdio'],{cwd,detached:false,
  env:{PATH:path.dirname(manifest.command[0]),HOME:os.tmpdir(),KAST_OUTPUT_TOON:format==='JSON'?'0':'1'},stdio:['pipe','pipe','pipe']});}
 catch{return early('SPAWN','SPAWN_FAILED');}
 let terminal=null,closed=false,closing=false,ready=false,buffer='',stderrBytes=0,exitCode=null,exitSignal=null;
 let pending=null,readyResolve,stopPromise=null,closeResolve;
 const readyPromise=new Promise(resolve=>{readyResolve=resolve;});
 const childClosed=new Promise(resolve=>{closeResolve=resolve;});
 const counts={admit:0,render:0};
 const evidence=()=>({ownedChildReaped:closed,spawned:Number.isSafeInteger(child.pid)&&child.pid>0,exitCode,signal:exitSignal,stderrBytes});
 const resultFailure=()=>({type:'failure',failure:terminal,...evidence()});
 const kill=name=>{if(!closed){try{child.kill(name);}catch{terminal??=failure('CLOSE','SHUTDOWN_FAILED');}}};
 const stop=()=>{
  if(stopPromise)return stopPromise;
  closing=true;
  stopPromise=(async()=>{
   let termTimer,killTimer;
   try{
    if(!closed){
     try{child.stdin.end();}catch{terminal??=failure('WRITE','WRITE_FAILED');}
     termTimer=setTimeout(()=>kill('SIGTERM'),shutdownMillis);
     killTimer=setTimeout(()=>kill('SIGKILL'),shutdownMillis*2);
    }
    await childClosed;
   }finally{clearTimeout(termTimer);clearTimeout(killTimer);signal?.removeEventListener('abort',cancel);}
  })();
  return stopPromise;
 };
 const fail=(operation,reason,extra={})=>{
  terminal??=failure(operation,reason,extra);
  if(pending){clearTimeout(pending.timer);const resolve=pending.resolve;pending=null;resolve();}
  readyResolve();void stop();
 };
 const cancel=()=>fail('CANCEL','CANCELLED');
 const frame=line=>{
  if(terminal)return;
  let value;try{value=JSON.parse(line);}catch{fail('FRAME','FRAME_INVALID');return;}
  if(!ready){
   if(exact(value,['type'])&&value.type==='ready'){ready=true;readyResolve();}
   else fail('READY','READY_REJECTED');
   return;
  }
  if(exact(value,['type','cause'])&&value.type==='rejected'&&adapterCauses.has(value.cause)){fail(pending?.kind==='admit'?'ADMIT':pending?.kind==='render'?'RENDER':'FRAME','ADAPTER_REJECTED',{cause:value.cause});return;}
  if(!pending){fail('FRAME','FRAME_EXCESS');return;}
  const valid=pending.kind==='admit'?exact(value,['type','receipt'])&&value.type==='admission'&&object(value.receipt):exact(value,['type','text'])&&value.type==='rendered'&&typeof value.text==='string';
  if(!valid){fail('FRAME','FRAME_UNEXPECTED');return;}
  const waiting=pending;pending=null;clearTimeout(waiting.timer);waiting.value=value;waiting.resolve();
 };
 const output=new PassThrough(),decoder=new TextDecoder('utf-8',{fatal:true});
 output.setEncoding('utf8');
 child.stdout.on('data',chunk=>{
  if(terminal)return;
  try{decoder.decode(chunk,{stream:true});output.write(chunk);}catch{fail('FRAME','FRAME_INVALID');}
 });
 child.stdout.on('end',()=>{try{decoder.decode();}catch{fail('FRAME','FRAME_INVALID');}output.end();});
 output.on('data',chunk=>{
  if(terminal)return;
  buffer+=chunk;
  for(;;){
   const newline=buffer.indexOf('\n');
   if(newline<0){if(Buffer.byteLength(buffer)>maximumFrameBytes)fail('FRAME','FRAME_TOO_LARGE');return;}
   const line=buffer.slice(0,newline);buffer=buffer.slice(newline+1);
   if(Buffer.byteLength(line)>maximumFrameBytes){fail('FRAME','FRAME_TOO_LARGE');return;}
   frame(line);if(terminal)return;
  }
 });
 child.stderr.on('data',chunk=>{stderrBytes+=chunk.length;if(stderrBytes>maximumStderrBytes)fail('STDERR','STDERR_LIMIT');});
 child.stdin.on('error',()=>{if(!closing)fail('WRITE','WRITE_FAILED');});
 child.once('error',()=>fail('SPAWN','SPAWN_FAILED'));
 child.once('close',(code,childSignal)=>{
  closed=true;exitCode=code;exitSignal=childSignal;
  if(buffer.length&&!terminal)terminal=failure('FRAME','INCOMPLETE_FRAME');
  if(!closing&&!terminal)terminal=failure('EXIT','CHILD_EXIT');
  if(closing&&!terminal&&(code!==0||childSignal!==null))terminal=failure('CLOSE','SHUTDOWN_FAILED');
  if(pending){clearTimeout(pending.timer);const resolve=pending.resolve;pending=null;resolve();}
  readyResolve();closeResolve();signal?.removeEventListener('abort',cancel);
 });
 signal?.addEventListener('abort',cancel,{once:true});if(signal?.aborted)cancel();
 const readyTimer=setTimeout(()=>fail('READY','READY_TIMEOUT'),readyMillis);
 await readyPromise;clearTimeout(readyTimer);
 if(terminal){await stop();return resultFailure();}
 const request=async(kind,payload)=>{
  if(terminal){await stop();return resultFailure();}
  if(closing){fail('REQUEST','SESSION_CLOSED');await stop();return resultFailure();}
  if(pending){fail('REQUEST','REQUEST_IN_FLIGHT');await stop();return resultFailure();}
  if(counts[kind]){fail(kind==='admit'?'ADMIT':'RENDER','REQUEST_LIMIT');await stop();return resultFailure();}
  let encoded;
  try{encoded=JSON.stringify(payload);}catch{fail('REQUEST','REQUEST_REJECTED');await stop();return resultFailure();}
  if(Buffer.byteLength(encoded)>maximumFrameBytes){fail('REQUEST','FRAME_TOO_LARGE');await stop();return resultFailure();}
  counts[kind]++;
  let resolve;const response=new Promise(done=>{resolve=done;});
  const waiting={kind,resolve,value:null,timer:setTimeout(()=>fail(kind==='admit'?'ADMIT':'RENDER','RESPONSE_TIMEOUT'),responseMillis)};
  pending=waiting;
  try{child.stdin.write(encoded+'\n',error=>{if(error)fail('WRITE','WRITE_FAILED');});}
  catch{fail('WRITE','WRITE_FAILED');}
  await response;
  if(terminal){await stop();return resultFailure();}
  return waiting.value;
 };
 const invalid=async()=>{fail('REQUEST','REQUEST_REJECTED');await stop();return resultFailure();};
 const session=Object.freeze({
  admit:input=>exact(input,['argv','stdin','directory'])&&Array.isArray(input.argv)&&input.argv.every(value=>typeof value==='string')&&typeof input.stdin==='string'&&typeof input.directory==='string'
   ?request('admit',{type:'admit',argv:input.argv,stdin:input.stdin,directory:input.directory}):invalid(),
  render:(document,requestedFormat)=>typeof document==='string'&&['TOON','JSON'].includes(requestedFormat)
   ?request('render',{type:'render',document,format:requestedFormat}):invalid(),
  close:async()=>{
   if(pending)fail('CLOSE','REQUEST_IN_FLIGHT');
   await stop();return terminal?resultFailure():{type:'closed',...evidence()};
  },
 });
 return {type:'ready',session};
}
