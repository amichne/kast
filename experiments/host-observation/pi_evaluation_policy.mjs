// Pure rules; no process, filesystem, provider, Kast, clock or credential access.
import { isDeepStrictEqual } from 'node:util';
import { isAdmittedPayload, isVerifiedInputCalibration, PayloadFailure } from './pi_evaluation_payload.mjs';
import { decodeQueryDelivery, deliverySummary } from './pi_evaluation_delivery.mjs';
export const READ_TOOLS=Object.freeze(['query_symbols','check_diagnostics','health_check']);
export const Outcome = Object.freeze({RUNNING:'RUNNING', FINAL_ANSWER:'FINAL_ANSWER', QUALIFIED_ANSWER:'QUALIFIED_ANSWER', REJECTION:'INTENTIONAL_REJECTION', RPC_REJECTION:'TOOL_RPC_REJECTION', DELIVERY_BLOCKED:'CLIENT_DELIVERY_BLOCKED', BUDGET:'HARNESS_BUDGET_LIMIT', MODEL_MISMATCH:'HARNESS_MODEL_MISMATCH', TOOL_BLOCKED:'HARNESS_TOOL_BLOCKED', INVALID:'HARNESS_OBSERVATION_INVALID', PAYLOAD_UNSUPPORTED:PayloadFailure.UNSUPPORTED, INPUT_BOUND_UNAVAILABLE:PayloadFailure.UNPROVEN, INPUT_CALIBRATION_EXCEEDED:'HARNESS_INPUT_CALIBRATION_EXCEEDED', MODEL_ERROR:'MODEL_ERROR', CANCELLED:'HARNESS_CANCELLED'});
const RPC_FAILURES=Object.freeze(['INSTALLATION_STOPPED','OBSERVATION_UNAVAILABLE','CATALOG_UNAVAILABLE','INVALID_COMMAND','REQUEST_TOO_LARGE','UNKNOWN_TOOL','INVALID_ARGUMENTS','OUT_OF_SCOPE','INVOCATION_FAILED','INVALID_RESULT']);
const emptyUsage=()=>({input:0,cacheRead:0,cacheWrite:0,output:0,reasoning:0,totalTokens:0});
const integer=n=>Number.isSafeInteger(n)&&n>=0;
const decision=(allow,reason,phase)=>({allow,reason,phase});
export function decodeUsage(value) {
  if(!value||typeof value!=='object'||!['input','output','totalTokens'].every(k=>integer(value[k]))) return {type:'invalid'};
  const result=Object.fromEntries(Object.keys(emptyUsage()).map(k=>[k,value?.[k]??0]));
  if(!Object.values(result).every(integer)||result.reasoning>result.output || result.totalTokens!==result.input+result.cacheRead+result.cacheWrite+result.output) return {type:'invalid'};
  return {type:'usage',value:result};
}
function decodeCanonical(envelope,toolName='query_symbols') {
    if(!['complete','qualified','rejected_document','rejected'].includes(envelope?.type)) return {type:'invalid'};
    if(envelope.type==='rejected'&&!RPC_FAILURES.includes(envelope.failure)) return {type:'invalid'};
    if(envelope.type==='rejected_document'&&(envelope.document?.status!=='rejected'||typeof envelope.document?.rejection?.type!=='string')) return {type:'invalid'};
    if(envelope.type==='complete' && (!envelope.document || (toolName==='query_symbols'&&(envelope.document.status!=='complete' || typeof envelope.document.coverage?.exhaustive!=='boolean' || !Array.isArray(envelope.document.items))))) return {type:'invalid'};
    if(envelope.type==='qualified' && (envelope.document?.status!=='qualified'||envelope.document.coverage?.exhaustive!==false||!Array.isArray(envelope.document.items)||!integer(envelope.document.qualification?.knownMinimum)||!Array.isArray(envelope.document.qualification?.limitations)||!['resumable','terminal_incomplete','retention_unavailable'].includes(envelope.document.qualification?.progress?.type))) return {type:'invalid'};
    return envelope;
}
export function decodeEnvelope(content,toolName='query_symbols') {
  if(!Array.isArray(content)||content.length!==1||content[0]?.type!=='text') return {type:'invalid'};
  try {
    const envelope=JSON.parse(content[0].text);
    if(envelope?.type==='query_delivery')return toolName==='query_symbols'?decodeQueryDelivery(envelope,reply=>decodeCanonical(reply,toolName)):{type:'invalid'};
    return decodeCanonical(envelope,toolName);
  } catch {return {type:'invalid'};}
}

