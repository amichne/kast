// Generated contract version; owned by packaging/generate-public-query.py.
const PUBLIC_TOOL_CONTRACT_VERSION = 17;
// Generated loading policy; owned by tools.schema.json.
const DEFERRED_TOOL_NAMES: readonly string[] = ["query_symbols", "add_declaration", "replace_body"];
// Generated from ToolRpcFailure; owned by packaging/generate-public-query.py.
const TOOL_RPC_FAILURES = [
  "INSTALLATION_STOPPED",
  "OBSERVATION_UNAVAILABLE",
  "CATALOG_UNAVAILABLE",
  "INVALID_COMMAND",
  "REQUEST_TOO_LARGE",
  "UNKNOWN_TOOL",
  "INVALID_ARGUMENTS",
  "OUT_OF_SCOPE",
  "INVOCATION_FAILED",
  "INVALID_RESULT",
  "QUERY_SESSION_TRANSITION_REJECTED",
] as const;
// End generated ToolRpcFailure.
import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

type Tool = {
  name: string;
  description: string;
  effect: "READ" | "WRITE";
  inputSchema: Record<string, unknown>;
};

// Generated query delivery; owned by cli/src/main/js/query-delivery.mjs.
// Generated outer delivery contract; owned by cli/src/main/resources/query-delivery.schema.json.
const QUERY_DELIVERY_SCHEMA = {"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://kast.dev/contracts/query-delivery.schema.json","title":"Outer query output delivery","description":"Client output delivery only; DELIVERED does not establish canonical semantic completion. Physical RPC accounting may exceed the number of received replies.","x-page-count-within-rpc-count":true,"$defs":{"CanonicalReply":{"type":"object","required":["type"],"properties":{"type":{"enum":["complete","qualified","rejected_document","rejected"]}},"additionalProperties":true,"description":"Opaque canonical RPC reply: the owning RPC decoder admits all inner semantics separately."},"SemanticHeader":{"type":"object","additionalProperties":false,"required":["original_outcome","completion","authority","coverage","blockers","omissions","presentation","recovery"],"properties":{"original_outcome":{"enum":["complete","qualified","rejected_document","rejected"]},"completion":{"type":"object","additionalProperties":false,"required":["policy","model","interpretation","cause"],"properties":{"policy":{"enum":["COMPLETE_ONLY","UNAVAILABLE"]},"model":{"enum":["COMPILER_RESOLVED_STATIC_V1","UNAVAILABLE"]},"interpretation":{"type":["string","null"],"maxLength":128},"cause":{"type":["string","null"],"maxLength":128}}},"authority":{"oneOf":[{"type":"object","additionalProperties":false,"required":["type","result"],"properties":{"type":{"const":"RETAINED_RESULT"},"result":{"type":"string","maxLength":128}}},{"type":"object","additionalProperties":false,"required":["type","root","host","epoch","contentView","version"],"properties":{"type":{"const":"LIVE"},"root":{"type":"string","maxLength":4096},"host":{"type":"string","maxLength":128},"epoch":{"type":"integer","minimum":0,"maximum":9007199254740991},"contentView":{"type":"string","maxLength":128},"version":{"type":"integer","minimum":0,"maximum":9007199254740991}}},{"type":"object","additionalProperties":false,"required":["type"],"properties":{"type":{"const":"UNAVAILABLE"}}},{"type":"object","additionalProperties":false,"required":["type","workspace_digest","host","epoch","contentView","version"],"properties":{"type":{"const":"LIVE_DIGEST"},"workspace_digest":{"type":"string","pattern":"^[0-9a-f]{64}$","maxLength":64},"host":{"type":"string","maxLength":36},"epoch":{"type":"integer","minimum":1,"maximum":9007199254740991},"contentView":{"enum":["SAVED_PSI_COMMITTED"]},"version":{"type":"integer","minimum":1,"maximum":1}}}]},"coverage":{"type":"object","additionalProperties":false,"required":["type","exhaustive","known_minimum","limitations","progress"],"properties":{"type":{"enum":["COMPLETE","QUALIFIED","UNKNOWN"]},"exhaustive":{"type":["boolean","null"]},"known_minimum":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},"limitations":{"type":"array","maxItems":128,"items":{"type":"string","maxLength":128}},"progress":{"type":"object","additionalProperties":false,"required":["type","reason","next_action"],"properties":{"type":{"type":["string","null"],"maxLength":128},"reason":{"type":["string","null"],"maxLength":128},"next_action":{"type":["string","null"],"maxLength":128}}}}},"blockers":{"type":"object","additionalProperties":false,"required":["failure","rejection","cause","stop","qualification","codes","failure_count","kinds"],"properties":{"failure":{"type":["string","null"],"maxLength":128},"rejection":{"type":["string","null"],"maxLength":128},"cause":{"type":["string","null"],"maxLength":128},"stop":{"type":["string","null"],"maxLength":128},"qualification":{"type":["string","null"],"maxLength":128},"codes":{"type":"array","maxItems":128,"items":{"type":"string","maxLength":128}},"failure_count":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},"kinds":{"type":"array","maxItems":128,"items":{"type":"string","maxLength":128}}}},"omissions":{"type":"object","additionalProperties":false,"required":["count","codes"],"properties":{"codes":{"type":"array","maxItems":128,"items":{"type":"string","maxLength":128}},"count":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991}}},"presentation":{"type":"object","additionalProperties":false,"required":["rows","accumulated_rows","evidence_presented","evidence_total","detail","deferred_count","detail_read"],"properties":{"rows":{"type":"integer","minimum":0,"maximum":9007199254740991},"accumulated_rows":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},"evidence_presented":{"type":"integer","minimum":0,"maximum":9007199254740991},"evidence_total":{"type":["integer","null"],"minimum":0,"maximum":9007199254740991},"detail":{"enum":["INLINE","DEFERRED_DETAIL","UNAVAILABLE"]},"deferred_count":{"type":"integer","minimum":0,"maximum":9007199254740991},"detail_read":{"enum":["NONE","FRAGMENT","FINAL_FRAGMENT"]}}},"recovery":{"oneOf":[{"type":"object","additionalProperties":false,"required":["type","result","cursor","evidence_cursor"],"properties":{"type":{"const":"READ_RESULT"},"result":{"type":"string","maxLength":128},"cursor":{"type":"integer","minimum":0,"maximum":9007199254740991},"evidence_cursor":{"type":"integer","minimum":0,"maximum":9007199254740991}}},{"type":"object","additionalProperties":false,"required":["type","continuation"],"properties":{"type":{"const":"RESUME"},"continuation":{"type":"string","maxLength":128}}},{"type":"object","additionalProperties":false,"required":["type","reason"],"properties":{"type":{"const":"UNAVAILABLE"},"reason":{"enum":["NO_RETAINED_AUTHORITY","AUTHORITY_REJECTED"]}}}]}}},"QueryPresentationHeader":{"type":"object","additionalProperties":false,"required":["type","operation","status","delivery_stop","semantic","binding"],"properties":{"type":{"const":"QUERY_PRESENTATION_HEADER"},"operation":{"const":"query.run"},"status":{"enum":["complete","qualified","rejected"]},"delivery_stop":{"enum":["BYTE_LIMIT","DETAIL_SECTION"]},"semantic":{"$ref":"#/$defs/SemanticHeader"},"binding":{"oneOf":[{"type":"object","additionalProperties":false,"required":["type","result"],"properties":{"type":{"const":"RETAINED_RESULT"},"result":{"type":"string","maxLength":128}}},{"type":"object","additionalProperties":false,"required":["type","root","host","epoch","contentView","version"],"properties":{"type":{"const":"LIVE"},"root":{"type":"string","maxLength":4096},"host":{"type":"string","maxLength":128},"epoch":{"type":"integer","minimum":0,"maximum":9007199254740991},"contentView":{"type":"string","maxLength":128},"version":{"type":"integer","minimum":0,"maximum":9007199254740991}}},{"type":"object","additionalProperties":false,"required":["type"],"properties":{"type":{"const":"UNAVAILABLE"}}},{"type":"object","additionalProperties":false,"required":["type","workspace_digest","host","epoch","contentView","version"],"properties":{"type":{"const":"LIVE_DIGEST"},"workspace_digest":{"type":"string","pattern":"^[0-9a-f]{64}$","maxLength":64},"host":{"type":"string","maxLength":36},"epoch":{"type":"integer","minimum":1,"maximum":9007199254740991},"contentView":{"enum":["SAVED_PSI_COMMITTED"]},"version":{"type":"integer","minimum":1,"maximum":1}}}]}}}},"oneOf":[{"type":"object","additionalProperties":false,"required":["type","initial","pages","delivery","semantic"],"properties":{"type":{"const":"query_delivery"},"initial":{"$ref":"#/$defs/CanonicalReply"},"pages":{"type":"array","maxItems":63,"items":{"$ref":"#/$defs/CanonicalReply"}},"delivery":{"type":"object","additionalProperties":false,"required":["stop","rpc_count","request_bytes","response_bytes","result"],"properties":{"stop":{"enum":["DELIVERED","CANCELLED","TIME_LIMIT","PAGE_LIMIT","BYTE_LIMIT","DELIVERY_UNAVAILABLE","BUDGET_INCREASE_REQUIRED","MALFORMED_PAGE","IDENTITY_MISMATCH","NON_ADVANCING"]},"rpc_count":{"type":"integer","minimum":1,"maximum":64},"request_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"response_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"result":{"type":["string","null"]}}},"semantic":{"$ref":"#/$defs/SemanticHeader"}}},{"type":"object","additionalProperties":false,"required":["type","initial","pages","delivery","semantic"],"properties":{"type":{"const":"query_delivery"},"initial":{"type":"null"},"pages":{"type":"array","maxItems":0,"items":{"$ref":"#/$defs/CanonicalReply"}},"delivery":{"type":"object","additionalProperties":false,"required":["stop","rpc_count","request_bytes","response_bytes","result","original_outcome"],"properties":{"stop":{"const":"BYTE_LIMIT"},"rpc_count":{"type":"integer","minimum":1,"maximum":64},"request_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"response_bytes":{"type":"integer","minimum":0,"maximum":9007199254740991},"result":{"type":["string","null"]},"original_outcome":{"enum":["complete","qualified","rejected_document","rejected"]}}},"semantic":{"$ref":"#/$defs/SemanticHeader"}}}]};

const queryDeliveryInvalid = reason => ({ type: 'invalid', reason });
function queryDeliveryMatches(value, node) {
  if (node.$ref) return queryDeliveryMatches(value, QUERY_DELIVERY_SCHEMA.$defs[node.$ref.slice('#/$defs/'.length)]);
  if (node.oneOf) return node.oneOf.filter(branch => queryDeliveryMatches(value, branch)).length === 1;
  if ('const' in node && value !== node.const || node.enum && !node.enum.includes(value)) return false;
  const types = Array.isArray(node.type) ? node.type : node.type ? [node.type] : [];
  const matchesType = type => type === 'null' ? value === null
    : type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value)
    : type === 'array' ? Array.isArray(value)
    : type === 'integer' ? Number.isSafeInteger(value) : typeof value === type;
  if (types.length && !types.some(matchesType)) return false;
  if (node.minimum != null && value < node.minimum || node.maximum != null && value > node.maximum) return false;
  if (node.maxLength != null && typeof value === 'string' && Array.from(value).length > node.maxLength) return false;
  if (node.pattern != null && typeof value === 'string' && !new RegExp(node.pattern).test(value)) return false;
  if (node.type === 'object') {
    if (node.required.some(key => !Object.hasOwn(value, key))) return false;
    if (node.additionalProperties === false && Object.keys(value).some(key => !Object.hasOwn(node.properties, key))) return false;
    for (const [key, child] of Object.entries(node.properties)) {
      if (Object.hasOwn(value, key) && !queryDeliveryMatches(value[key], child)) return false;
    }
  }
  if (node.type === 'array' && (value.length > node.maxItems || Array.from(value).some(item => !queryDeliveryMatches(item, node.items)))) return false;
  return true;
}

/** Outer admission preserves opaque canonical replies; their authority stays with the RPC owner. */
function admitQueryDeliveryEnvelope(value) {
  if (!queryDeliveryMatches(value, QUERY_DELIVERY_SCHEMA)) return queryDeliveryInvalid('INVALID_OUTER_SHAPE');
  if (value.pages.length + 1 > value.delivery.rpc_count) return queryDeliveryInvalid('INVALID_RPC_ACCOUNTING');
  if(value.semantic.original_outcome!==(value.initial?.type??value.delivery.original_outcome)) return queryDeliveryInvalid('SEMANTIC_OUTCOME_MISMATCH');
  return value;
}

/** Serialization owner for already admitted canonical replies; no inner document is projected or rewritten. */
function createQueryDeliveryEnvelope(initial, pages, delivery, semantic) {
  return admitQueryDeliveryEnvelope({ type: 'query_delivery', initial, pages, delivery,
    semantic: semantic ?? querySemanticHeader(initial, pages, delivery) });
}

// The RPC owner admits the input. This projection copies finite facts, never a question or witness tree.
function querySemanticHeader(initial, pages, delivery) {
  if (!initial) return null;
  const document=initial.document ?? {};
  const rejection=document.rejection;
  const detail=rejection?.detail;
  const evidence=detail?.evidence?.type==='RETAINED'?detail.evidence:null;
  const question=document.question??evidence?.question;
  const interpretation=document.interpretation;
  const originalCoverage=detail?.originalCoverage??interpretation?.originalCoverage;
  const coverage=originalCoverage??document.coverage;
  const progress=coverage?.progress??document.qualification?.progress;
  const replies=[initial,...pages];
  const documents=replies.map(reply=>reply.document).filter(Boolean);
  const families=['failures','omissions','walk_observations','reference_observations','discovery_observations','relation_observations'];
  const entries=field=>documents.flatMap(value=>Array.isArray(value[field])?value[field]:[]);
  const code=value=>typeof value==='string'?value:value?.type??null;
  const codes=values=>[...new Set(values.map(code).filter(value=>value!==null))];
  const failures=entries('failures'),omissions=entries('omissions');
  const original=document.original_evidence_summary??detail?.original_evidence_summary??detail?.evidence?.original_evidence_summary_state?.summary;
  const originalFailureCodes=original?Object.entries(original).filter(([key])=>key!=='omission_reasons'&&(key.endsWith('_reasons')||key==='source_limitations')).flatMap(([,value])=>value):[];
  const result=delivery.result??evidence?.result??(document.retention?.kind==='retained'?document.retention.reference:null);
  const authorityRejected=documents.some(value=>value.qualification?.progress?.type==='retention_unavailable'||
    value.rejection?.type==='execution-rejected'&&['result-unavailable','result-stale-basis'].includes(value.rejection.reason));
  const window=documents.map(value=>value.evidence_window).filter(Boolean).at(-1);
  const rows=documents.reduce((total,value)=>total+(value.items?.length??0),0);
  const deferred=documents.reduce((total,value)=>total+(value.items??[]).filter(item=>item.type==='DEFERRED_DETAIL').length+(value.deferred_evidence?.length??0),0);
  const evidencePresented=families.reduce((total,field)=>total+entries(field).length,0)+
    documents.reduce((total,value)=>total+(value.relation_graph?.reference_observations?.length??0),0);
  const nextCursor=documents.map(value=>value.next_cursor).filter(value=>value!=null).at(-1)??rows;
  const continuation=documents.map(value=>value.continuation??value.qualification?.progress?.checkpoint?.token).filter(Boolean).at(-1);
  const live=documents.map(value=>value.live).find(Boolean);
  return {
    original_outcome:initial.type,
    completion:{policy:question?.completion?.type??'UNAVAILABLE',model:detail?.model??interpretation?.model??question?.completion?.model??'UNAVAILABLE',
      interpretation:interpretation?.type??null,cause:code(detail?.cause??interpretation?.cause)},
    authority:result?{type:'RETAINED_RESULT',result}:live?{type:'LIVE',root:live.root,host:live.host,epoch:live.epoch,contentView:live.contentView,version:live.version}:{type:'UNAVAILABLE'},
    coverage:{type:coverage?.type??(coverage?.exhaustive===true?'COMPLETE':coverage?.exhaustive===false?'QUALIFIED':'UNKNOWN'),
      exhaustive:coverage?.exhaustive??null,known_minimum:coverage?.knownMinimum??document.qualification?.knownMinimum??null,limitations:codes(coverage?.limitations??document.qualification?.limitations??[]),
      progress:{type:progress?.type??null,reason:progress?.reason??null,next_action:progress?.next_action??null}},
    blockers:{failure:initial.failure??null,rejection:rejection?.type??null,cause:code(detail?.cause??interpretation?.cause),
      stop:code(detail?.stop??document.invocation?.stop),qualification:document.qualification?.progress?.type??null,
      codes:codes([...failures.map(value=>value.reason??value.type),...originalFailureCodes]),
      failure_count:original?.failure_count??null,kinds:codes(original?.failure_kinds??[])},
    omissions:{count:original?.omission_count??null,codes:codes(original?.omission_reasons??omissions.map(value=>value.reason??value.evidence?.reason??value.type))},
    presentation:{rows,accumulated_rows:document.invocation?.accumulated_row_count??document.row_window?.total??null,evidence_presented:evidencePresented,
      evidence_total:original?.evidence_count??window?.total??null,detail:authorityRejected?'UNAVAILABLE':delivery.stop!=='DELIVERED'||deferred||documents.some(value=>value.detail!=null)?'DEFERRED_DETAIL':'INLINE',deferred_count:deferred,
      detail_read:documents.some(value=>value.detail!=null)?(documents.filter(value=>value.detail!=null).at(-1).detail.end===documents.filter(value=>value.detail!=null).at(-1).detail.total?'FINAL_FRAGMENT':'FRAGMENT'):'NONE'},
    recovery:authorityRejected?{type:'UNAVAILABLE',reason:'AUTHORITY_REJECTED'}:result?{type:'READ_RESULT',result,cursor:nextCursor,evidence_cursor:window?.end??0}:
      continuation?{type:'RESUME',continuation}:{type:'UNAVAILABLE',reason:'NO_RETAINED_AUTHORITY'},
  };
}

function encodeQueryDeliveryEnvelope(value) {
  const admitted = admitQueryDeliveryEnvelope(value);
  return admitted.type === 'invalid' ? admitted : { type: 'encoded', json: JSON.stringify(admitted) };
}
// End generated outer delivery contract.
/** Portable output delivery. Canonical replies stay opaque, schema-validated RPC documents. */
const QUERY_DELIVERY_GUIDANCE = " The client awaits bounded query output in one invocation. Default text starts with received row, relation and file counts, original coverage and blockers, then exact rows and compact graph evidence. Signature contracts and detailed source/evidence use the shown authority-bound READ_RESULT request with verbose:true. Counts describe received evidence, not an unread suffix. Distinct occurrences remain distinct; a retained rejection is still a rejection. Handles may expire or become stale; never rerun a query implicitly.";
const QUERY_DELIVERY_LIMITS = Object.freeze({ pages: 64, bytes: 262_144 });
const deliveryObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const deliveryInteger = value => Number.isSafeInteger(value) && value >= 0 && value <= 1_000_000;
const deliveryReference = value => typeof value === 'string' && /^result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const deliveryBlocker = document => document.qualification?.progress?.next_action === 'increase_execution_budget'
  ? 'BUDGET_INCREASE_REQUIRED' : document.qualification?.progress?.type === 'retention_unavailable' ? 'DELIVERY_UNAVAILABLE' : null;
const deliveryContinuation = value => typeof value === 'string' && /^query-output:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);

// Canonical replies are already admitted by invoke. A rejected internal assembly is a producer defect.
const producedQueryDelivery = (initial, pages, delivery, semantic) => {
  const value = createQueryDeliveryEnvelope(initial, pages, delivery, semantic);
  if (value.type === 'invalid') throw new Error(value.reason);
  return value;
};

/**
 * Invoke once. Only READ_RESULT and output-only RESUME may follow the admitted call.
 * No transport retry is safe without a supported reattachment identity, even for a lost initial reply.
 * Limits bound the aggregate model presentation, in addition to each physical transport response.
 */
async function awaitQueryDelivery(params, invoke, policy, signal, now = Date.now) {
  params = JSON.parse(JSON.stringify(params));
  const started = now();
  const runnerControlled = params.request?.type === 'RUN' &&
    (params.request.executionMode ?? 'RUNNER_CONTROLLED') === 'RUNNER_CONTROLLED';
  const expired = () => !runnerControlled && now() - started >= policy.callTimeoutMillis;
  let rpcCount = 0, requestBytes = 0, responseBytes = 0;
  const pages = [], seenContinuations = new Set();
  const call = async arguments_ => {
    const remaining = policy.callTimeoutMillis - (now() - started);
    if (signal?.aborted) throw new Error('CANCELLED');
    if (!runnerControlled && remaining <= 0) throw new Error('TIME_LIMIT');
    rpcCount++;
    requestBytes += Buffer.byteLength(JSON.stringify(arguments_), 'utf8');
    let observedBytes = false;
    const reply = await invoke(arguments_, { ...policy,
      ...(runnerControlled ? { executionMode: 'RUNNER_CONTROLLED' } : { callTimeoutMillis: remaining }),
      onResponseBytes: bytes => { observedBytes = true; responseBytes += bytes; } });
    if (!observedBytes) responseBytes += Buffer.byteLength(JSON.stringify(reply), 'utf8');
    return reply;
  };
  // Failure here is ambiguous submission. Let the adapter's bounded transport failure surface unchanged.
  const initial = await call(params);
  const first = initial.document;
  if (!deliveryObject(first)) return initial;
  const rejectedEvidence = first.rejection?.type === 'COMPLETION_UNPROVEN' && first.rejection.detail?.evidence?.type === 'RETAINED'
    ? first.rejection.detail.evidence : null;
  const retained = rejectedEvidence?.result ?? (first.retention?.kind === 'retained' ? first.retention.reference : null);
  const invocation = first.invocation;
  const count = invocation?.accumulated_row_count;
  let liveIdentity = first.live;
  const initialReadEnd = params.request?.type === 'READ_RESULT' && deliveryInteger(params.request.cursor ?? 0) && Array.isArray(first.items)
    ? (params.request.cursor ?? 0) + first.items.length : undefined;
  let rowCursor = first.next_cursor ?? (invocation?.preview?.type === 'PREFIX' ? invocation.preview.row_count : count ?? initialReadEnd);
  let evidenceCursor = first.evidence_window?.end ?? 0;
  let evidenceTotal = first.evidence_window?.total ?? null;
  let continuation = first.continuation ?? first.qualification?.progress?.checkpoint?.token;
  let next = null;
  const summary = stop => producedQueryDelivery(initial, pages,
    { stop, rpc_count: rpcCount, request_bytes: requestBytes, response_bytes: responseBytes, result: retained });
  const bounded = stop => {
    const result = summary(stop);
    // Reserve the finite semantic facts even when the canonical payload cannot fit.
    if (Buffer.byteLength(encodeQueryDeliveryEnvelope(result).json, 'utf8') > QUERY_DELIVERY_LIMITS.bytes) {
      const semantic=JSON.parse(JSON.stringify(result.semantic));
      semantic.presentation.rows=0;
      semantic.presentation.evidence_presented=0;
      semantic.presentation.detail=semantic.recovery.type==='UNAVAILABLE'?'UNAVAILABLE':'DEFERRED_DETAIL';
      if(semantic.recovery.type==='READ_RESULT') {semantic.recovery.cursor=0;semantic.recovery.evidence_cursor=0;}
      return producedQueryDelivery(null, [], {
        stop: 'BYTE_LIMIT', original_outcome: initial.type, rpc_count: rpcCount, request_bytes: requestBytes, response_bytes: responseBytes, result: retained,
      }, semantic);
    }
    return result;
  };
  const requestedOutput = params.request?.type === 'RUN'
    ? params.request.terminalStep?.output : params.request?.output;
  const read = (cursor, evidence) => ({ request: {
    type: 'READ_RESULT', result: retained, cursor, evidence_cursor: evidence,
    ...(requestedOutput != null ? { output: requestedOutput } : {}),
    ...(rejectedEvidence ? { output: rejectedEvidence.nextQuery?.request?.output } : {}),
    ...(params.request?.executionBudget != null ? { executionBudget: params.request.executionBudget } : {}),
  }, ...(params.verbose != null ? { verbose: params.verbose } : {}) });
  if (Buffer.byteLength(encodeQueryDeliveryEnvelope(summary('BYTE_LIMIT')).json, 'utf8') > QUERY_DELIVERY_LIMITS.bytes)
    return bounded('BYTE_LIMIT');
  if (signal?.aborted) return bounded('CANCELLED');
  if (expired()) return bounded('TIME_LIMIT');
  if (deliveryBlocker(first)) return bounded(deliveryBlocker(first));
  if (rejectedEvidence) {
    if (!deliveryReference(retained) || rejectedEvidence.nextQuery?.request?.type !== 'READ_RESULT' ||
        rejectedEvidence.nextQuery.request.result !== retained) return bounded('MALFORMED_PAGE');
    // Rejection preview is a preview only. Retained proof is consumed once from its start.
    rowCursor = 0;
    next = read(0, 0);
  } else if (invocation?.preview?.type === 'PREFIX' || first.next_cursor != null || first.evidence_window?.type === 'MORE') {
    if (!deliveryReference(retained) || !deliveryInteger(rowCursor) || !deliveryInteger(evidenceCursor))
      return bounded('MALFORMED_PAGE');
    next = read(rowCursor, evidenceCursor);
  } else if (deliveryContinuation(continuation)) {
    seenContinuations.add(continuation);
    next = { request: { type: 'RESUME', continuation,
      ...(params.request?.executionBudget != null ? { executionBudget: params.request.executionBudget } : {}) },
      ...(params.verbose != null ? { verbose: params.verbose } : {}) };
  } else return Buffer.byteLength(JSON.stringify(initial), 'utf8') > QUERY_DELIVERY_LIMITS.bytes ? bounded('BYTE_LIMIT') : initial;
  while (next) {
    if (signal?.aborted) return bounded('CANCELLED');
    if (expired()) return bounded('TIME_LIMIT');
    if (rpcCount >= QUERY_DELIVERY_LIMITS.pages) return bounded('PAGE_LIMIT');
    if (Buffer.byteLength(encodeQueryDeliveryEnvelope(summary('BYTE_LIMIT')).json, 'utf8') >= QUERY_DELIVERY_LIMITS.bytes)
      return bounded('BYTE_LIMIT');
    let reply;
    try { reply = await call(next); }
    catch { return bounded(signal?.aborted ? 'CANCELLED' : expired() ? 'TIME_LIMIT' : 'DELIVERY_UNAVAILABLE'); }
    const page = reply.document;
    if (!deliveryObject(page) || reply.type === 'rejected_document') {
      pages.push(reply);
      return bounded('DELIVERY_UNAVAILABLE');
    }
    const proposed=producedQueryDelivery(initial,[...pages,reply],{
      stop:'BYTE_LIMIT',rpc_count:rpcCount,request_bytes:requestBytes,response_bytes:responseBytes,result:retained});
    if (Buffer.byteLength(encodeQueryDeliveryEnvelope(proposed).json,'utf8') > QUERY_DELIVERY_LIMITS.bytes) return bounded('BYTE_LIMIT');
    if (deliveryBlocker(page)) {
      pages.push(reply);
      return bounded(deliveryBlocker(page));
    }
    if (next.request.type === 'READ_RESULT') {
      const items = page.items;
      const window = page.evidence_window;
      const expectedQuestion = rejectedEvidence?.question ?? first.question;
      if (!Array.isArray(items) || page.retention?.reference !== retained ||
          (page.relation_graph != null && page.relation_graph.basis !== retained) ||
          JSON.stringify(page.question) !== JSON.stringify(expectedQuestion) ||
          (liveIdentity != null && JSON.stringify(page.live) !== JSON.stringify(liveIdentity)) ||
          (count != null && page.invocation != null && page.invocation.accumulated_row_count !== count)) return bounded('IDENTITY_MISMATCH');
      const rowEnd = rowCursor + items.length;
      const rowNext = page.next_cursor;
      if (rowNext != null && (!deliveryInteger(rowNext) || rowNext !== rowEnd || rowNext < rowCursor) ||
          count != null && (rowEnd > count || rowNext == null && rowEnd !== count)) return bounded('MALFORMED_PAGE');
      if (window != null && (!deliveryInteger(window.start) || !deliveryInteger(window.end) || !deliveryInteger(window.total) ||
          window.start !== evidenceCursor || window.end < window.start || window.end > window.total ||
          evidenceTotal != null && window.total !== evidenceTotal ||
          window.type !== (window.end === window.total ? (window.deferred_count > 0 ? 'DEFERRED_DETAIL' : 'FINAL') : 'MORE') ||
          ['failures', 'omissions', 'walk_observations', 'reference_observations', 'discovery_observations', 'relation_observations']
            .reduce((total, field) => total + (Array.isArray(page[field]) ? page[field].length : 0),
              (page.deferred_evidence?.length??0)+(Array.isArray(page.relation_graph?.reference_observations) ? page.relation_graph.reference_observations.length : 0)) !== window.end - window.start)) return bounded('MALFORMED_PAGE');
      if (evidenceTotal != null && window == null) return bounded('MALFORMED_PAGE');
      const evidenceEnd = window?.end ?? evidenceCursor;
      const more = rowNext != null || window?.type === 'MORE';
      if (more && rowEnd === rowCursor && evidenceEnd === evidenceCursor) return bounded('NON_ADVANCING');
      rowCursor = rowEnd;
      evidenceCursor = evidenceEnd;
      evidenceTotal = window?.total ?? evidenceTotal;
      next = more ? read(rowCursor, evidenceCursor) : null;
    } else {
      const token = page.continuation ?? page.qualification?.progress?.checkpoint?.token;
      if (liveIdentity != null && JSON.stringify(page.live) !== JSON.stringify(liveIdentity)) return bounded('IDENTITY_MISMATCH');
      if (token != null && (!deliveryContinuation(token) || seenContinuations.has(token))) return bounded('NON_ADVANCING');
      if (JSON.stringify(page.question) !== JSON.stringify(first.question)) return bounded('IDENTITY_MISMATCH');
      if (token) seenContinuations.add(token);
      next = token ? { ...next, request: { ...next.request, continuation: token } } : null;
    }
    liveIdentity ??= page.live;
    pages.push(reply);
    if (signal?.aborted) return bounded('CANCELLED');
    if (expired()) return bounded('TIME_LIMIT');
  }
  return bounded('DELIVERED');
}

function queryDeliveryFailed(result) {
  return result.type === 'query_delivery'
    ? result.delivery.stop !== 'DELIVERED' || !result.initial || ['rejected', 'rejected_document'].includes(result.initial.type)
    : ['rejected', 'rejected_document'].includes(result.type);
}

const queryEvidenceFields = Object.freeze([
  'failures', 'omissions', 'walk_observations', 'reference_observations', 'discovery_observations', 'relation_observations',
]);
// Human advice for already admitted QueryExactFailureDocument values, using ReadRecoveryAction names.
// A refinement location establishes no issued selector or permission to retry semantic work.
const queryRefinementRecoveryActions = Object.freeze({
  'workspace-not-ready': 'wait_for_workspace',
  'workspace-root-mismatch': 'reacquire_authority',
  'stale-generation': 'reacquire_authority',
  'stale-location': 'reacquire_authority',
  'declaration-moved-or-changed': 'reacquire_authority',
  'scope-rejected': 'correct_request',
  'outside-scope': 'correct_request',
  'ambiguous-declaration': 'correct_request',
  'unsupported-declaration': 'correct_request',
  'workspace-index-unavailable': 'report_failure',
  'compiler-identity-unavailable': 'report_failure',
  'compiler-contract-violation': 'report_failure',
});

/** Human presentation only. The admitted canonical result remains unchanged in adapter details. */
function queryPresentation(result, verbose = false, finalDelivery = false) {
  const canonical = () => JSON.stringify(result);
  if (verbose) return canonical();
  const initial = result.type === 'query_delivery' ? result.initial : result;
  const first = initial?.document;
  const rejectionEvidence = first?.rejection?.type === 'COMPLETION_UNPROVEN' &&
    first.rejection.detail?.evidence?.type === 'RETAINED' ? first.rejection.detail.evidence : null;
  if (!first || !Array.isArray(first.items) && !rejectionEvidence) return canonical();
  if (!first.question && !rejectionEvidence?.question) return canonical();
  const replies = result.type === 'query_delivery' ? [initial, ...result.pages] : [initial];
  const retained = result.delivery?.result ?? rejectionEvidence?.result ??
    (first.retention?.kind === 'retained' ? first.retention.reference : null);
  const stop = result.delivery?.stop ?? 'DELIVERED';
  // Transport/decoder loss does not invalidate authority. Only canonical retention failures do.
  const detailAuthorityRejected = replies.some(reply =>
    reply.type === 'qualified' && reply.document?.qualification?.progress?.type === 'retention_unavailable' ||
    reply.type === 'rejected_document' && reply.document?.rejection?.type === 'execution-rejected' &&
    ['result-unavailable', 'result-stale-basis'].includes(reply.document.rejection.reason));
  const detailAvailable = deliveryReference(retained) && !detailAuthorityRejected;
  const summarizeObservations = !finalDelivery && detailAvailable && stop !== 'DELIVERY_UNAVAILABLE';
  // Exact compiler identity remains inline. Full signature contracts stay under the same retained authority.
  const symbolPresentation = value => {
    if (!summarizeObservations || !deliveryObject(value?.compilerEvidence) ||
        !Object.hasOwn(value.compilerEvidence, 'signature')) return value;
    const { signature, ...compilerEvidence } = value.compilerEvidence;
    return { ...value, compilerEvidence };
  };
  const rows = replies.flatMap(reply => Array.isArray(reply.document?.items) ? reply.document.items : []);
  // A rejection preview is a copy of the retained prefix. Keep any occurrence not actually delivered.
  if (rejectionEvidence) {
    const delivered = new Set(rows.map(row => JSON.stringify(row)));
    rows.unshift(...rejectionEvidence.preview.filter(row => !delivered.has(JSON.stringify(row))));
  }
  const metadata = document => Object.fromEntries(Object.entries(document).filter(([key]) =>
    !['items', 'relation_graph', ...queryEvidenceFields, 'operation', 'page_progress', 'evidence_window', 'next_cursor',
      'continuation', 'invocation', 'retention'].includes(key)));
  const summary = metadata(first);
  if (first.invocation) summary.invocation = { accumulated_row_count: first.invocation.accumulated_row_count,
    stop: first.invocation.stop, preview: { type: first.invocation.preview.type, row_count: first.invocation.preview.row_count } };
  if (rejectionEvidence) {
    // Issued preview/recovery control is rendered once below; rejection and coverage remain authoritative.
    summary.rejection = JSON.parse(JSON.stringify(first.rejection));
    delete summary.rejection.detail.evidence.preview;
    delete summary.rejection.detail.evidence.nextQuery;
  }
  // Admitted JSON declarations are compared exactly, including field order and nested proof.
  // A selector narrows candidates only; it never substitutes for full description equality.
  const sameDescription = (left, right) => {
    if (left === right) return true;
    if (left === null || right === null || typeof left !== 'object' || typeof right !== 'object' ||
        Array.isArray(left) !== Array.isArray(right)) return false;
    const keys = Object.keys(left), otherKeys = Object.keys(right);
    return keys.length === otherKeys.length && keys.every((key, index) =>
      key === otherKeys[index] && sameDescription(left[key], right[key]));
  };
  // Merge only received tables under their admitted retained basis. IDs never become live selectors.
  const receivedGraphs = new Map();
  for (const reply of replies) {
    const graph = reply.document?.relation_graph;
    if (graph == null) continue;
    if (graph.basis !== retained || !Array.isArray(graph.nodes) || !Array.isArray(graph.witnesses) ||
        !Array.isArray(graph.reference_observations)) return canonical();
    let received = receivedGraphs.get(graph.basis);
    if (!received) {
      received = { original: graph, nodes: new Map(), witnesses: new Map(), connections: new Map(), observations: [] };
      receivedGraphs.set(graph.basis, received);
    } else if (!sameDescription(received.original.detail, graph.detail)) return canonical();
    if (graph.connection_witnesses != null && !Array.isArray(graph.connection_witnesses)) return canonical();
    for (const [values, table] of [[graph.nodes, received.nodes], [graph.witnesses, received.witnesses],
      [graph.connection_witnesses ?? [], received.connections]]) {
      for (const value of values) {
        const prior = table.get(value.id);
        if (prior != null && !sameDescription(prior, value)) return canonical();
        table.set(value.id, value);
      }
    }
    received.observations.push(...graph.reference_observations);
  }
  for (const received of receivedGraphs.values()) {
    for (const witness of received.witnesses.values()) {
      if (!received.nodes.has(witness.target) || witness.ownership?.type === 'declaration-owned' &&
          !received.nodes.has(witness.ownership.declaration)) return canonical();
    }
    if (received.observations.some(id => !received.witnesses.has(id))) return canonical();
    for (const connection of received.connections.values()) {
      if (connection.type === 'reference-backed') {
        if (received.witnesses.get(connection.witness)?.ownership?.type !== 'declaration-owned') return canonical();
      } else if (connection.type === 'direct') {
        if (!received.nodes.has(connection.source) || !received.nodes.has(connection.target)) return canonical();
      } else return canonical();
    }
  }
  const graph = receivedGraphs.get(retained);
  if (rows.some(row => row.type === 'reference-edge' && !graph?.witnesses.has(row.witness))) return canonical();
  if (rows.some(row => row.type === 'exact-symbol-node' && (!graph?.nodes.has(row.node) ||
      !Array.isArray(row.connections) || row.connections.some(id => !graph.connections.has(id))))) return canonical();
  const descriptions = [], candidates = new Map(), descriptionEntries = new WeakMap();
  const consider = value => {
    if (!deliveryObject(value)) return;
    let bucket = candidates.get(value.selector);
    if (!bucket) { bucket = []; candidates.set(value.selector, bucket); }
    let entry = bucket.find(candidate => sameDescription(candidate.value, value));
    if (entry) entry.count++;
    else { entry = { count: 1, value }; bucket.push(entry); descriptions.push(entry); }
    descriptionEntries.set(value, entry);
  };
  for (const row of rows) {
    if (row.type === 'reference-occurrence') {
      consider(row.occurrence?.target);
      consider(row.occurrence?.ownership?.declaration);
    }
  }
  const shared = [];
  for (const entry of descriptions.values()) if (entry.count > 1) {
    entry.label = `description${shared.length + 1}`;
    shared.push(entry);
  }
  const describe = value => {
    const entry = descriptionEntries.get(value);
    return entry?.label ? { $description: entry.label } : symbolPresentation(value);
  };
  const factPresentation = fact => ({ ...fact,
    source: symbolPresentation(fact.source), target: symbolPresentation(fact.target) });
  const rowPresentation = row => {
    if (row.type === 'exact-symbol') return Object.fromEntries(Object.entries(row).filter(([key, value]) =>
      !['name', 'location', 'signature', 'source', 'matches', 'connections'].includes(key) ||
      value != null && (!Array.isArray(value) || value.length > 0)).map(([key, value]) =>
        [key, key === 'connections' && summarizeObservations ? value.map(factPresentation) : value]));
    if (row.type === 'occurrence' && summarizeObservations) return { ...row, relation: factPresentation(row.relation) };
    if (row.type !== 'reference-occurrence') return row;
    const occurrence = row.occurrence;
    return { ...row, occurrence: { ...occurrence, target: describe(occurrence.target),
      ownership: occurrence.ownership?.type === 'declaration-owned'
        ? { ...occurrence.ownership, declaration: describe(occurrence.ownership.declaration) } : occurrence.ownership } };
  };
  const evidence = Object.fromEntries(queryEvidenceFields.map(field => [field,
    replies.flatMap(reply => Array.isArray(reply.document?.[field]) ? reply.document[field] : [])]));
  const refinements = evidence.failures.filter(failure => failure.type === 'refinement');
  if (refinements.some(failure => !Object.hasOwn(queryRefinementRecoveryActions, failure.reason))) return canonical();
  const refinementRecovery = refinements.map(failure => ({
    location: failure.location, reason: failure.reason, read_source_eligibility: 'NOT_ISSUED',
    next_action: queryRefinementRecoveryActions[failure.reason],
  }));
  const relations = new Map(), files = new Map();
  let rowsWithoutFile = 0, rowsWithoutRelation = 0;
  for (const row of rows) {
    const witness = row.type === 'reference-edge' ? graph?.witnesses.get(row.witness) : null;
    const file = row.location?.file ?? row.occurrence?.occurrence?.file ?? row.relation?.occurrence?.file ??
      witness?.occurrence?.file ?? (row.type === 'exact-symbol-node' ? graph?.nodes.get(row.node)?.symbol?.file : null);
    if (file) files.set(file, (files.get(file) ?? 0) + 1);
    else rowsWithoutFile++;
    const facts = row.type === 'exact-symbol-node' ? row.connections.map(id => {
      const connection = graph.connections.get(id);
      return connection.type === 'reference-backed' ? graph.witnesses.get(connection.witness) : connection;
    }) : row.type === 'reference-edge' ? [witness] : row.type === 'reference-occurrence' ? [row.occurrence] :
      row.type === 'occurrence' ? [row.relation] : row.type === 'exact-symbol' ? row.connections ?? [] : [];
    let counted = false;
    for (const fact of facts) if (fact?.meaning) {
      relations.set(fact.meaning, (relations.get(fact.meaning) ?? 0) + 1);
      counted = true;
    }
    if (!counted) rowsWithoutRelation++;
  }
  const rejection = first.rejection;
  const coverage = first.coverage ?? rejection?.detail?.originalCoverage;
  const overview = {
    rows: { received: rows.length,
      ...(first.invocation ? { accumulated: first.invocation.accumulated_row_count } : {}) },
    relations: [...relations].map(([relation, count]) => ({ relation, received_connections: count })),
    files: [...files].map(([file, count]) => ({ file, received_rows: count })),
    rows_without_file: rowsWithoutFile, rows_without_relation: rowsWithoutRelation,
    ...(coverage ? { coverage } : {}),
    ...((first.question ?? rejectionEvidence?.question)?.completion
      ? { completion: (first.question ?? rejectionEvidence.question).completion } : {}),
    blockers: {
      ...(stop !== 'DELIVERED' ? { delivery: stop } : {}),
      ...(rejection ? { rejection: { type: rejection.type,
        ...(rejection.detail?.cause ? { cause: rejection.detail.cause } : {}),
        ...(rejection.detail?.stop ? { stop: rejection.detail.stop } : {}) } } : {}),
      ...(first.qualification ? { qualification: first.qualification } : {}),
      failures: evidence.failures.length, omissions: evidence.omissions.length,
      ...(refinementRecovery.length ? { refinement_recovery: refinementRecovery } : {}),
    },
  };
  const lines = [`Kast query: ${initial.type}; delivery: ${stop}`, 'Overview:', JSON.stringify(overview),
    'Counts describe received rows and their connections. Files without supplied locations remain unknown.',
    'Summary:', JSON.stringify(summary)];
  if (refinementRecovery.length) lines.push('Refinement locations do not issue READ_SOURCE selectors. Recovery advice does not issue or retry a query.');
  if (stop !== 'DELIVERED') lines.push('Output is incomplete; retain the semantic outcome and delivery blocker above.');
  lines.push(`Rows (${rows.length}):`, ...rows.map(row => JSON.stringify(rowPresentation(row))));
  if (shared.length) lines.push('Shared descriptions:', ...shared.map(entry => JSON.stringify({ [entry.label]: symbolPresentation(entry.value) })));
  for (const received of receivedGraphs.values()) lines.push('Relation graph:', JSON.stringify({ ...received.original,
    nodes: [...received.nodes.values()], witnesses: [...received.witnesses.values()],
    reference_observations: received.observations,
    ...(received.connections.size ? { connection_witnesses: [...received.connections.values()] } : {}) }));
  const window = replies.map(reply => reply.document?.evidence_window).filter(Boolean).at(-1);
  lines.push('Evidence delivery:', JSON.stringify({ delivered_entries: Object.values(evidence).reduce((sum, entries) => sum + entries.length,
    [...receivedGraphs.values()].reduce((sum, received) => sum + received.observations.length, 0)),
    ...(window ? { window } : {}) }), 'Evidence:');
  for (const [field, entries] of Object.entries(evidence)) if (entries.length) {
    // Finite failures and omissions always stay inline. Detail omission is explicit and recoverable.
    lines.push(JSON.stringify({ [field]: summarizeObservations && !['failures', 'omissions'].includes(field)
      ? { available_entries: entries.length, detail: 'READ_RESULT' } : entries }));
  }
  const observations = new Set([JSON.stringify(metadata(first))]);
  for (const reply of replies.slice(1)) {
    if (!reply.document) { lines.push('Delivery observation:', JSON.stringify(reply)); continue; }
    const detail = metadata(reply.document), encoded = JSON.stringify(detail);
    if (!observations.has(encoded)) {
      observations.add(encoded);
      lines.push('Delivery observation:', encoded);
    }
  }
  const question = first.question ?? rejectionEvidence?.question;
  // Canonical output tags/fields serialize lower-case; public READ_RESULT uses the generated CAPS_CASE enum.
  const output = question?.output ? { ...question.output, type: question.output.type.toUpperCase(),
    ...(question.output.fields ? { fields: question.output.fields.map(field => field.toUpperCase()) } : {}) } : null;
  if (finalDelivery) lines.push('Details: the received canonical archive and SDK details retain the full result.',
    ...(retained ? [`Observed retained handle: ${JSON.stringify(retained)}; ${detailAuthorityRejected
      ? 'canonical detail authority was rejected; unavailable' : 'current usability is unverified'}.`] : []),
    'Observed authority is historical. All received evidence is inline; final delivery does not read a handle or rerun semantics.');
  else if (detailAvailable && stop === 'DELIVERY_UNAVAILABLE')
    lines.push('Detail delivery unavailable; authority was not rejected. Received evidence is inline; READ_RESULT remains available without retrying RUN.');
  if (!finalDelivery && detailAvailable) lines.push('Details:', JSON.stringify(graph?.original.detail ??
    { request: { type: 'READ_RESULT', result: retained,
      cursor: 0, evidence_cursor: 0, ...(output ? { output } : {}) }, verbose: true }),
  'Details use the original live authority and may expire or become stale. An unavailable handle is not rerun.');
  else if (!finalDelivery && retained) {
    lines.push(`Details: canonical retained detail authority unavailable for ${JSON.stringify(retained)}; all received evidence is inline.`,
      'Delivery does not retry RUN.');
    if (replies.some(reply => reply.document?.next_action != null))
      lines.push('Follow the received canonical recovery action above.');
  }
  else if (!finalDelivery) lines.push('Details: no retained handle; all available evidence is inline.');
  const text = lines.join('\n');
  // Preserve existing presentation bounds. A projection never buys a larger drain or erases a large failure.
  return Buffer.byteLength(text, 'utf8') <= QUERY_DELIVERY_LIMITS.bytes
    ? text : canonical();
}
// End generated query delivery.

// Generated query session; owned by cli/src/main/js/query-session.mjs.
/** Invocation-owned sequential query transport. No child survives a logical query. */
function createQuerySession(command, cwd, policy, signal, spawnProcess, environment) {
  let child, pending, timer, closed = false, stopped = null, closing = false;
  let record = Buffer.alloc(0), responseBytes = 0;
  const childEnvironment = { ...environment };
  delete childEnvironment.KAST_TOOL_RPC_COMMAND;
  delete childEnvironment.KAST_EVALUATION_DEADLINE_MS;
  let finishExit;
  const exit = new Promise(resolve => { finishExit = resolve; });
  const failure = reason => new Error(`Kast query session outcome=${reason}; submission may be ambiguous, do not resubmit RUN`);
  const stop = reason => {
    stopped ??= reason;
    if (pending) { const current = pending; pending = null;
      current.policy.onResponseBytes?.(responseBytes); responseBytes = 0; current.reject(failure(stopped)); }
    child?.kill('SIGKILL');
    // Release owned pipes even if another process inherited a descriptor; close must not await that peer.
    child?.stdin.destroy?.(); child?.stdout.destroy?.(); child?.stderr.destroy?.();
  };
  const cleanup = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
  const abort = () => stop('CANCELLED');
  const launch = deliveryPolicy => {
    if (signal?.aborted) throw failure('CANCELLED');
    child = spawnProcess(command, ['query-session'], { cwd, env: childEnvironment, stdio: ['pipe', 'pipe', 'pipe'] });
    if (deliveryPolicy.executionMode !== 'RUNNER_CONTROLLED')
      timer = setTimeout(() => stop('TIMEOUT'), policy.callTimeoutMillis);
    signal?.addEventListener('abort', abort, { once: true });
    child.stderr.on('data', () => {}); // No arbitrary process diagnostics or source payload retained.
    child.stdin.on('error', () => stop('INPUT_FAILED'));
    child.on('error', () => { stop('LAUNCH_FAILED'); cleanup(); finishExit(); });
    child.on('close', code => {
      closed = true;
      if (pending) stop(stopped ?? (code === 0 ? 'MISSING_REPLY' : 'NONZERO_EXIT'));
      cleanup(); finishExit();
    });
    child.stdout.on('data', chunk => {
      if (stopped || closing) return;
      if (!pending) return stop('UNEXPECTED_REPLY');
      responseBytes += chunk.length;
      if (responseBytes > pending.policy.maxResponseBytes) return stop('OUTPUT_OVERFLOW');
      record = Buffer.concat([record, chunk]);
      const boundary = record.indexOf(10);
      if (boundary === -1) return;
      // Exactly one reply per admitted request; never buffer unsolicited records.
      if (boundary !== record.length - 1) return stop('UNEXPECTED_REPLY');
      let reply;
      try { reply = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(record.subarray(0, boundary))); }
      catch { return stop('INVALID_REPLY'); }
      if (!isToolResult(reply)) return stop('INVALID_RESULT');
      const current = pending;
      pending = null; record = Buffer.alloc(0);
      current.policy.onResponseBytes?.(responseBytes); responseBytes = 0;
      current.resolve(reply);
    });
  };
  return {
    invoke(arguments_, deliveryPolicy) {
      if (closed || stopped || closing) return Promise.reject(failure(stopped ?? 'CLOSED'));
      if (pending) return Promise.reject(failure('CONCURRENT_REQUEST'));
      try { if (!child) launch(deliveryPolicy); } catch (error) { return Promise.reject(error); }
      return new Promise((resolve, reject) => {
        pending = { resolve, reject, policy: deliveryPolicy };
        child.stdin.write(JSON.stringify(arguments_) + '\n');
      });
    },
    async close() {
      closing = true;
      if (!child) return;
      if (!closed) {
        if (pending || stopped || signal?.aborted) stop(stopped ?? 'CANCELLED');
        else child.stdin.end();
        // Child cleanup has a finite ownership bound inside the original session deadline.
        const reap = setTimeout(() => stop('CLOSE_TIMEOUT'), Math.min(1000, policy.callTimeoutMillis));
        try { await exit; } finally { clearTimeout(reap); cleanup(); }
      }
    },
  };
}
// End generated query session.

const installRoot = join(homedir(), ".local", "share", "kast");
const command = process.env.KAST_TOOL_RPC_COMMAND ||
  join(installRoot, "installation", "bin", "kast-tool-rpc-complete");

function run(args: string[], input: string, cwd: string, policy?: {callTimeoutMillis: number; executionMode?: "RUNNER_CONTROLLED"; maxResponseBytes: number; onResponseBytes?: (bytes: number) => void}, signal?: AbortSignal): Promise<unknown> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error("Kast invocation CANCELLED before launch"));
    const childEnvironment = { ...process.env };
    delete childEnvironment.KAST_TOOL_RPC_COMMAND;
    delete childEnvironment.KAST_EVALUATION_DEADLINE_MS;
    const child = spawn(command, args, { cwd: cwd, env: childEnvironment, stdio: ["pipe", "pipe", "pipe"] });
    const chunks : Buffer[] = [];
    let size = 0;
    let stderrBytes = 0;
    let phaseEvidence = "";
    let stderrLine = "";
    let termination : "TIMEOUT" | "CANCELLED" | "OUTPUT_OVERFLOW" | "INPUT_FAILED" | null = null;
    const started = Date.now();
    const maxResponseBytes = policy?.maxResponseBytes ?? 4_194_304;
    const deadline = policy?.callTimeoutMillis ?? 180_000;
    const stop = (reason: NonNullable<typeof termination>) => { termination ??= reason; child.kill("SIGKILL"); };
    const timer = policy?.executionMode === "RUNNER_CONTROLLED" ? undefined
      : setTimeout(() => stop("TIMEOUT"), deadline);
    const abort = () => stop("CANCELLED");
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
    };
    const failure = (reason: string, exitCode: number | null = null) => new Error(
      `Kast subprocess stage=${args[0]} outcome=${reason} exitCode=${exitCode ?? "unknown"} elapsedMs=${Date.now() - started} deadlineMs=${deadline} ` +
      `executable=${command} stderrBytes=${stderrBytes} ${phaseEvidence}; mutation state is unknown; do not replay a write; query submission may be ambiguous, do not resubmit it`,
    );
    signal?.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > maxResponseBytes) stop("OUTPUT_OVERFLOW");
      else chunks.push(chunk);
    });
    child.stderr.on("data", (chunk: Buffer) => {
      stderrBytes += chunk.length;
      // Keep only closed diagnostic fields, never source text, arbitrary stderr or reference tokens.
      stderrLine = (stderrLine + chunk.toString("utf8")).slice(-4096);
      const lines = stderrLine.split("\n");
      stderrLine = lines.pop() ?? "";
      for (const line of lines) {
        const phase = line.match(/kast_change stage=([A-Z_]{1,48}) outcome=([A-Z_]{1,32})(?: plan=(plan:[0-9a-f]{64}))?/);
        if (phase) phaseEvidence = `phase=${phase[1]} phaseOutcome=${phase[2]}${phase[3] ? ` plan=${phase[3]}` : ""}`;
      }
    });
    child.on("error", () => { cleanup(); reject(failure("LAUNCH_FAILED")); });
    child.stdin.on("error", () => stop("INPUT_FAILED"));
    child.on("close", (code: number | null, exitSignal: string | null) => {
      cleanup();
      policy?.onResponseBytes?.(size);
      if (termination) return reject(failure(termination));
      if (code !== 0) return reject(failure(exitSignal ? "SIGNALLED" : "NONZERO_EXIT", code));
      let decoded: string;
      try { decoded = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)); }
      catch { return reject(failure("INVALID_UTF8")); }
      let result: unknown;
      try { result = JSON.parse(decoded); }
      catch { return reject(failure("INVALID_JSON")); }
      if (args[0] === "call" && !isToolResult(result)) return reject(failure("INVALID_RESULT"));
      resolve(result);
    });
    child.stdin.end(input);
  });
}

