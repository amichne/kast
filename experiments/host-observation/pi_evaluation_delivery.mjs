// Observation of #995's envelope only. The production client owns pagination,
// identity/cursor validation, transport effects, cancellation and its limits.
import { admitQueryDeliveryEnvelope } from '../../cli/src/main/js/query-delivery-contract.mjs';
import { isDeepStrictEqual } from 'node:util';
export function decodeQueryDelivery(value,decodeCanonical) {
  const admitted=admitQueryDeliveryEnvelope(value);
  if(admitted.type==='invalid')return admitted;
  if(value.initial!==null&&decodeCanonical(value.initial).type==='invalid')return {type:'invalid'};
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

// This admits an interpretation of a received prefix, never completion or fresh
// authority. The RPC/client still owns canonical schema and transport admission.
export function partialEvidenceSummary(envelope) {
  if(envelope.delivery.stop!=='BYTE_LIMIT'||envelope.initial?.type!=='rejected_document'||!envelope.pages.length)return null;
  const rejection=envelope.initial.document.rejection,detail=rejection.detail,proof=detail?.evidence,coverage=detail?.originalCoverage;
  const integer=n=>Number.isSafeInteger(n)&&n>=0;
  const reference=value=>typeof value==='string'&&/^result:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
  if(rejection.type!=='COMPLETION_UNPROVEN'||detail?.policyProgress?.type!=='EVIDENCE_ONLY'||proof?.type!=='RETAINED'||
    !reference(proof.result)||proof.result!==envelope.delivery.result||proof.nextQuery?.request?.type!=='READ_RESULT'||
    proof.nextQuery.request.result!==proof.result||(proof.nextQuery.request.cursor??0)!==0||(proof.nextQuery.request.evidence_cursor??0)!==0||
    !Array.isArray(proof.preview)||coverage?.type!=='QUALIFIED'||!integer(coverage.knownMinimum)||coverage.knownMinimum===0||
    !Array.isArray(coverage.limitations)||!coverage.limitations.length||coverage.limitations.some(value=>typeof value!=='string')||
    coverage.progress?.type!=='terminal_incomplete'||typeof coverage.progress.reason!=='string'||!coverage.progress.reason.length)return null;
  const question=proof.question,live=envelope.pages[0].document?.live;
  if(!question?.from||!Array.isArray(question.steps)||!question.output||question.completion?.type!=='COMPLETE_ONLY'||
    typeof live?.root!=='string'||!live.root.startsWith('/')||typeof live.host!=='string'||
    !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(live.host)||!integer(live.epoch)||live.epoch===0||
    live.contentView!=='SAVED_PSI_COMMITTED'||live.version!==1)return null;
  const evidenceFields=['failures','omissions','walk_observations','reference_observations','discovery_observations','relation_observations'];
  const rows=new Map();let rowCursor=0,evidenceCursor=0,evidenceTotal,duplicatePreviewRows=0;
  const addRow=row=>{
    if(!row||typeof row!=='object'||Array.isArray(row)||!['exact-symbol','reference-occurrence'].includes(row.type)||
      typeof row.ref!=='string'||!/^exact:v[2345]:/.test(row.ref))return false;
    if(row.type==='exact-symbol'&&(!Object.hasOwn(row,'name')||!Object.hasOwn(row,'location')||
      !Object.hasOwn(row,'signature')||typeof row.kind!=='string'||!Array.isArray(row.connections)))return false;
    if(row.type==='reference-occurrence') {
      const occurrence=row.occurrence?.occurrence,range=occurrence?.range,target=row.occurrence?.target;
      if(target?.selector!==row.ref||typeof target.compilerEvidence?.identity!=='string'||!target.compilerEvidence.signature||
        typeof occurrence?.file!=='string'||!integer(range?.startInclusive)||!integer(range?.endExclusive)||range.endExclusive<range.startInclusive)return false;
    }
    if(row.row_id!==undefined&&(typeof row.row_id!=='string'||!/^result-row:v1:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(row.row_id)))return false;
    const encoded=JSON.stringify(row),key=row.row_id??encoded;
    if(typeof key!=='string'||rows.has(key)&&rows.get(key)!==encoded)return false;
    rows.set(key,encoded);return true;
  };
  for(const reply of envelope.pages) {
    const d=reply.document,w=d?.evidence_window;
    if(reply.type!=='qualified'||d?.coverage?.exhaustive!==false||!Array.isArray(d.items)||d.page_progress?.rows!==d.items.length||
      d.qualification?.knownMinimum!==coverage.knownMinimum||d.qualification?.progress?.type!=='terminal_incomplete'||
      d.interpretation?.type!=='POLICY_REJECTED_EVIDENCE'||d.interpretation.model!==detail.model||
      !isDeepStrictEqual(d.interpretation.cause,detail.cause)||!isDeepStrictEqual(d.interpretation.originalCoverage,coverage)||
      !isDeepStrictEqual(d.question,question)||!isDeepStrictEqual(d.live,live)||
      d.retention?.kind!=='retained'||d.retention.reference!==proof.result||
      !evidenceFields.every(field=>Array.isArray(d[field]))||!d.items.every(addRow)||
      !w||!integer(w.start)||!integer(w.end)||!integer(w.total)||w.start!==evidenceCursor||w.end<w.start||w.end>w.total||
      (evidenceTotal!==undefined&&w.total!==evidenceTotal)||w.type!==(w.end<w.total?'MORE':'FINAL')||
      evidenceFields.reduce((sum,field)=>sum+d[field].length,0)!==w.end-w.start)return null;
    rowCursor+=d.items.length;
    if(d.next_cursor!==null&&d.next_cursor!==rowCursor)return null;
    evidenceCursor=w.end;evidenceTotal=w.total;
  }
  for(const row of proof.preview) {
    if(rows.has(row?.row_id??JSON.stringify(row)))duplicatePreviewRows++;
    if(!addRow(row))return null;
  }
  if(!rows.size||rows.size>coverage.knownMinimum)return null;
  return {stop:'BYTE_LIMIT',receivedRows:rows.size,duplicatePreviewRows,evidence:{received:evidenceCursor,total:evidenceTotal},
    knownMinimum:coverage.knownMinimum,question:structuredClone(question),live:structuredClone(live),result:proof.result,maximumAnswerRequests:1};
}
