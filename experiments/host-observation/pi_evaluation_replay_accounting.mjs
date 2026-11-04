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

// A campaign claim needs a fixed task cohort and complete live measurements.
// Synthetic receipts exercise this arithmetic but cannot establish model gains.
export function assessReplayTarget({target,baseline,candidate}={}) {
 const rejected=reason=>({type:'REPLAY_TARGET_ASSESSMENT',status:'INVALID_COMPARISON',reasons:[reason]});
 if(!target||Object.keys(target).some(key=>!['factor','dimensions','caseIds','repetitions'].includes(key))||!Number.isFinite(target.factor)||target.factor<=1||target.repetitions!==2
  ||!['TOKENS_AND_LATENCY','TOKENS','LATENCY'].includes(target.dimensions)
  ||!Array.isArray(target.caseIds)||!target.caseIds.length||new Set(target.caseIds).size!==target.caseIds.length
  ||target.caseIds.some(id=>!/^C[1-6]$/.test(id))||!Array.isArray(baseline)||!Array.isArray(candidate))return rejected('TARGET_REJECTED');
 if([...baseline,...candidate].some(row=>!row||typeof row!=='object'||typeof row.trialId!=='string'
  ||!Array.isArray(row.outcome?.reasons)))return rejected('RECEIPT_REJECTED');
 const expected=target.caseIds.flatMap(caseId=>[1,2].map(repetition=>({caseId,repetition,key:`${caseId}-r${repetition}`})));
 const keys=new Set(expected.map(item=>item.key)),issues=new Set(),invalid=new Set();
 const index=records=>{
  const byKey=new Map();
  for(const row of records){
   const key=`${row?.caseId}-r${row?.repetition}`;
   if(!keys.has(key)||byKey.has(key)||!outcomes.has(row?.outcome?.type)){invalid.add('COHORT_REJECTED');continue;}
   byKey.set(key,row);
  }
  return byKey;
 };
 const controls=index(baseline),candidates=index(candidate);
 if(invalid.size)return rejected('COHORT_REJECTED');
 const identityFields=['provider','model','effort','piVersion','fixtureVersion','canonicalSha256','promptSha256','contractSha256','graderSha256','sdkIdentity','budgetSha256','timingMethod','credentialSource','principalSha256'];
 const modelIdentity=row=>{
  const value=row?.identity;
  if(!value)return null;
  return {...value,model:typeof value.model==='string'?value.model:value.model?.id,
   provider:value.provider??value.model?.provider,effort:value.effort??value.model?.effort};
 };
 const projectionValid=row=>row?.projection?.type==='projected'&&row.projection.complete===true&&row.projection.encodingEquivalent===true
  &&row.projection.presentationOccurrences===1&&row.actualProviderProjection?.type==='projected'
  &&row.actualProviderProjection.complete===true&&row.actualProviderProjection.presentationOccurrences===1
  &&typeof row.projection.payloadSha256==='string'&&row.projection.payloadSha256===row.actualProviderProjection.payloadSha256;
 const pairs=expected.map(item=>{
  const control=controls.get(item.key),next=candidates.get(item.key);
  if(!control||!next)issues.add('MISSING_TRIAL');
  for(const row of [control,next])if(row){
   if(!eligible(row))issues.add('INVALID_TRIAL');
   if(row.protectedFilesUnchanged!==true)issues.add('PROTECTED_STATE_UNAVAILABLE');
   if(row.measurement!=='LIVE_MODEL')issues.add('LIVE_MEASUREMENT_UNAVAILABLE');
   if(!projectionValid(row))issues.add('PAYLOAD_PROOF_UNAVAILABLE');
   if(row.providerSends!==1||row.nativeCalls!==0||!count(row.shellCalls)||row.shellCalls>1
    ||!count(row.kastInvocations)||row.kastInvocations>1)issues.add('EFFECT_COUNTS_UNAVAILABLE');
   if(row.outcome.type==='correct'&&(row.grade?.correct!==true||gradeMisuse(row).length||coverage(row)===false))invalid.add('GRADE_CONTRADICTION');
  }
  if(control&&next){
   const left=modelIdentity(control),right=modelIdentity(next);
   if(!left||!right||identityFields.some(key=>left[key]===undefined||right[key]===undefined))issues.add('IDENTITY_UNAVAILABLE');
   else{
    if(identityFields.some(key=>JSON.stringify(left[key])!==JSON.stringify(right[key])))invalid.add('IDENTITY_MISMATCH');
    const sourceFields=['canonicalSha256','promptSha256','contractSha256','graderSha256','budgetSha256','principalSha256'];
    for(const identity of [left,right])if(sourceFields.some(key=>! /^[a-f0-9]{64}$/.test(identity[key]))
     ||!Array.isArray(identity.sdkIdentity)||!identity.sdkIdentity.length
     ||identity.sdkIdentity.some(item=>typeof item.file!=='string'||! /^[a-f0-9]{64}$/.test(item.sha256)))issues.add('IDENTITY_UNAVAILABLE');
   }
  }
  return {caseId:item.caseId,repetition:item.repetition,key:item.key,baselineTrialId:control?.trialId??null,candidateTrialId:next?.trialId??null,
   baselineOutcome:control?.outcome.type??'unavailable',candidateOutcome:next?.outcome.type??'unavailable',
   latencyRatio:control&&next&&eligible(control)&&eligible(next)&&duration(control.latency?.trialMs)&&control.latency.trialMs>0
    &&duration(next.latency?.trialMs)&&next.latency.trialMs>0?control.latency.trialMs/next.latency.trialMs:null};
 });
 const cost=records=>{
  const usages=records.map(row=>({row,usage:normalizeReplayUsage(row.usage)}));
  const missingUsage=usages.filter(item=>item.usage.type!=='known').map(item=>item.row.trialId);
  const knownTotal=sum(usages.filter(item=>item.usage.type==='known').map(item=>item.usage.value.totalTokens));
  const correct=records.filter(row=>row.outcome.type==='correct').length;
  const complete=records.length===expected.length&&!missingUsage.length;
  return {total:complete?knownTotal:null,knownTotal,correct,missingUsage,perCorrect:complete&&correct?knownTotal/correct:null};
 };
 const baselineCost=cost([...controls.values()]),candidateCost=cost([...candidates.values()]);
 const totals=records=>({eligible:records.filter(eligible).length,correct:records.filter(row=>row.outcome.type==='correct').length});
 const perCase=target.caseIds.map(caseId=>{
  const left=[...controls.values()].filter(row=>row.caseId===caseId),right=[...candidates.values()].filter(row=>row.caseId===caseId);
  const control=totals(left),next=totals(right);
  return {caseId,baseline:control,candidate:next,noAccuracyLoss:control.eligible===2&&next.eligible===2&&next.correct>=control.correct,
   supported:next.correct>0};
 });
 const noAccuracyLoss=perCase.every(item=>item.noAccuracyLoss);
 const supported=perCase.every(item=>item.supported);
 if(!noAccuracyLoss&&!issues.has('INVALID_TRIAL')&&!issues.has('MISSING_TRIAL'))issues.add('ACCURACY_REGRESSION');
 if(!supported)issues.add('CASE_UNSUCCESSFUL');
 const tokens={baseline:baselineCost,candidate:candidateCost,speedup:baselineCost.perCorrect!==null&&candidateCost.perCorrect!==null&&candidateCost.perCorrect>0?baselineCost.perCorrect/candidateCost.perCorrect:null};
 if(tokens.speedup===null)issues.add('TOKEN_MEASUREMENT_UNAVAILABLE');
 const caseRatios=target.caseIds.map(caseId=>{
  const selected=pairs.filter(pair=>pair.caseId===caseId);
  return {caseId,ratio:selected.length===2&&selected.every(pair=>pair.latencyRatio!==null)?median(selected.map(pair=>pair.latencyRatio)):null};
 });
 const latency={method:'MEDIAN_OF_CASE_MEDIAN_PAIRED_RATIOS',caseRatios,speedup:caseRatios.every(item=>item.ratio!==null)?median(caseRatios.map(item=>item.ratio)):null};
 if(latency.speedup===null)issues.add('LATENCY_MEASUREMENT_UNAVAILABLE');
 const wantedTokens=target.dimensions!=='LATENCY',wantedLatency=target.dimensions!=='TOKENS';
 if(wantedTokens&&tokens.speedup!==null&&tokens.speedup<target.factor)issues.add('TOKEN_TARGET_NOT_MET');
 if(wantedLatency&&latency.speedup!==null&&latency.speedup<target.factor)issues.add('LATENCY_TARGET_NOT_MET');
 const proofMissing=[...issues].some(reason=>['MISSING_TRIAL','INVALID_TRIAL','LIVE_MEASUREMENT_UNAVAILABLE','PAYLOAD_PROOF_UNAVAILABLE','EFFECT_COUNTS_UNAVAILABLE','IDENTITY_UNAVAILABLE','PROTECTED_STATE_UNAVAILABLE'].includes(reason)
  ||reason==='TOKEN_MEASUREMENT_UNAVAILABLE'&&wantedTokens||reason==='LATENCY_MEASUREMENT_UNAVAILABLE'&&wantedLatency);
 const contextProofMissing=[...issues].some(reason=>['MISSING_TRIAL','INVALID_TRIAL','LIVE_MEASUREMENT_UNAVAILABLE','PAYLOAD_PROOF_UNAVAILABLE','EFFECT_COUNTS_UNAVAILABLE','IDENTITY_UNAVAILABLE','PROTECTED_STATE_UNAVAILABLE'].includes(reason));
 if(invalid.size||contextProofMissing){tokens.speedup=null;latency.speedup=null;}
 const notMet=!noAccuracyLoss||!supported||wantedTokens&&tokens.speedup<target.factor||wantedLatency&&latency.speedup<target.factor;
 const ledger=records=>({sends:records.every(row=>count(row.providerSends))?sum(records.map(row=>row.providerSends)):null,
  invalid:records.filter(row=>!eligible(row)).map(row=>({trialId:row.trialId,reasons:row.outcome.reasons}))});
 return {type:'REPLAY_TARGET_ASSESSMENT',status:invalid.size?'INVALID_COMPARISON':proofMissing?'UNMEASURED':notMet?'NOT_MET':'TARGET_MET',
  target:{...target,caseIds:[...target.caseIds]},cases:target.caseIds.length,pairs,quality:{noAccuracyLoss,supported,perCase,baseline:totals([...controls.values()]),candidate:totals([...candidates.values()])},
  tokens,latency,accounting:{baseline:ledger(baseline),candidate:ledger(candidate)},costUnit:'PROVIDER_TOKENS',billedCost:{status:'UNAVAILABLE'},
  reasons:[...invalid,...issues],qualifications:['DECLARED_CASES_ONLY','REPETITIONS_ARE_NOT_INDEPENDENT_TASKS','TOKENS_ARE_NOT_BILLED_COST','SYNTHETIC_INPUTS_DO_NOT_PROVE_MODEL_PERFORMANCE']};
}