type ToolRpcResult =
  | { type: "complete" | "qualified" | "rejected_document"; document: Record<string, unknown> }
  | { type: "rejected"; failure: (typeof TOOL_RPC_FAILURES)[number]; detail?: never }
  | { type: "rejected"; failure: "INVALID_ARGUMENTS"; detail: { type: "OUTPUT_ALLOWANCE_REJECTED"; minimumBytes: number } }
  | { type: "rejected"; failure: "INVALID_ARGUMENTS"; detail: { type: "DETAIL_SELECTOR_REJECTED"; rule: "SAME_RETAINED_RESULT" | "DETAIL_TARGET_KIND" | "DETAIL_CURSOR_CONTROL" } }
  | { type: "rejected"; failure: "INVALID_ARGUMENTS"; detail: {
      type: "REQUEST_CONSTRAINT_REJECTED";
      parameters: ["executionMode", "executionBudget.maxWorkUnits"] | ["executionBudget.maxWorkUnits", "executionMode"];
      rule: "RUNNER_CONTROLLED_WORK_LIMIT";
      remedies: ["REMOVE_MAX_WORK_UNITS", "SELECT_BOUNDED"] | ["SELECT_BOUNDED", "REMOVE_MAX_WORK_UNITS"] } }
  | { type: "rejected"; failure: "INVALID_ARGUMENTS"; detail: {
      type: "SOURCE_ANCHOR_REJECTED";
      cause: "UNKNOWN_TOKEN_FAMILY" | "INVALID_TOKEN_STRUCTURE" | "INVALID_PAYLOAD_ENCODING" | "PAYLOAD_DIGEST_MISMATCH";
      next_action: "USE_ISSUED_CANDIDATE_SELECTOR" } }
  | { type: "rejected"; failure: "QUERY_SESSION_TRANSITION_REJECTED"; detail:
      | { type: "NEW_RUN_REQUIRES_NEW_SESSION" | "SOURCE_READ_REQUIRES_NEW_SESSION"; next_action: "START_NEW_QUERY_SESSION" }
      | { type: "RESULT_NOT_ISSUED_BY_PRECEDING_REPLY"; expected_result: string | null; requested_result: string; next_action: "START_NEW_QUERY_SESSION" }
      | { type: "CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY"; expected_continuation: string | null; requested_continuation: string; next_action: "START_NEW_QUERY_SESSION" } };

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isToolResult(value: unknown): value is ToolRpcResult {
  const object = (item: unknown): item is Record<string, unknown> => item !== null && typeof item === "object" && !Array.isArray(item);
  const keys = (item: unknown, expected: readonly string[]) => object(item) && Object.keys(item).length === expected.length &&
    expected.every(key => Object.hasOwn(item, key));
  const uuid = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
  const token = (item: unknown, family: string) => typeof item === "string" && new RegExp("^" + family + ":v1:" + uuid + "$").test(item);
  if (!object(value) || typeof value.type !== "string") return false;
  if (value.type === "rejected") {
    if (typeof value.failure !== "string" || !(TOOL_RPC_FAILURES as readonly string[]).includes(value.failure)) return false;
    if (keys(value, ["type", "failure"])) return true;
    if (!keys(value, ["type", "failure", "detail"]) || !object(value.detail) || typeof value.detail.type !== "string") return false;
    const detail = value.detail;
    if (detail.type === "OUTPUT_ALLOWANCE_REJECTED") return value.failure === "INVALID_ARGUMENTS" &&
      keys(detail, ["type", "minimumBytes"]) && detail.minimumBytes === 4096;
    if (detail.type === "DETAIL_SELECTOR_REJECTED") return value.failure === "INVALID_ARGUMENTS" &&
      keys(detail, ["type", "rule"]) && ["SAME_RETAINED_RESULT", "DETAIL_TARGET_KIND", "DETAIL_CURSOR_CONTROL"].includes(detail.rule);
    if (detail.type === "REQUEST_CONSTRAINT_REJECTED") return value.failure === "INVALID_ARGUMENTS" &&
      keys(detail, ["type", "parameters", "rule", "remedies"]) && detail.rule === "RUNNER_CONTROLLED_WORK_LIMIT" &&
      Array.isArray(detail.parameters) && detail.parameters.length === 2 &&
      detail.parameters.includes("executionMode") && detail.parameters.includes("executionBudget.maxWorkUnits") &&
      Array.isArray(detail.remedies) && detail.remedies.length === 2 &&
      detail.remedies.includes("REMOVE_MAX_WORK_UNITS") && detail.remedies.includes("SELECT_BOUNDED");
    if (detail.type === "SOURCE_ANCHOR_REJECTED") return value.failure === "INVALID_ARGUMENTS" &&
      keys(detail, ["type", "cause", "next_action"]) && detail.next_action === "USE_ISSUED_CANDIDATE_SELECTOR" && typeof detail.cause === "string" &&
      ["UNKNOWN_TOKEN_FAMILY", "INVALID_TOKEN_STRUCTURE", "INVALID_PAYLOAD_ENCODING", "PAYLOAD_DIGEST_MISMATCH"].includes(detail.cause);
    if (value.failure !== "QUERY_SESSION_TRANSITION_REJECTED" || detail.next_action !== "START_NEW_QUERY_SESSION") return false;
    if (["NEW_RUN_REQUIRES_NEW_SESSION", "SOURCE_READ_REQUIRES_NEW_SESSION"].includes(detail.type))
      return keys(detail, ["type", "next_action"]);
    if (detail.type === "RESULT_NOT_ISSUED_BY_PRECEDING_REPLY") return keys(detail, ["type", "expected_result", "requested_result", "next_action"]) &&
      (detail.expected_result === null || token(detail.expected_result, "result")) && token(detail.requested_result, "result");
    if (detail.type === "CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY") return keys(detail, ["type", "expected_continuation", "requested_continuation", "next_action"]) &&
      (detail.expected_continuation === null || token(detail.expected_continuation, "query-output")) &&
      token(detail.requested_continuation, "(?:query|query-output)");
    return false;
  }
  // The canonical document stays opaque here. The Kotlin RPC admits its owning schema.
  return keys(value, ["type", "document"]) && ["complete", "qualified", "rejected_document"].includes(value.type) && object(value.document);
}

