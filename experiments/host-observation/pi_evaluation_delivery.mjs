// Observation of #995's envelope only. The production client owns pagination,
// identity/cursor validation, transport effects, cancellation and its limits.
import { admitQueryDeliveryEnvelope } from '../../cli/src/main/js/query-delivery-contract.mjs';
import { isDeepStrictEqual } from 'node:util';
import { admittedQueryDocument,canonicalQueryLimitations } from './pi_evaluation_question.mjs';
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
// authority. Admit the full canonical DTO boundary before its narrower consistency
// checks; transport admission alone does not validate SDK details or saved data.
export function partialEvidenceSummary(envelope) {
  if(envelope.delivery.stop!=='BYTE_LIMIT'||envelope.initial?.type!=='rejected_document'||!envelope.pages.length)return null;
  if(envelope.initial.document?.status!=='rejected'||!admittedQueryDocument(envelope.initial.document)||
    envelope.pages.some(reply=>reply.type!=='qualified'||reply.document?.status!=='qualified'||!admittedQueryDocument(reply.document)))return null;
  const rejection=envelope.initial.document.rejection,detail=rejection.detail,proof=detail?.evidence,coverage=detail?.originalCoverage;
  const integer=n=>Number.isSafeInteger(n)&&n>=0;
  if(rejection.type!=='COMPLETION_UNPROVEN'||detail?.policyProgress?.type!=='EVIDENCE_ONLY'||proof?.type!=='RETAINED'||
    proof.result!==envelope.delivery.result||proof.nextQuery.request.result!==proof.result||(proof.nextQuery.request.cursor??0)!==0||
    coverage?.type!=='QUALIFIED'||coverage.knownMinimum===0||coverage.progress.type!=='terminal_incomplete')return null;
  const question=proof.question,live=envelope.pages[0].document?.live;
  if(typeof live?.host!=='string'||!integer(live.epoch)||live.epoch===0||live.version!==1)return null;
  // QueryOutputDocument.toCompletionPublicOutput owns the public recovery
  // spelling; this exception supports only the two existing actionable families.
  const output=question.output;
  const readOutput=output.type==='symbols'?{type:'SYMBOLS',fields:output.fields.map(field=>field.toUpperCase())}:
    output.type==='occurrences'?{type:'OCCURRENCES'}:null;
  if(!readOutput||!isDeepStrictEqual(proof.nextQuery.request.output,readOutput))return null;
  const evidenceFields=['failures','omissions','walk_observations','reference_observations','discovery_observations','relation_observations'];
  // completionEvidenceRead preserves source limitations, adds STATIC_MODEL_UNPROVEN,
  // and translates evidence-read progress to upstream-incomplete. Retained page
  // selection can additionally contribute RESULT_LIMIT_REACHED (pageCoverage).
  const limitations=canonicalQueryLimitations;
  if(!isDeepStrictEqual(coverage.limitations,limitations.filter(value=>coverage.limitations.includes(value))))return null;
  const expectedQualification=extraResultLimit=>({knownMinimum:coverage.knownMinimum,
    limitations:limitations.filter(value=>coverage.limitations.includes(value)||value==='STATIC_MODEL_UNPROVEN'||extraResultLimit&&value==='RESULT_LIMIT_REACHED').map(value=>value.toLowerCase().replaceAll('_','-')),
    progress:{type:'terminal_incomplete',reason:'upstream-incomplete'}});
  const rows=new Set(),receivedPrefix=[];let rowCursor=0,evidenceCursor=0,evidenceTotal,rowsEnded=false;
  const rowIdentity=row=>{
    if(row.row_id!=null)return JSON.stringify(['issued-row',row.row_id]);
    if(row.type==='exact-symbol')return JSON.stringify(['exact-symbol',row.ref]);
    const reference=row.occurrence,site=reference.occurrence,owner=reference.ownership;
    // RelationReferenceOccurrence.canonicalProjection identifies the target,
    // meaning, source site, context, ownership and provenance within one authority.
    return JSON.stringify(['reference-occurrence',row.ref,reference.meaning,site.file,site.range.startInclusive,site.range.endExclusive,
      reference.context,owner.type,owner.type==='declaration-owned'?owner.declaration.selector:owner.type==='file-scoped'?owner.context:owner.cause,reference.provenance]);
  };
  const addRow=row=>{
    if(row.type!==(output.type==='symbols'?'exact-symbol':'reference-occurrence'))return false;
    if(row.type==='reference-occurrence') {
      const reference=row.occurrence,range=reference.occurrence.range;
      if(reference.target.selector!==row.ref||range.endExclusive<range.startInclusive)return false;
    }
    const key=rowIdentity(row);
    // A retained row position has one issued identity. The canonical optional
    // nullable-ID legacy shape uses semantic identity with the same uniqueness.
    if(rows.has(key))return false;
    rows.add(key);receivedPrefix.push(row);return true;
  };
  for(const reply of envelope.pages) {
    const d=reply.document,w=d?.evidence_window;
    if(!evidenceFields.every(field=>Array.isArray(d[field])))return null;
    // QueryRunResult.pageProgress derives these page counts from the received
    // groups. Schema admission proves their types; equality proves their facts.
    const walk=d.walk_observations,relations=d.relation_observations;
    const sum=(groups,field)=>groups.reduce((n,group)=>n+group[field].length,0);
    const callbacks=[...relations.flatMap(group=>group.callback_observations),...walk.flatMap(group=>group.callback_observations.map(item=>item.observation))];
    const progress={rows:d.items.length,walk_evidence:walk.length,reference_evidence:d.reference_observations.length+sum(walk,'reference_occurrences'),
      relation_evidence:relations.length,scope_exclusions:sum(walk,'scope_exclusions')+sum(relations,'scope_exclusions'),
      callback_observations:callbacks.length,callable_observations:sum(walk,'callable_observations')+sum(relations,'callable_observations'),
      excluded_callbacks:callbacks.filter(item=>item.named_policy.type==='EXCLUDED').length,omissions:d.omissions.length,failures:d.failures.length};
    if(d.coverage.exhaustive!==false||
      !isDeepStrictEqual(d.page_progress,progress)||d.terminal_reason!==d.qualification.progress.reason||d.continuation!=null||
      rowsEnded&&d.items.length>0||
      ![expectedQualification(false),expectedQualification(true)].some(expected=>isDeepStrictEqual(d.qualification,expected))||
      d.interpretation?.type!=='POLICY_REJECTED_EVIDENCE'||d.interpretation.model!==detail.model||
      !isDeepStrictEqual(d.interpretation.cause,detail.cause)||!isDeepStrictEqual(d.interpretation.originalCoverage,coverage)||
      !isDeepStrictEqual(d.question,question)||!isDeepStrictEqual(d.live,live)||
      d.retention?.kind!=='retained'||d.retention.reference!==proof.result||
      !d.items.every(addRow)||
      !w||!integer(w.start)||!integer(w.end)||!integer(w.total)||w.start!==evidenceCursor||w.end<w.start||w.end>w.total||
      (evidenceTotal!==undefined&&w.total!==evidenceTotal)||w.type!==(w.end<w.total?'MORE':'FINAL')||
      evidenceFields.reduce((sum,field)=>sum+d[field].length,0)!==w.end-w.start)return null;
    rowCursor+=d.items.length;
    const next=d.next_cursor??null;
    if(next!==null&&next!==rowCursor)return null;
    if(next===null)rowsEnded=true;
    evidenceCursor=w.end;evidenceTotal=w.total;
  }
  // awaitQueryDelivery finishes DELIVERED when a received page has no output
  // successor. BYTE_LIMIT can retain this prefix only while a successor remains.
  const last=envelope.pages.at(-1).document;
  if(last.next_cursor==null&&last.evidence_window.type==='FINAL')return null;
  // The producer preview is the ordered prefix at cursor zero, with the same
  // issued identities. A conflicting copy must not be unioned into another row.
  if(proof.preview.length>receivedPrefix.length||!proof.preview.every((row,index)=>isDeepStrictEqual(row,receivedPrefix[index])))return null;
  if(!rows.size||rows.size>coverage.knownMinimum)return null;
  return {stop:'BYTE_LIMIT',receivedRows:rows.size,duplicatePreviewRows:proof.preview.length,evidence:{received:evidenceCursor,total:evidenceTotal},
    knownMinimum:coverage.knownMinimum,question:structuredClone(question),live:structuredClone(live),result:proof.result,maximumAnswerRequests:1};
}
