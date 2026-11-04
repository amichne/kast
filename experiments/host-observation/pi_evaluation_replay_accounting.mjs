// Pure replay arithmetic. Unknown measurements remain unavailable.
const formats=Object.freeze(['TOON','JSON']);
export const ReplayOutcome=Object.freeze({CORRECT:'correct',INCORRECT:'incorrect',INCOMPLETE:'incomplete',INVALID:'invalid',CANCELLED:'cancelled-before-start'});
const outcomes=new Set(Object.values(ReplayOutcome));
const eligibleTypes=new Set([ReplayOutcome.CORRECT,ReplayOutcome.INCORRECT,ReplayOutcome.INCOMPLETE]);
const count=value=>Number.isSafeInteger(value)&&value>=0;
const duration=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
const unavailable=reason=>Object.freeze({type:'unavailable',reason});

// Pi usage.input excludes cacheRead/cacheWrite. Reasoning is part of output.
// Missing cache accounting cannot silently become zero.
export function normalizeReplayUsage(raw) {
  if(raw===null||raw===undefined)return unavailable('USAGE_UNAVAILABLE');
  if(!['input','cacheRead','cacheWrite','output','totalTokens'].every(key=>count(raw[key])))return unavailable('USAGE_INVALID');
  const reasoning=raw.reasoning??null;
  if(reasoning!==null&&(!count(reasoning)||reasoning>raw.output))return unavailable('USAGE_INVALID');
  const sum=raw.input+raw.cacheRead+raw.cacheWrite+raw.output;
  if(!Number.isSafeInteger(sum)||sum!==raw.totalTokens)return unavailable('USAGE_INVALID');
  return Object.freeze({type:'known',value:Object.freeze({input:raw.input,cacheRead:raw.cacheRead,cacheWrite:raw.cacheWrite,output:raw.output,reasoning,totalTokens:raw.totalTokens})});
}
const median=values=>{
  if(values.length===0)return null;
  const sorted=[...values].sort((a,b)=>a-b),middle=Math.floor(sorted.length/2);
  return sorted.length%2?sorted[middle]:(sorted[middle-1]+sorted[middle])/2;
};
const sum=values=>{
  const total=values.reduce((acc,value)=>acc+value,0);
  if(!Number.isSafeInteger(total))throw Error('Accounting total exceeds exact integer range');
  return total;
};
const tally=values=>values.reduce((acc,value)=>({...acc,[value]:(acc[value]??0)+1}),{});
const eligible=row=>eligibleTypes.has(row.outcome.type);
const misuseReasons=new Set(['WRONG_ACTION','WRONG_TOOL','WRONG_TARGET','WRONG_SCOPE','WRONG_REFERENCE','WRONG_COVERAGE','MALFORMED_DECISION']);
const gradeMisuse=row=>Array.isArray(row.grade?.misuse)?row.grade.misuse:(row.grade?.reasons??[]).filter(reason=>misuseReasons.has(reason));
const argumentCause=row=>row.grade?.argumentCause??row.grade?.productionCause??null;
const coverage=row=>typeof row.grade?.coverageCorrect==='boolean'?row.grade.coverageCorrect:
  row.grade?.dimensions?.coverage==='PASS'?true:row.grade?.dimensions?.coverage==='FAIL'?false:null;