function approvalSummary(tool: Tool, params: Record<string, unknown>, cwd: string): string {
  const bounded = (value: string, limit: number) => value.length <= limit ? value :
    value.slice(0, limit) + " [truncated; review full tool arguments]";
  const target = typeof params.exactTarget === "string" ? params.exactTarget : "[missing exact target]";
  return `Tool: ${tool.name}\nWorkspace: ${bounded(JSON.stringify(cwd), 384)}\n` +
    `Target: ${bounded(JSON.stringify(target), 256)}\nIntent: ${bounded(JSON.stringify(params), 1200)}`;
}

export default async function (pi: ExtensionAPI) {
  pi.on("session_start", () => {
    if (!pi.getAllTools().some(tool => tool.name === "tool_search")) {
      throw new Error("Kast deferred tools need Pi's native tool_search");
    }
    const active = pi.getActiveTools();
    if (!active.includes("tool_search")) pi.setActiveTools([...active, "tool_search"]);
  });
  if (!existsSync(command)) throw new Error("Kast tool RPC command is not installed");
  const reply = await run(["catalog"], "", process.cwd()) as {
    type?: string;
    catalog?: { schemaVersion?: number; callTimeoutMillis?: number; maxResponseBytes?: number; querySession?: boolean; tools?: Tool[] };
  };
  if (reply?.type !== "catalog" || reply.catalog?.schemaVersion !== PUBLIC_TOOL_CONTRACT_VERSION ||
      !Number.isSafeInteger(reply.catalog.callTimeoutMillis) || !Number.isSafeInteger(reply.catalog.maxResponseBytes) ||
      (reply.catalog.callTimeoutMillis ?? 0) < 1 || (reply.catalog.maxResponseBytes ?? 0) < 1 ||
      (reply.catalog.querySession !== undefined && typeof reply.catalog.querySession !== "boolean") ||
      !Array.isArray(reply.catalog.tools)) throw new Error(`Kast catalog admission failed: expected=${PUBLIC_TOOL_CONTRACT_VERSION} observed=${Number.isSafeInteger(reply?.catalog?.schemaVersion) ? reply.catalog.schemaVersion : "invalid"} executable=${command}`);
  const policy = reply.catalog as {callTimeoutMillis: number; maxResponseBytes: number; querySession?: boolean; tools: Tool[]};
  const names = new Set<string>();
  for (const tool of reply.catalog.tools) {
    if (tool == null || typeof tool.name !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(tool.name) || names.has(tool.name) ||
      typeof tool.description !== "string" || !tool.description.trim() ||
      tool.inputSchema == null || typeof tool.inputSchema !== "object" || Array.isArray(tool.inputSchema) ||
      tool.inputSchema.type !== "object" ||
        !["READ", "WRITE"].includes(tool.effect)) throw new Error(`Kast catalog entry admission failed: executable=${command}`);
    names.add(tool.name);
  }
  for (const tool of policy.tools) {
    pi.registerTool({
      name: tool.name,
      label: `Kast ${tool.name}`,
      exposure: DEFERRED_TOOL_NAMES.includes(tool.name) ? "deferred" : "direct",
      description: tool.description + (tool.name === "query_symbols" ? QUERY_DELIVERY_GUIDANCE : ""),
      parameters: Type.Unsafe<Record<string, unknown>>(tool.inputSchema),
      async execute(_id, params, signal, _onUpdate, ctx) {
        if (tool.effect === "WRITE" &&
            (!ctx.hasUI || !await ctx.ui.confirm("Apply Kast change?", approvalSummary(tool, params, ctx.cwd), { signal }))) {
          const denied = { type: "APPROVAL_DENIED" } as const;
          return { content: [{ type: "text", text: JSON.stringify(denied) }], details: denied, isError: true };
        }
        const workspace = ctx.cwd;
        const invoke = (arguments_: Record<string, unknown>, deliveryPolicy = policy) =>
          run(["call", tool.name], JSON.stringify(arguments_), workspace, deliveryPolicy, signal);
        const session = tool.name === "query_symbols" && policy.querySession === true
          ? createQuerySession(command, workspace, policy, signal, spawn, process.env) : null;
        let result;
        try {
          result = tool.name === "query_symbols"
            ? await awaitQueryDelivery(params, session?.invoke ?? invoke, policy, signal)
            : await invoke(params);
        } finally { await session?.close(); }
        return {
          content: [{ type: "text", text: tool.name === "query_symbols" ? queryPresentation(result, params.verbose === true) : JSON.stringify(result) }],
          details: result,
          isError: queryDeliveryFailed(result),
        };
      },
    });
  }
}
