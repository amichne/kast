import { createHash } from 'node:crypto';

// Pi catches provider-hook exceptions. An explicit abort is necessary to stop
// its SSE transport before fetch. Returning a failure alone grants no stop.
export function admitProviderRequest(payload, {requests,totalTokens}, plan, {record,stop,abort}) {
  const body={...payload,max_output_tokens:plan.maximumOutputTokens};
  let encoded;
  try {encoded=JSON.stringify(body);} catch {
    stop('PROVIDER_PAYLOAD_REJECTED');abort();return {type:'rejected',payload:body};
  }
  const bytes=Buffer.byteLength(encoded);
  if(requests>=plan.maximumRequests||totalTokens>=plan.maximumReportedTokens||bytes>plan.maximumPayloadBytes) {
    stop('RESOURCE_LIMIT');
    try {record({type:'provider_denied',reason:'RESOURCE_LIMIT',bytes});}
    catch {stop('OBSERVATION_FAILED');}
    finally {abort();}
    return {type:'rejected',payload:body};
  }
  const sha256=createHash('sha256').update(encoded).digest('hex');
  try {record({type:'provider_request',request:requests+1,bytes,sha256});}
  catch {stop('OBSERVATION_FAILED');abort();return {type:'rejected',payload:body};}
  return {type:'admitted',payload:body,bytes};
}
