// Observation of #995's envelope only. The production client owns pagination,
// identity/cursor validation, transport effects, cancellation and its limits.
const stops=new Set(['DELIVERED','CANCELLED','TIME_LIMIT','PAGE_LIMIT','BYTE_LIMIT','DELIVERY_UNAVAILABLE','BUDGET_INCREASE_REQUIRED','MALFORMED_PAGE','IDENTITY_MISMATCH','NON_ADVANCING']);
const rpcTypes=new Set(['complete','qualified','rejected_document','rejected']);
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const integer=value=>Number.isSafeInteger(value)&&value>=0;
export function decodeQueryDelivery(value,decodeCanonical) {
  const d=value.delivery;
  if(!object(d)||!stops.has(d.stop)||!integer(d.rpc_count)||d.rpc_count<1||d.rpc_count>64||!integer(d.request_bytes)||!integer(d.response_bytes)||!(d.result===null||typeof d.result==='string')||!Array.isArray(value.pages)||value.pages.length+1>d.rpc_count)return {type:'invalid'};
  if(value.initial===null) {
    if(d.stop!=='BYTE_LIMIT'||value.pages.length!==0||!rpcTypes.has(d.original_outcome))return {type:'invalid'};
  } else if(decodeCanonical(value.initial).type==='invalid')return {type:'invalid'};
  if(value.pages.some(page=>decodeCanonical(page).type==='invalid'))return {type:'invalid'};
  return value;
}
export function canonicalSummary(reply) {
  if(reply.type==='rejected')return {type:reply.type,failure:reply.failure};
  const document=reply.document;
  return {type:reply.type,status:document.status,coverage:structuredClone(document.coverage??null),qualification:structuredClone(document.qualification??null),
    rejection:document.rejection?{type:document.rejection.type,reason:document.rejection.reason??null,originalCoverage:structuredClone(document.rejection.detail?.originalCoverage??null),policyProgress:structuredClone(document.rejection.detail?.policyProgress??null)}:null};
}
export function deliverySummary(envelope,callId,observation) {
  const d=envelope.delivery;
  return {callId:callId??null,observation,stop:d.stop,rpcCount:d.rpc_count,requestBytes:d.request_bytes,responseBytes:d.response_bytes,retainedResultIssued:d.result!==null,
    originalOutcome:envelope.initial?.type??d.original_outcome,hostFailureExpected:d.stop!=='DELIVERED'||envelope.initial===null||['rejected','rejected_document'].includes(envelope.initial.type),
    canonicalOutcomes:[envelope.initial,...envelope.pages].filter(reply=>reply!==null).map(canonicalSummary)};
}
