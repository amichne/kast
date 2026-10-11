import { spawn } from 'node:child_process';

// A real, bounded child boundary. Only this owned process group can be killed.
// Cancellation is first cooperative; an unresponsive worker is terminated and
// reaped. No existing IDE/provider/configuration lifecycle is managed here.
export function runOwnedWorker(command,args,{cwd,env,signal,wallMillis,graceMillis=5000,terminateMillis=1000,onEvent=()=>{}}) {
  if(!Number.isSafeInteger(wallMillis)||wallMillis<=0) throw Error('Finite worker wall bound required');
  return new Promise((resolve,reject)=>{
    const child=spawn(command,args,{cwd,env,detached:process.platform!=='win32',stdio:['ignore','ignore','pipe','ipc']});
    let result,closed=false,cancelling=false,stderrBytes=0;
    const timers=[];
    const kill=signalName=>{
      try {if(process.platform==='win32') child.kill(signalName);else process.kill(-child.pid,signalName);}
      catch(error){if(error.code!=='ESRCH') reject(error);}
    };
    const cancel=()=>{
      if(closed||cancelling) return;
      cancelling=true;onEvent({type:'controller_cancel'});
      if(child.connected) child.send({type:'cancel'},()=>{});
      timers.push(setTimeout(()=>{
        kill('SIGTERM');timers.push(setTimeout(()=>kill('SIGKILL'),terminateMillis));
      },graceMillis));
    };
    child.stderr.on('data',data=>{stderrBytes+=data.length;if(stderrBytes>65536) cancel();});
    child.on('message',message=>{
      if(message.type==='report') result=message.report;
      else onEvent(message);
    });
    child.once('error',error=>{closed=true;cleanup();reject(error);});
    const cleanup=()=>{for(const timer of timers)clearTimeout(timer);signal?.removeEventListener('abort',cancel);};
    child.once('close',(code,childSignal)=>{
      closed=true;cleanup();kill('SIGKILL');
      resolve({type:result?'report':cancelling?'cancelled':'worker_failed',report:result,exitCode:code,signal:childSignal,ownedChildReaped:true,cancellationRequested:cancelling,stderrBytes});
    });
    timers.push(setTimeout(cancel,wallMillis));
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
