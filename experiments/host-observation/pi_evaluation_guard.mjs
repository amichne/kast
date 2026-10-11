import { decodeEnvelope, READ_TOOLS, Outcome } from './pi_evaluation_policy.mjs';

import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
// The same exported factory is loaded by the real SDK worker and callback tests.
// It observes provider settings/byte sizes; it never logs payloads or credentials.
export function evaluationGuard(policy,record) {
  return pi=>{
    const observe=(value,ctx)=>{
      const result=record(value);
      if(result?.type==='failure') {policy.stop(Outcome.INVALID);ctx.abort();return result;}
      return {type:'recorded'};
    };
    pi.on('session_start',(_event,ctx)=>{
      pi.setActiveTools([...READ_TOOLS]);
      const recorded=observe({type:'session_settings',provider:ctx.model?.provider,model:ctx.model?.id,thinking:pi.getThinkingLevel(),toolNames:pi.getActiveTools()},ctx);
    });
    pi.on('before_provider_request',(event,ctx)=>{
      const payload={...event.payload,max_output_tokens:policy.providerOutputCap()};
      const inspected=inspectProviderPayload(payload,policy.maximumProviderPayloadBytes);
      const observation={provider:ctx.model?.provider,model:ctx.model?.id,thinking:pi.getThinkingLevel(),payload:inspected};
      const admission=policy.beforeProvider(observation);
      const recorded=observe({type:'provider_admission',observation,decision:admission},ctx);
      if(recorded.type!=='failure'&&!admission.allow) ctx.abort();
      return payload;
    });
    pi.on('tool_call',(event,ctx)=>{
      const admission=policy.toolCall(event.toolName,event.input,event.toolCallId);
      const recorded=observe({type:'model_tool_proposal',toolName:event.toolName,requestType:event.input?.request?.type??null,callId:event.toolCallId,decision:admission},ctx);
      if(recorded.type==='failure') return {block:true,reason:Outcome.INVALID};
      if(!admission.allow) {ctx.abort();return {block:true,reason:admission.reason};}
    });
    pi.on('tool_execution_start',event=>policy.adapterStarted(event.toolCallId));
    pi.on('tool_result',(event,ctx)=>{
      const textBytes=(event.content??[]).reduce((sum,c)=>sum+(c.type==='text'?Buffer.byteLength(c.text):0),0);
      const envelope=decodeEnvelope(event.content,event.toolName);
      const admission=policy.toolResult(envelope,textBytes,event.toolCallId,event.isError);
      const recorded=observe({type:'native_reply',callId:event.toolCallId,envelopeType:envelope.type,hostIsError:typeof event.isError==='boolean'?event.isError:null,deliveryStop:envelope.delivery?.stop??null,physicalRpcsReported:envelope.delivery?.rpc_count??null,textBytes,decision:admission},ctx);
      if(recorded.type!=='failure'&&!admission.allow) ctx.abort();
    });
    pi.on('message_end',(event,ctx)=>{
      const m=event.message;
      if(m.role!=='assistant') return;
      const accounted=policy.modelUsage(m.usage,m.stopReason);
      const ended=policy.assistantEnded(m);
      const recorded=observe({type:'model_response',usage:policy.report().usage,stopReason:m.stopReason,accounting:accounted,decision:ended},ctx);
      if(recorded.type!=='failure'&&(!accounted.allow||!ended.allow)) ctx.abort();
    });
  };
}
