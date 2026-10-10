// Pure rules; no process, filesystem, provider, Kast, clock or credential access.
import { isDeepStrictEqual } from 'node:util';
import { decodeQueryDelivery, deliverySummary } from './pi_evaluation_delivery.mjs';
export const READ_TOOLS=Object.freeze(['query_symbols','check_diagnostics','health_check']);
export const Outcome = Object.freeze({RUNNING:'RUNNING', FINAL_ANSWER:'FINAL_ANSWER', QUALIFIED_ANSWER:'QUALIFIED_ANSWER', REJECTION:'INTENTIONAL_REJECTION', RPC_REJECTION:'TOOL_RPC_REJECTION', DELIVERY_BLOCKED:'CLIENT_DELIVERY_BLOCKED', BUDGET:'HARNESS_BUDGET_LIMIT', MODEL_MISMATCH:'HARNESS_MODEL_MISMATCH', TOOL_BLOCKED:'HARNESS_TOOL_BLOCKED', INVALID:'HARNESS_OBSERVATION_INVALID', MODEL_ERROR:'MODEL_ERROR', CANCELLED:'HARNESS_CANCELLED'});
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
    this.config=structuredClone(config);this.phase='WORK';this.outcome=Outcome.RUNNING;this.nativeOutcome='UNOBSERVED';
    this.usage=emptyUsage();this.phaseUsage={WORK:emptyUsage(),DELIVERY:emptyUsage()};this.requests={WORK:0,DELIVERY:0};this.requestObservations=[];
    this.proposals=[];this.tools=0;this.resultCount=0;this.toolTextBytes=0;this.contextGrowthCeiling=0;this.finalAnswer=false;this.exhaustiveEvidence=false;this.evidenceRequest=undefined;this.providerInFlight=false;
    this.lastMeasuredInput=0;this.growthSinceMeasured=0;this.queryDeliveries=[];
  }
  stop(outcome) {this.outcome=outcome;this.phase='STOPPED';return decision(false,outcome,this.phase);}
  restoreReceivedResult(envelope,textBytes) {
    if(this.phase!=='WORK'||this.tools||this.requests.WORK||!['complete','qualified','query_delivery'].includes(envelope.type)) return this.stop(Outcome.INVALID);
    this.restoringReceived=true;
    let observed;
    try {observed=this.toolResult(envelope,textBytes);} finally {this.restoringReceived=false;}
    if(!observed.allow) return observed;
    this.phase='DELIVERY';
    return decision(true,'SAVED_RESULT_DELIVERY',this.phase);
  }
  beforeProvider(observation) {
    if(this.phase==='STOPPED') return decision(false,this.outcome,this.phase);
    if(observation.provider!=='openai-codex'||observation.model!=='gpt-6.1-sol'||observation.thinking!=='high'||observation.payloadModel!=='gpt-6.1-sol'||observation.effort!=='high') return this.stop(Outcome.MODEL_MISMATCH);
    if(!['payloadBytes','toolsBytes','instructionsBytes','inputBytes'].every(k=>integer(observation[k]))) return this.stop(Outcome.INVALID);
    const bound=this.phase==='WORK'?this.config.work:this.config.delivery;
    // Declared input ceiling + conservative context-byte growth + generation
    // reserve. This is explicitly an estimate, never an exact tokenizer claim.
    const measuredInputFloor=this.lastMeasuredInput+this.growthSinceMeasured;
    const inputEstimate=Math.max(this.config.inputTokenCeiling+this.contextGrowthCeiling,measuredInputFloor);
    const required=inputEstimate+bound.outputReserve;
    const remaining=bound.reportedTokens-this.phaseUsage[this.phase].totalTokens;
    const allow=this.requests[this.phase]<bound.requests && required<=remaining && observation.toolsBytes<=this.config.declarationByteCeiling;
    this.requestObservations.push({...observation,phase:this.phase,inputEstimate,measuredInputFloor,required,remaining,allow});
    if(!allow) return this.stop(Outcome.BUDGET);
    this.requests[this.phase]++;this.providerInFlight=true;this.lastRequestPhase=this.phase;
    return decision(true,'ADMITTED',this.phase);
  }
  modelUsage(raw) {
    const decoded=decodeUsage(raw);
    if(decoded.type==='invalid') return this.stop(Outcome.INVALID);
    const usage=decoded.value;
    if(usage.totalTokens===0) return decision(true,'ZERO_USAGE',this.phase);
    if(!this.providerInFlight) return this.stop(Outcome.INVALID);
    this.providerInFlight=false;
    for(const key of Object.keys(usage)) {this.usage[key]+=usage[key];this.phaseUsage[this.lastRequestPhase][key]+=usage[key];}
    this.contextGrowthCeiling+=usage.output;
    this.lastMeasuredInput=usage.input+usage.cacheRead+usage.cacheWrite;this.growthSinceMeasured=usage.output;
    const bound=this.lastRequestPhase==='WORK'?this.config.work:this.config.delivery;
    if(this.phaseUsage[this.lastRequestPhase].totalTokens>bound.reportedTokens) return this.stop(Outcome.BUDGET);
    return decision(true,'ACCOUNTED',this.phase);
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
    this.toolTextBytes+=textBytes;this.contextGrowthCeiling+=textBytes;this.growthSinceMeasured+=textBytes;
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
      exhaustiveRowsVerified:false,requiredEvidenceVerified:false,usage:structuredClone(this.usage),phaseUsage:structuredClone(this.phaseUsage),
      bounds:{work:structuredClone(this.config.work),delivery:structuredClone(this.config.delivery),inputTokenCeiling:this.config.inputTokenCeiling,declarationByteCeiling:this.config.declarationByteCeiling,maximumToolTextBytes:this.config.maximumToolTextBytes,wallSeconds:this.config.wallSeconds},
      queryDeliveries:structuredClone(this.queryDeliveries),deliveryPhysicalRpcCountReported:this.queryDeliveries.filter(d=>d.observation==='TOOL_RESULT').reduce((sum,d)=>sum+d.rpcCount,0),savedDeliveryPhysicalRpcCountReported:this.queryDeliveries.filter(d=>d.observation==='SAVED_RESULT').reduce((sum,d)=>sum+d.rpcCount,0),rpcRejection:structuredClone(this.rpcRejection??null),qualification:structuredClone(this.qualification??null),semanticRejection:structuredClone(this.rejectionSummary??null),requestObservations:structuredClone(this.requestObservations),modelProposals:structuredClone(this.proposals),nativeRepliesObserved:this.proposals.filter(p=>p.nativeReplyObserved).length,semanticRepliesObserved:this.proposals.filter(p=>p.nativeReplyObserved&&(p.requestType==='RUN'||p.toolName==='check_diagnostics')).length,toolTextBytes:this.toolTextBytes,tokenPreflightMethod:'MAX_DECLARED_OR_MEASURED_INPUT_PLUS_CONTEXT_GROWTH_AND_OUTPUT_RESERVE',providerEnforcedTokenCap:false};
  }
}
