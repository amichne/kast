import { decodeEnvelope } from './pi_evaluation_policy.mjs';

const bytes=value=>Buffer.byteLength(JSON.stringify(value));
// The same exported factory is loaded by the real SDK worker and callback tests.
// It observes provider settings/byte sizes; it never logs payloads or credentials.
export function evaluationGuard(policy,record) {
  return pi=>{
    pi.on('session_start',(_event,ctx)=>{
      pi.setActiveTools(['query_symbols','check_diagnostics','health_check']);
      record({type:'session_settings',provider:ctx.model?.provider,model:ctx.model?.id,thinking:pi.getThinkingLevel(),toolNames:pi.getActiveTools()});
    });
    pi.on('before_provider_request',(event,ctx)=>{
      const payload=event.payload;
      const observation={provider:ctx.model?.provider,model:ctx.model?.id,thinking:pi.getThinkingLevel(),payloadModel:payload?.model,effort:payload?.reasoning?.effort,
        payloadBytes:bytes(payload),toolsBytes:bytes(payload.tools??[]),instructionsBytes:Buffer.byteLength(payload.instructions??''),inputBytes:bytes(payload.input??[])};
      const admission=policy.beforeProvider(observation);
      record({type:'provider_admission',observation,decision:admission});
      if(!admission.allow) ctx.abort();
    });
    pi.on('tool_call',(event,ctx)=>{
      const admission=policy.toolCall(event.toolName,event.input,event.toolCallId);
      record({type:'model_tool_proposal',toolName:event.toolName,requestType:event.input?.request?.type??null,callId:event.toolCallId,decision:admission});
      if(!admission.allow) {ctx.abort();return {block:true,reason:admission.reason};}
    });
    pi.on('tool_execution_start',event=>policy.adapterStarted(event.toolCallId));
    pi.on('tool_result',(event,ctx)=>{
      const textBytes=(event.content??[]).reduce((sum,c)=>sum+(c.type==='text'?Buffer.byteLength(c.text):0),0);
      const envelope=decodeEnvelope(event.content);
      const admission=policy.toolResult(envelope,textBytes,event.toolCallId);
      record({type:'native_reply',callId:event.toolCallId,envelopeType:envelope.type,textBytes,decision:admission});
      if(!admission.allow) ctx.abort();
    });
    pi.on('message_end',(event,ctx)=>{
      const m=event.message;
      if(m.role!=='assistant') return;
      const accounted=policy.modelUsage(m.usage);
      const ended=policy.assistantEnded(m);
      record({type:'model_response',usage:policy.report().usage,stopReason:m.stopReason,accounting:accounted,decision:ended});
      if(!accounted.allow||!ended.allow) ctx.abort();
    });
  };
}