export class CasePolicy {
  constructor(config) {
    for(const phase of ['work','delivery']) {
      const b=config[phase];
      if(!b || ![b.reportedTokens,b.requests,b.outputReserve].every(n=>integer(n)&&n>0)) throw Error('Explicit per-case work and delivery bounds required');
    }
    if(!integer(config.work.tools)||config.work.tools<=0 || !integer(config.inputTokenCeiling)||config.inputTokenCeiling<=0 || !integer(config.declarationByteCeiling)||config.declarationByteCeiling<=0 || !integer(config.expectedSemanticCalls)||config.expectedSemanticCalls<=0 || !integer(config.maximumToolTextBytes)||config.maximumToolTextBytes<=0) throw Error('Explicit input/context, semantic and byte bounds required');
    for(const key of ['maximumProviderPayloadBytes','maximumProviderRequests','maximumProviderResponseBytes','maximumReportedTokens']) {
      if(config[key]!==undefined&&(!integer(config[key])||config[key]<1)) throw Error('Finite provider bounds required');
    }
    this.maximumProviderPayloadBytes=Math.min(config.maximumProviderPayloadBytes??262144,262144);
    this.maximumProviderRequests=Math.min(config.maximumProviderRequests??3,3);
    this.maximumProviderResponseBytes=Math.min(config.maximumProviderResponseBytes??65536,65536);
    if(![this.maximumProviderRequests,this.maximumProviderResponseBytes].every(n=>integer(n)&&n>0)) throw Error('Finite provider request and response limits required');
    if(!integer(this.maximumProviderPayloadBytes)||this.maximumProviderPayloadBytes<1||this.maximumProviderPayloadBytes>4194304) throw Error('Finite provider payload byte limit required');
    this.maximumReportedTokens=config.maximumReportedTokens??(config.work.reportedTokens+config.delivery.reportedTokens);
    if(!integer(this.maximumReportedTokens)||this.maximumReportedTokens<1) throw Error('Finite cumulative post-response token threshold required');
    this.config=structuredClone(config);this.phase='WORK';this.outcome=Outcome.RUNNING;this.nativeOutcome='UNOBSERVED';
    this.usage=emptyUsage();this.phaseUsage={WORK:emptyUsage(),DELIVERY:emptyUsage()};this.requests={WORK:0,DELIVERY:0};this.requestObservations=[];
    this.proposals=[];this.tools=0;this.resultCount=0;this.toolTextBytes=0;this.finalAnswer=false;this.exhaustiveEvidence=false;this.evidenceRequest=undefined;this.providerInFlight=false;
    this.lastMeasuredInput=0;this.inputCalibrations=new Map();this.outputCapApplied=false;this.queryDeliveries=[];
  }
  stop(outcome) {if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);this.outcome=outcome;this.phase='STOPPED';return decision(false,outcome,this.phase);}
  restoreReceivedResult(envelope,textBytes) {
    if(this.phase!=='WORK'||this.tools||this.requests.WORK||!['complete','qualified','query_delivery'].includes(envelope.type)) return this.stop(Outcome.INVALID);
    this.restoringReceived=true;
    let observed;
    try {observed=this.toolResult(envelope,textBytes);} finally {this.restoringReceived=false;}
    if(!observed.allow) return observed;
    this.maximumProviderRequests=Math.min(this.maximumProviderRequests,1);
    this.phase='DELIVERY';
    return decision(true,'SAVED_RESULT_DELIVERY',this.phase);
  }
  providerOutputCap() {return Math.min(2000,(this.phase==='WORK'?this.config.work:this.config.delivery).outputReserve);}
  registerInputCalibration(calibration) {
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    if(!isVerifiedInputCalibration(calibration) || this.requests.WORK || this.requests.DELIVERY || calibration.calibratedInputCeiling>this.config.inputTokenCeiling || this.inputCalibrations.size>=64 || this.inputCalibrations.has(calibration.payloadSha256)) return this.stop(Outcome.INVALID);
    this.inputCalibrations.set(calibration.payloadSha256,calibration);
    return decision(true,'CALIBRATION_REGISTERED',this.phase);
  }
  beforeProvider(observation) {
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    if(observation.provider!=='openai-codex'||observation.model!=='gpt-6.1-sol'||observation.thinking!=='high') return this.stop(Outcome.MODEL_MISMATCH);
    const payload=observation.payload;
    if(!isAdmittedPayload(payload)) return this.stop(Object.values(PayloadFailure).includes(payload?.reason)?payload.reason:Outcome.INVALID);
    if(payload.payloadModel!=='gpt-6.1-sol'||payload.effort!=='high') return this.stop(Outcome.MODEL_MISMATCH);
    const bound=this.phase==='WORK'?this.config.work:this.config.delivery;
    if(payload.outputTokenCap!==this.providerOutputCap()) return this.stop(Outcome.INVALID);
    // This proves the local payload setting only; no transport/backend fact follows.
    this.outputCapApplied=true;
    const calibration=this.inputCalibrations.get(payload.payloadSha256);
    const remaining=this.maximumReportedTokens-this.usage.totalTokens;
    // Calibration and the measured floor are empirical observations, never a
    // pre-inference spend guarantee. Exact complete serialized bytes and finite
    // calls are the authority for novel and dynamically changed requests.
    const inputEstimate=calibration ? Math.max(calibration.calibratedInputCeiling,this.lastMeasuredInput) : Math.max(payload.payloadBytes,this.lastMeasuredInput);
    const inputEstimateMethod=calibration?'VERIFIED_FULL_PAYLOAD_EMPIRICAL_CALIBRATION':'FULL_SERIALIZED_UTF8_BYTES_ONE_TOKEN_PER_BYTE_ESTIMATE';
    const inputEstimateQualification=calibration?'CALIBRATION_NOT_TOKENIZER_PROOF':'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF';
    const required=inputEstimate+bound.outputReserve;
    const withinBytes=payload.payloadBytes<=this.maximumProviderPayloadBytes && payload.toolsBytes+payload.declarationInputBytes+payload.instructionsBytes<=this.config.declarationByteCeiling;
    const totalRequests=this.requests.WORK+this.requests.DELIVERY;
    const reason=!withinBytes||this.providerInFlight||this.requests[this.phase]>=bound.requests||totalRequests>=this.maximumProviderRequests||remaining<=0?Outcome.BUDGET:'ADMITTED';
    this.requestObservations.push({...payload,phase:this.phase,inputEstimate,inputEstimateMethod,inputEstimateQualification,measuredInputFloor:this.lastMeasuredInput,required,remaining,allow:reason==='ADMITTED',reason,boundMethod:'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS',boundQualification:'POST_RESPONSE_TOKEN_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT',calibratedInputCeiling:calibration?.calibratedInputCeiling??null,calibrationMethod:calibration?.method??null,calibrationQualification:calibration?.qualification??null,calibrationSourceSha256:calibration?.sourceSha256??null});
    if(reason!=='ADMITTED') return this.stop(reason);
    this.requests[this.phase]++;this.providerInFlight=true;this.lastRequestPhase=this.phase;this.activeInputCalibration=calibration;
    return decision(true,'ADMITTED',this.phase);
  }
  modelUsage(raw,stopReason) {
    if(this.phase==='STOPPED'&&!this.providerInFlight) return decision(false,this.outcome,this.phase);
    const decoded=decodeUsage(raw);
    if(decoded.type==='invalid') return this.stop(Outcome.INVALID);
    const usage=decoded.value;
    if(usage.totalTokens===0) {
      this.providerInFlight=false;
      if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
      return this.stop(stopReason==='error'?Outcome.MODEL_ERROR:stopReason==='aborted'?Outcome.CANCELLED:Outcome.INVALID);
    }
    if(!this.providerInFlight) return this.stop(Outcome.INVALID);
    this.providerInFlight=false;
    for(const key of Object.keys(usage)) {this.usage[key]+=usage[key];this.phaseUsage[this.lastRequestPhase][key]+=usage[key];}
    this.lastMeasuredInput=usage.input+usage.cacheRead+usage.cacheWrite;
    const bound=this.lastRequestPhase==='WORK'?this.config.work:this.config.delivery;
    if(this.activeInputCalibration&&this.lastMeasuredInput>this.activeInputCalibration.calibratedInputCeiling) {
      this.inputCalibrationFailure={type:Outcome.INPUT_CALIBRATION_EXCEEDED,payloadSha256:this.activeInputCalibration.payloadSha256,sourceSha256:this.activeInputCalibration.sourceSha256,calibratedInputCeiling:this.activeInputCalibration.calibratedInputCeiling,observedInput:this.lastMeasuredInput};
    }
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    if(stopReason==='error'||stopReason==='aborted') return this.stop(stopReason==='error'?Outcome.MODEL_ERROR:Outcome.CANCELLED);
    if(usage.output>Math.min(2000,bound.outputReserve)||this.usage.totalTokens>=this.maximumReportedTokens) return this.stop(Outcome.BUDGET);
    return decision(true,'ACCOUNTED',this.phase);
  }
  providerResponseBytes(bytes) {
    if(!integer(bytes)) return this.stop(Outcome.INVALID);
    this.maximumObservedResponseBytes=Math.max(this.maximumObservedResponseBytes??0,bytes);
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    return bytes>this.maximumProviderResponseBytes?this.stop(Outcome.BUDGET):decision(true,'RESPONSE_WITHIN_BYTES',this.phase);
  }
  toolCall(toolName,args,callId) {
    const proposal={callId,toolName,requestType:args?.request?.type??null,harnessDecision:undefined,adapterStartObserved:false,rpcReplyObserved:false,nativeReplyObserved:false};this.proposals.push(proposal);
    let allowed=false;
    if(this.phase==='WORK') allowed=READ_TOOLS.includes(toolName) && (toolName!=='query_symbols'||args?.request?.type==='RUN') && this.tools<this.config.work.tools && !this.activeToolCall;
    if(this.phase==='DELIVERY'&&this.evidenceRequest) allowed=toolName==='query_symbols' && isDeepStrictEqual(args?.request,this.evidenceRequest) && !this.activeToolCall;
    if(this.phase==='STOPPED') {proposal.harnessDecision=this.outcome;return decision(false,this.outcome,this.phase);}
    if(!allowed) {proposal.harnessDecision=Outcome.TOOL_BLOCKED;return this.stop(Outcome.TOOL_BLOCKED);}
    this.tools++;this.activeToolCall=callId;proposal.harnessDecision='ADMITTED';return decision(true,'ADMITTED',this.phase);
  }
  adapterStarted(callId) {const p=this.proposals.find(p=>p.callId===callId);if(p) p.adapterStartObserved=true;}
  toolResult(envelope,textBytes=0,callId,hostIsError) {
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    if(!integer(textBytes)) return this.stop(Outcome.INVALID);
    this.toolTextBytes+=textBytes;
    const proposal=this.proposals.find(p=>p.callId===callId);
    if(callId!==undefined&&!proposal) return this.stop(Outcome.INVALID);
    if(callId===this.activeToolCall) this.activeToolCall=undefined;
    if(envelope.type==='invalid') return this.stop(Outcome.INVALID);
    if(proposal) {proposal.rpcReplyObserved=true;proposal.nativeReplyObserved=envelope.type!=='rejected';proposal.hostIsErrorObserved=typeof hostIsError==='boolean'?hostIsError:null;}
    if(this.toolTextBytes>this.config.maximumToolTextBytes) return this.stop(Outcome.BUDGET);
    if(envelope.type==='query_delivery') {
      this.queryDeliveries.push(deliverySummary(envelope,callId,this.restoringReceived===true?'SAVED_RESULT':'TOOL_RESULT'));
      const replies=[envelope.initial,...envelope.pages].filter(reply=>reply!==null);
      if(proposal)proposal.nativeReplyObserved=replies.some(reply=>reply.type!=='rejected');
      // Observe one logical result. Do not replay pages as semantic results or
      // invoke the client's continuation machinery again from this harness.
      if(envelope.initial===null) {this.nativeOutcome='UNOBSERVED';this.exhaustiveEvidence=false;return this.stop(Outcome.DELIVERY_BLOCKED);}
      const observed=this.acceptQuery(envelope.initial,true);
      if(envelope.delivery.stop!=='DELIVERED') {this.exhaustiveEvidence=false;this.evidenceRequest=undefined;return this.stop(Outcome.DELIVERY_BLOCKED);}
      return observed;
    }
    if(proposal&&proposal.toolName!=='query_symbols'&&envelope.type==='complete')return decision(true,'READ_TOOL_REPLY',this.phase);
    return this.acceptQuery(envelope,false);
  }
  acceptQuery(envelope,clientDelivered) {
    if(envelope.type==='rejected') {this.nativeOutcome='UNOBSERVED';this.rpcRejection={failure:envelope.failure};return this.stop(Outcome.RPC_REJECTION);}
    if(envelope.type==='rejected_document') {
      this.exhaustiveEvidence=false;this.nativeOutcome='REJECTED';this.rejection=structuredClone(envelope);
      const detail=envelope.document?.rejection?.detail;
      this.rejectionSummary={code:envelope.document?.rejection?.type??'TRANSPORT_REJECTION',nextAction:envelope.document?.next_action??null,
        knownMinimum:detail?.originalCoverage?.knownMinimum??null,limitations:detail?.originalCoverage?.limitations??[],progress:detail?.originalCoverage?.progress??null,policyProgress:detail?.policyProgress?.type??null};
      this.semanticRejected=true;
      const next=envelope.document?.rejection?.detail?.evidence?.nextQuery?.request;
      // Only the exact product-issued READ_RESULT is allowed by explicit opt-in.
      // RUN/RESUME, changed cursor/grants/scope and arbitrary reads never pass.
      if(clientDelivered&&this.config.evidenceOnlyDelivery===true) {this.evidenceRequest=undefined;this.phase='DELIVERY';return decision(true,'DECLARED_ALREADY_DELIVERED_PROOF',this.phase);}
      if(!clientDelivered && this.config.evidenceOnlyDelivery===true && next?.type==='READ_RESULT') {this.evidenceRequest=structuredClone(next);this.phase='DELIVERY';return decision(true,'DECLARED_EVIDENCE_ONLY_DELIVERY',this.phase);}
      return this.stop(Outcome.REJECTION);
    }
    if(envelope.type==='qualified') {
      this.resultCount++;this.semanticQualified=true;this.qualification=structuredClone(envelope.document.qualification);
      this.exhaustiveEvidence=false;if(!this.semanticRejected)this.nativeOutcome='QUALIFIED_RESULT';
      this.phase='DELIVERY';this.evidenceRequest=undefined;
      return decision(true,'QUALIFIED_RESULT_RECEIVED',this.phase);
    }
    if(envelope.type!=='complete'||envelope.document?.status!=='complete'||typeof envelope.document.coverage?.exhaustive!=='boolean') return this.stop(Outcome.INVALID);
    this.resultCount++;this.exhaustiveEvidence=envelope.document.coverage.exhaustive;
    if(!this.semanticRejected) this.nativeOutcome=this.exhaustiveEvidence?'EXHAUSTIVE_RESULT':'QUALIFIED_RESULT';
    if(this.resultCount>=this.config.expectedSemanticCalls) this.phase='DELIVERY';
    if(this.evidenceRequest) this.evidenceRequest=undefined;
    return decision(true,'RESULT_RECEIVED',this.phase);
  }
  assistantEnded(message) {
    this.finalTextObserved ||= message.stopReason==='stop'&&(message.content?.some(b=>b.type==='text'&&typeof b.text==='string'&&b.text.trim().length>0)??false);
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    if(message.stopReason==='error') return this.stop(Outcome.MODEL_ERROR);
    if(message.stopReason==='aborted') return this.stop(Outcome.CANCELLED);
    if(message.stopReason==='stop') {this.finalAnswer=message.content?.some(b=>b.type==='text'&&typeof b.text==='string'&&b.text.trim().length>0)??false;return this.stop(this.finalAnswer?(this.semanticRejected?Outcome.REJECTION:this.semanticQualified?Outcome.QUALIFIED_ANSWER:Outcome.FINAL_ANSWER):Outcome.INVALID);}
    if(message.stopReason!=='toolUse') return this.stop(Outcome.INVALID);
    return decision(true,'TOOL_PROPOSAL_PENDING',this.phase);
  }
  report() {
    return {case:this.config.name,outcome:this.outcome,phase:this.phase,nativeOutcome:this.nativeOutcome,finalAnswer:this.finalAnswer,finalTextObserved:this.finalTextObserved??false,exhaustiveEvidence:this.exhaustiveEvidence,
      // External row/oracle proof is never inferred from a final answer/row count.
      exhaustiveRowsVerified:false,requiredEvidenceVerified:false,usage:structuredClone(this.usage),phaseUsage:structuredClone(this.phaseUsage),inputCalibrationFailure:structuredClone(this.inputCalibrationFailure??null),measuredInputFloor:this.lastMeasuredInput,
      bounds:{work:structuredClone(this.config.work),delivery:structuredClone(this.config.delivery),inputTokenCeiling:this.config.inputTokenCeiling,maximumProviderPayloadBytes:this.maximumProviderPayloadBytes,maximumProviderRequests:this.maximumProviderRequests,maximumReportedTokens:this.maximumReportedTokens,maximumProviderResponseBytes:this.maximumProviderResponseBytes,declarationByteCeiling:this.config.declarationByteCeiling,maximumToolTextBytes:this.config.maximumToolTextBytes,wallSeconds:this.config.wallSeconds},
      queryDeliveries:structuredClone(this.queryDeliveries),deliveryPhysicalRpcCountReported:this.queryDeliveries.filter(d=>d.observation==='TOOL_RESULT').reduce((sum,d)=>sum+d.rpcCount,0),savedDeliveryPhysicalRpcCountReported:this.queryDeliveries.filter(d=>d.observation==='SAVED_RESULT').reduce((sum,d)=>sum+d.rpcCount,0),rpcRejection:structuredClone(this.rpcRejection??null),qualification:structuredClone(this.qualification??null),semanticRejection:structuredClone(this.rejectionSummary??null),requestObservations:structuredClone(this.requestObservations),modelProposals:structuredClone(this.proposals),nativeRepliesObserved:this.proposals.filter(p=>p.nativeReplyObserved).length,semanticRepliesObserved:this.proposals.filter(p=>p.nativeReplyObserved&&(p.requestType==='RUN'||p.toolName==='check_diagnostics')).length,toolTextBytes:this.toolTextBytes,requestAdmissionMethod:'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS',tokenAccountingQualification:'POST_RESPONSE_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT',maximumObservedResponseBytes:this.maximumObservedResponseBytes??0,providerRequests:this.requests.WORK+this.requests.DELIVERY,outputCapApplied:this.outputCapApplied,backendOutputCapQualification:'UNQUALIFIED'};
  }
}
