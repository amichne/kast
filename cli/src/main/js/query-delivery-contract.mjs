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
export { admitQueryDeliveryEnvelope, createQueryDeliveryEnvelope, encodeQueryDeliveryEnvelope };
