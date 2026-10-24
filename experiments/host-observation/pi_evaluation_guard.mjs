import { decodeEnvelope, measureToolResult, READ_TOOL_IDENTITIES, readToolName, Outcome } from './pi_evaluation_policy.mjs';

import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
import { finalQueryPresentation, finalProviderIdentity } from './pi_evaluation_presentation.mjs';
// The same exported factory is loaded by the real SDK worker and callback tests.
// It observes provider settings/byte sizes; it never logs payloads or credentials.
export function evaluationGuard(policy,record,projectProviderInput) {
  return pi=>{
    let finalProjection;
    const observe=(value,ctx)=>{
      const result=record(value);
      if(result?.type==='failure') {policy.stop(Outcome.INVALID);ctx.abort();return result;}
      return {type:'recorded'};
    };
    pi.on('session_start',(_event,ctx)=>{
      pi.setActiveTools([...READ_TOOL_IDENTITIES]);
      const recorded=observe({type:'session_settings',provider:ctx.model?.provider,model:ctx.model?.id,thinking:pi.getThinkingLevel(),toolNames:pi.getActiveTools()},ctx);
    });
    pi.on('context',(event,ctx)=>{
      finalProjection=undefined;
      if(policy.config.finalPresentation!=='compact'||!policy.isFinalAnswerDelivery())return;
      const view=finalQueryPresentation(event.messages,policy.finalQuery,policy.config.maximumToolTextBytes);
      const identity=view.type==='failure'?view:finalProviderIdentity(view,ctx.model,projectProviderInput);
      const admission=identity.type==='failure'?policy.stop(Outcome.INVALID):{allow:true,reason:'ADMITTED',phase:policy.phase};
      const recorded=observe({type:'final_projection',...(view.observation??{}),...(identity.observation??{}),failure:identity.failure??null,decision:admission},ctx);
      if(recorded.type==='failure')return;
      if(!admission.allow) {ctx.abort();return;}
      finalProjection={text:view.text,callId:identity.callId};
      return {messages:view.messages};
    });
    pi.on('before_provider_request',(event,ctx)=>{
      const payload={...event.payload,max_output_tokens:policy.providerOutputCap()};
      // Pi reconstructs declarations from transcript history. Final answers have
      // no remaining tool capability; retain history and omit only this field.
      if(policy.isFinalAnswerDelivery())delete payload.tools;
      const instruction=policy.deliveryInstruction();
      if(instruction&&typeof payload.instructions==='string')payload.instructions+='\n'+instruction;
      if(policy.config.finalPresentation==='compact'&&policy.isFinalAnswerDelivery()) {
        const selected=finalProjection&&Array.isArray(payload.input)?payload.input.filter(item=>
          item?.type==='function_call_output'&&item.call_id===finalProjection.callId):[];
        if(selected.length!==1||selected[0].output!==finalProjection.text)policy.stop(Outcome.INVALID);
      }
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
      const envelope=decodeEnvelope(event.content,event.toolName,event.details,event.structuredContent);
      const native=event.toolName!==readToolName(event.toolName);
      const measured=measureToolResult(event.content,event.toolName,envelope);
      const admission=measured.type==='invalid'?policy.stop(Outcome.INVALID):policy.toolResult(envelope,measured.bytes,event.toolCallId,event.isError);
      const recorded=observe({type:'native_reply',callId:event.toolCallId,envelopeType:envelope.type,hostIsError:typeof event.isError==='boolean'?event.isError:null,deliveryStop:envelope.delivery?.stop??null,physicalRpcsReported:envelope.delivery?.rpc_count??null,...(measured.type==='measured'?{textBytes:measured.bytes}:{}),decision:admission},ctx);
      if(recorded.type!=='failure'&&!admission.allow) ctx.abort();
      if(envelope.type!=='invalid'&&native&&measured.type==='measured'&&measured.bytes<=policy.config.maximumToolTextBytes)
        return {details:{...event.details,kastEvaluation:{type:'MCP_CANONICAL_RESULT',envelope}}};
    });
    const observeResponse=(message,ctx)=>{
      let bytes;
      try {bytes=Buffer.byteLength(JSON.stringify(message));} catch {bytes=undefined;}
      const admission=policy.providerResponseBytes(bytes);
      const recorded=observe({type:'provider_response_bytes',bytes,decision:admission},ctx);
      if(recorded.type!=='failure'&&!admission.allow) ctx.abort();
      return admission;
    };
    pi.on('message_update',(event,ctx)=>{
      if(event.message?.role==='assistant') observeResponse(event.message,ctx);
    });
    pi.on('message_end',(event,ctx)=>{
      const m=event.message;
      if(m.role!=='assistant') return;
      observeResponse(m,ctx);
      const accounted=policy.modelUsage(m.usage,m.stopReason);
      const ended=policy.assistantEnded(m);
      const recorded=observe({type:'model_response',usage:policy.report().usage,stopReason:m.stopReason,accounting:accounted,decision:ended},ctx);
      if(recorded.type!=='failure'&&(!accounted.allow||!ended.allow)) ctx.abort();
    });
  };
}