const measurement=(rows,read)=>{
  const known=rows.map(row=>({trialId:row.trialId,value:read(row)}));
  const missing=known.filter(value=>value.value===null).map(value=>value.trialId);
  return {knownTotal:sum(known.filter(value=>value.value!==null).map(value=>value.value)),complete:missing.length===0,missing};
};
const field=(row,key)=>count(row[key])?row[key]:null;
const identityKeys=['trialId','pairId','caseId','format','repetition','armOrder'];
function admitPlans(plans) {
  if(!Array.isArray(plans)||plans.length===0)throw Error('Nonempty planned trials required');
  const ids=new Set(),pairs=new Map();
  for(const plan of plans) {
    if(identityKeys.some(key=>!Object.hasOwn(plan,key))||typeof plan.trialId!=='string'||!plan.trialId||typeof plan.pairId!=='string'||!plan.pairId||typeof plan.caseId!=='string'||!plan.caseId||!formats.includes(plan.format)||!count(plan.repetition)||plan.repetition<1||!count(plan.armOrder)||plan.armOrder>1)throw Error('Explicit trial identity required');
    if(ids.has(plan.trialId))throw Error('Duplicate planned trial');
    ids.add(plan.trialId);
    const pair=pairs.get(plan.pairId)??[];
    pair.push(plan);pairs.set(plan.pairId,pair);
  }
  for(const pair of pairs.values())if(pair.length!==2||pair[0].format===pair[1].format||pair[0].armOrder===pair[1].armOrder||pair[0].caseId!==pair[1].caseId||pair[0].repetition!==pair[1].repetition)throw Error('A pair needs two distinct format arms with one case and repetition');
  return pairs;
}
function admitReceipts(plans,receipts) {
  if(!Array.isArray(receipts))throw Error('Explicit receipts required');
  const byId=new Map(),planned=new Map(plans.map(plan=>[plan.trialId,plan]));
  for(const receipt of receipts) {
    const plan=planned.get(receipt.trialId);
    if(!plan)throw Error('Unplanned receipt');
    if(byId.has(receipt.trialId))throw Error('Duplicate receipt');
    if(identityKeys.some(key=>receipt[key]!==plan[key]))throw Error('Receipt identity mismatch');
    if(!outcomes.has(receipt.outcome?.type)||!Array.isArray(receipt.outcome?.reasons)||receipt.outcome.reasons.some(reason=>typeof reason!=='string'||reason.length===0))throw Error('Unknown replay outcome');
    if(receipt.outcome.type===ReplayOutcome.CANCELLED&&receipt.providerSends!==0)throw Error('Cancellation before start cannot contain provider sends');
    byId.set(receipt.trialId,receipt);
  }
  return plans.map(plan=>{
    const receipt=byId.get(plan.trialId)??{...plan,outcome:{type:ReplayOutcome.INVALID,reasons:['REPORT_UNAVAILABLE']},usage:null,providerSends:null,shellCalls:null,kastInvocations:null,nativeCalls:null,latency:null,grade:null};
    return {...receipt,normalizedUsage:normalizeReplayUsage(receipt.usage)};
  });
}
function formatSummary(rows) {
  const eligibleRows=rows.filter(eligible),correct=eligibleRows.filter(row=>row.outcome.type===ReplayOutcome.CORRECT).length;
  const usage=measurement(eligibleRows,row=>row.normalizedUsage.type==='known'?row.normalizedUsage.value.totalTokens:null);
  const tokenStatus=correct===0?'UNDEFINED':usage.complete?'COMPLETE':'PARTIAL';
  const measurable=rows.filter(row=>duration(row.latency?.trialMs));
  return {
    planned:rows.length,outcomes:tally(rows.map(row=>row.outcome.type)),
    correctness:{correct,eligible:eligibleRows.length,ratio:eligibleRows.length?correct/eligibleRows.length:null},
    tokens:usage,
    tokensPerCorrectCompletion:{status:tokenStatus,value:tokenStatus==='COMPLETE'?usage.knownTotal/correct:null,knownTokens:usage.knownTotal,correct,missingUsage:usage.missing},
    argumentRejections:tally(rows.map(argumentCause).filter(value=>typeof value==='string')),
    misuse:tally(rows.flatMap(gradeMisuse)),
    coverage:{correct:eligibleRows.filter(row=>coverage(row)===true).length,incorrect:eligibleRows.filter(row=>coverage(row)===false).length,notApplicable:eligibleRows.filter(row=>row.grade?.dimensions?.coverage==='NOT_APPLICABLE').length,unavailable:eligibleRows.filter(row=>coverage(row)===null&&row.grade?.dimensions?.coverage!=='NOT_APPLICABLE').map(row=>row.trialId)},
    latency:{measurable:measurable.length,medianTrialMs:median(measurable.map(row=>row.latency.trialMs)),queueMs:rows.map(row=>({trialId:row.trialId,value:duration(row.latency?.queueMs)?row.latency.queueMs:null}))},
  };
}

