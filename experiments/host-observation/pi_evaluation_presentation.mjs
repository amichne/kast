// Presentation of one already admitted source. Canonical admission remains in
// the existing policy; this boundary checks identity, supported view and bounds.
import { createHash } from 'node:crypto';
import { decodeEnvelope, readToolName } from './pi_evaluation_policy.mjs';
import { queryPresentation } from '../../cli/src/main/js/query-presentation.mjs';

const sha=value=>createHash('sha256').update(value).digest('hex');
const failed=failure=>({type:'failure',failure});
export function finalQueryPresentation(messages,binding,maximumBytes) {
  if(!binding||!Array.isArray(messages))return failed('SOURCE_UNAVAILABLE');
  const matches=messages.filter(message=>message?.role==='toolResult'&&readToolName(message.toolName)==='query_symbols'&&message.toolCallId===binding.callId);
  if(matches.length!==1)return failed('SOURCE_IDENTITY_MISMATCH');
  const source=matches[0];
  const issued=messages.flatMap(message=>message?.role==='assistant'&&Array.isArray(message.content)
    ?message.content.filter(block=>block?.type==='toolCall'&&block.id===binding.callId).map(call=>({message,call})):[]);
  if(issued.length!==1||issued[0].call.name!==source.toolName)return failed('SOURCE_IDENTITY_MISMATCH');
  const envelope=decodeEnvelope(source.content,source.toolName,source.details,source.structuredContent);
  const canonical=JSON.stringify(envelope);
  if(canonical!==binding.canonical)return failed('SOURCE_IDENTITY_MISMATCH');
  if(Buffer.byteLength(canonical)>maximumBytes)return failed('SOURCE_LIMIT');
  const replies=envelope.type==='query_delivery'?[envelope.initial,...envelope.pages]:[envelope];
  if(replies.some(reply=>!reply?.document||!['symbols','occurrences'].includes(
    (reply.document.question??reply.document.rejection?.detail?.evidence?.question)?.output?.type)))return failed('UNSUPPORTED_FAMILY');
  const text=queryPresentation(envelope,false,true);
  // The shared renderer's canonical fallback preserves truth but is not a
  // proven compact final view. Refuse rather than erase evidence or raise caps.
  if(text===canonical||Buffer.byteLength(text)>Math.min(maximumBytes,262144))return failed('PRESENTATION_LIMIT');
  const projectedSource={...source,content:[{type:'text',text}]};
  return {type:'projected',text,messages:messages.map(message=>message===source?projectedSource:message),
    issuedMessages:[{...issued[0].message,content:[issued[0].call]},projectedSource],observation:{canonicalSha256:sha(canonical),
      callIdSha256:sha(binding.callId),originalTextBytes:Buffer.byteLength(source.content[0].text),
      projectedTextBytes:Buffer.byteLength(text),projectedTextSha256:sha(text)}};
}

// The selected runtime's real converter owns ID normalization. This probe uses
// the actual issued call and result, including the source assistant metadata;
// it never manufactures an ID or changes the original full context.
export function finalProviderIdentity(view,model,projectProviderInput) {
  if(typeof projectProviderInput!=='function')return failed('PROVIDER_IDENTITY_UNAVAILABLE');
  let input;
  try {input=projectProviderInput(model,view.issuedMessages);} catch {return failed('PROVIDER_IDENTITY_UNAVAILABLE');}
  if(!Array.isArray(input))return failed('PROVIDER_IDENTITY_MISMATCH');
  const calls=input.filter(item=>item?.type==='function_call'),outputs=input.filter(item=>item?.type==='function_call_output');
  if(calls.length!==1||outputs.length!==1||readToolName(calls[0].name)!=='query_symbols'||
    typeof calls[0].call_id!=='string'||!calls[0].call_id.length||outputs[0].call_id!==calls[0].call_id||
    outputs[0].output!==view.text)return failed('PROVIDER_IDENTITY_MISMATCH');
  return {type:'bound',callId:calls[0].call_id,observation:{providerCallIdSha256:sha(calls[0].call_id)}};
}
