// Presentation of one already admitted source. Canonical admission remains in
// the existing policy; this boundary checks identity, supported view and bounds.
import { createHash } from 'node:crypto';
import { decodeEnvelope } from './pi_evaluation_policy.mjs';
import { queryPresentation } from '../../cli/src/main/js/query-presentation.mjs';

const sha=value=>createHash('sha256').update(value).digest('hex');
const failed=failure=>({type:'failure',failure});
export function finalQueryPresentation(messages,binding,maximumBytes) {
  if(!binding||!Array.isArray(messages))return failed('SOURCE_UNAVAILABLE');
  const matches=messages.filter(message=>message?.role==='toolResult'&&message.toolName==='query_symbols'&&message.toolCallId===binding.callId);
  if(matches.length!==1)return failed('SOURCE_IDENTITY_MISMATCH');
  const source=matches[0];
  const envelope=decodeEnvelope(source.content,source.toolName,source.details);
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
  return {type:'projected',text,messages:messages.map(message=>message===source
    ?{...message,content:[{type:'text',text}]}:message),observation:{canonicalSha256:sha(canonical),
      callIdSha256:sha(binding.callId),originalTextBytes:Buffer.byteLength(source.content[0].text),
      projectedTextBytes:Buffer.byteLength(text),projectedTextSha256:sha(text)}};
}