// Every planned identity appears once. Duplicate or foreign records fail closed.
export function summarizeReplay(plans,receipts) {
  const pairs=admitPlans(plans),rows=admitReceipts(plans,receipts);
  const byId=new Map(rows.map(row=>[row.trialId,row]));
  const exclusions=rows.filter(row=>!eligible(row)).map(row=>({trialId:row.trialId,pairId:row.pairId,outcome:row.outcome.type,reasons:[...row.outcome.reasons]}));
  const correctness={count:0,toonWins:0,jsonWins:0,ties:0,pairs:[]};
  const tokenDeltas=[],latencyDeltas=[],missingPairs=[];
  for(const [pairId,planned] of pairs) {
    const pair=planned.map(plan=>byId.get(plan.trialId));
    const toon=pair.find(row=>row.format==='TOON'),json=pair.find(row=>row.format==='JSON');
    if(pair.some(row=>!eligible(row)||row.outcome.type===ReplayOutcome.INCOMPLETE))missingPairs.push({pairId,arms:pair.filter(row=>!eligible(row)||row.outcome.type===ReplayOutcome.INCOMPLETE).map(row=>({trialId:row.trialId,outcome:row.outcome.type,reasons:[...row.outcome.reasons]}))});
    if(pair.every(eligible)) {
      const delta=Number(json.outcome.type===ReplayOutcome.CORRECT)-Number(toon.outcome.type===ReplayOutcome.CORRECT);
      correctness.count++;
      if(delta<0)correctness.toonWins++;else if(delta>0)correctness.jsonWins++;else correctness.ties++;
      correctness.pairs.push({pairId,toon:toon.outcome.type,json:json.outcome.type,correctnessJsonMinusToon:delta});
      if(pair.every(row=>duration(row.latency?.trialMs)))latencyDeltas.push({pairId,trialMsJsonMinusToon:json.latency.trialMs-toon.latency.trialMs,providerMsJsonMinusToon:pair.every(row=>duration(row.latency?.providerMs))?json.latency.providerMs-toon.latency.providerMs:null,shellMsJsonMinusToon:pair.every(row=>duration(row.latency?.shellMs))?json.latency.shellMs-toon.latency.shellMs:null});
      if(pair.every(row=>row.outcome.type===ReplayOutcome.CORRECT&&row.normalizedUsage.type==='known'))tokenDeltas.push({pairId,tokensJsonMinusToon:json.normalizedUsage.value.totalTokens-toon.normalizedUsage.value.totalTokens});
    }
  }
  return {
    type:'REPLAY_COMPARISON',outcome:'INCONCLUSIVE',plannedTrials:plans.length,plannedPairs:pairs.size,
    attemptedPairs:[...pairs.values()].filter(pair=>pair.some(plan=>(byId.get(plan.trialId).providerSends??0)>0)).length,
    distinctCases:new Set(plans.map(plan=>plan.caseId)).size,
    formats:Object.fromEntries(formats.map(format=>[format,formatSummary(rows.filter(row=>row.format===format))])),
    paired:{correctness,tokensBothCorrect:{count:tokenDeltas.length,deltas:tokenDeltas,medianDeltaJsonMinusToon:median(tokenDeltas.map(value=>value.tokensJsonMinusToon))},latency:{count:latencyDeltas.length,deltas:latencyDeltas,medianDeltaJsonMinusToon:median(latencyDeltas.map(value=>value.trialMsJsonMinusToon))}},
    exclusions,missingPairs,
    ledger:{providerSends:measurement(rows,row=>field(row,'providerSends')),shellCalls:measurement(rows,row=>field(row,'shellCalls')),kastInvocations:measurement(rows,row=>field(row,'kastInvocations')),nativeCalls:measurement(rows,row=>field(row,'nativeCalls')),tokens:measurement(rows,row=>row.normalizedUsage.type==='known'?row.normalizedUsage.value.totalTokens:null),trials:rows.map(row=>({trialId:row.trialId,outcome:row.outcome,providerSends:field(row,'providerSends'),shellCalls:field(row,'shellCalls'),kastInvocations:field(row,'kastInvocations'),nativeCalls:field(row,'nativeCalls'),usage:row.normalizedUsage,latency:row.latency??null}))},
    qualifications:['REPLAY_DECISIONS_GIVEN_FIXED_STATES','REPETITIONS_ARE_NOT_INDEPENDENT_TASKS','LATENCY_EXCLUDES_NATIVE_SEMANTIC_CLAIMS','NO_FORMAT_WINNER_REQUIRED'],
  };
}
