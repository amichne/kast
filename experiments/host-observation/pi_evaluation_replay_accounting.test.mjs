import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeReplayUsage, summarizeReplay } from './pi_evaluation_replay_accounting.mjs';

const trial=(pairId,format,caseId='C1')=>({trialId:`${pairId}-${format}`,pairId,caseId,format,repetition:1,armOrder:format==='TOON'?0:1});
const receipt=(planned,type='correct',extra={})=>({...planned,outcome:{type,reasons:type==='correct'?[]:['DECLARED_FAILURE']},usage:{input:80,cacheRead:20,cacheWrite:0,output:10,reasoning:4,totalTokens:110},providerSends:1,shellCalls:1,kastInvocations:1,nativeCalls:0,latency:{trialMs:100,queueMs:30,providerMs:60,shellMs:10},grade:{misuse:[],argumentCause:null,coverageCorrect:true},...extra});

test('blocked proposals remain visible with failed work and complete provider cost',()=>{
 const plans=[trial('p1','TOON'),trial('p1','JSON')];
 const rows=[receipt(plans[0],'incorrect',{toolProposals:2,blockedToolProposals:1,grade:{misuse:['RESOURCE_MISUSE']}}),receipt(plans[1],'correct',{toolProposals:1,blockedToolProposals:0})];
 const report=summarizeReplay(plans,rows);
 assert.deepEqual(report.formats.TOON.correctness,{correct:0,eligible:1,ratio:0});
 assert.deepEqual(report.formats.TOON.misuse,{RESOURCE_MISUSE:1});
 assert.equal(report.ledger.toolProposals.knownTotal,3);assert.equal(report.ledger.toolProposals.complete,true);
 assert.equal(report.ledger.blockedToolProposals.knownTotal,1);assert.equal(report.ledger.tokens.knownTotal,220);
 assert.equal(report.ledger.trials[0].blockedToolProposals,1);assert.equal(report.exclusions.length,0);
 const legacy=summarizeReplay(plans,plans.map(plan=>receipt(plan)));
 assert.equal(legacy.ledger.toolProposals.complete,false);assert.equal(legacy.ledger.trials[0].toolProposals,null);
});

test('a partial worker ledger preserves known resource misuse without a final grade',()=>{
 const plans=[trial('p1','TOON'),trial('p1','JSON')];
 for(const counts of [{toolProposals:2,blockedToolProposals:1},{kastInvocations:2},{blockedShellCalls:1},{blockedProviderSends:1}]){
  const rows=plans.map(plan=>receipt(plan,'incomplete',{...counts,grade:null}));
  const report=summarizeReplay(plans,rows);
  assert.deepEqual(report.formats.TOON.misuse,{RESOURCE_MISUSE:1});
  assert.equal(report.formats.TOON.correctness.eligible,1);assert.equal(report.formats.TOON.correctness.correct,0);
  assert.equal(report.ledger.tokens.knownTotal,220);assert.equal(report.exclusions.length,0);
 }
});

test('SDK usage counts cache once and reasoning within output',()=>{
  assert.deepEqual(normalizeReplayUsage({input:80,cacheRead:20,cacheWrite:3,output:10,reasoning:4,totalTokens:113}),{type:'known',value:{input:80,cacheRead:20,cacheWrite:3,output:10,reasoning:4,totalTokens:113}});
  assert.equal(normalizeReplayUsage({input:80,cacheRead:20,output:10,totalTokens:130}).type,'unavailable');
  assert.equal(normalizeReplayUsage({input:80,cacheRead:20,output:10,reasoning:11,totalTokens:110}).type,'unavailable');
  assert.equal(normalizeReplayUsage(null).type,'unavailable');
  assert.equal(normalizeReplayUsage({input:80,output:10,totalTokens:90}).type,'unavailable');
});

test('failures and incomplete model outcomes remain in correctness and token populations',()=>{
  const plans=[trial('p1','TOON'),trial('p1','JSON'),trial('p2','TOON','C2'),trial('p2','JSON','C2')];
  const report=summarizeReplay(plans,[receipt(plans[0]),receipt(plans[1],'incorrect',{grade:{misuse:['WRONG_TARGET'],argumentCause:null,coverageCorrect:false}}),receipt(plans[2],'incomplete'),receipt(plans[3])]);
  assert.deepEqual(report.formats.TOON.correctness,{correct:1,eligible:2,ratio:0.5});
  assert.deepEqual(report.formats.JSON.correctness,{correct:1,eligible:2,ratio:0.5});
  assert.equal(report.formats.TOON.tokensPerCorrectCompletion.value,220);
  assert.equal(report.paired.correctness.toonWins,1);
  assert.equal(report.paired.correctness.jsonWins,1);
  assert.equal(report.paired.correctness.ties,0);
  assert.equal(report.paired.correctness.count,2);
  assert.equal(report.missingPairs.length,1);
  assert.equal(report.missingPairs[0].arms[0].outcome,'incomplete');
  assert.deepEqual(report.formats.JSON.misuse,{WRONG_TARGET:1});
  assert.equal(report.distinctCases,2);
  assert.equal(report.plannedPairs,2);
});

test('invalid and cancelled trials are excluded individually while their costs remain visible',()=>{
  const plans=[trial('p1','TOON'),trial('p1','JSON'),trial('p2','TOON'),trial('p2','JSON')];
  const report=summarizeReplay(plans,[receipt(plans[0],'invalid',{outcome:{type:'invalid',reasons:['TRANSPORT_FAILURE']}}),receipt(plans[1]),receipt(plans[2],'cancelled-before-start',{usage:null,providerSends:0,shellCalls:0,kastInvocations:0})]);
  assert.equal(report.exclusions.length,3);
  assert.deepEqual(report.exclusions.map(value=>value.reasons),[['TRANSPORT_FAILURE'],['DECLARED_FAILURE'],['REPORT_UNAVAILABLE']]);
  assert.equal(report.formats.TOON.correctness.eligible,0);
  assert.equal(report.formats.TOON.correctness.ratio,null);
  assert.equal(report.ledger.tokens.knownTotal,220);
  assert.equal(report.ledger.tokens.complete,false);
  assert.equal(report.ledger.providerSends.knownTotal,2);
  assert.equal(report.ledger.providerSends.complete,false);
  assert.equal(report.paired.correctness.count,0);
});

test('missing usage never improves token ratios and invalid usage remains unavailable',()=>{
  const plans=[trial('p1','TOON'),trial('p1','JSON'),trial('p2','TOON'),trial('p2','JSON')];
  const report=summarizeReplay(plans,[receipt(plans[0]),receipt(plans[1]),receipt(plans[2],'incorrect',{usage:null}),receipt(plans[3],'incorrect')]);
  assert.deepEqual(report.formats.TOON.tokensPerCorrectCompletion,{status:'PARTIAL',value:null,knownTokens:110,correct:1,missingUsage:[plans[2].trialId]});
  assert.equal(report.formats.JSON.tokensPerCorrectCompletion.value,220);
  assert.equal(report.paired.tokensBothCorrect.count,1);
  assert.equal(report.paired.tokensBothCorrect.medianDeltaJsonMinusToon,0);
});

test('paired deltas preserve each pair and latency declares measurable subsets',()=>{
  const plans=[trial('p1','TOON'),trial('p1','JSON'),trial('p2','TOON'),trial('p2','JSON')];
  const rows=[receipt(plans[0]),receipt(plans[1], 'correct',{latency:{trialMs:130,queueMs:500,providerMs:80,shellMs:12},usage:{input:100,cacheRead:0,cacheWrite:0,output:20,reasoning:0,totalTokens:120}}),receipt(plans[2],'incorrect',{latency:null}),receipt(plans[3],'incorrect')];
  const report=summarizeReplay(plans,rows);
  assert.equal(report.paired.latency.count,1);
  assert.deepEqual(report.paired.latency.deltas,[{pairId:'p1',trialMsJsonMinusToon:30,providerMsJsonMinusToon:20,shellMsJsonMinusToon:2}]);
  assert.equal(report.paired.latency.medianDeltaJsonMinusToon,30);
  assert.equal(report.paired.tokensBothCorrect.medianDeltaJsonMinusToon,10);
  assert.equal(report.paired.correctness.ties,2);
  assert.equal(report.paired.correctness.count,2);
  assert.equal(report.outcome,'INCONCLUSIVE');
});

test('zero correct results leave tokens per correct completion undefined',()=>{
  const plans=[trial('p1','TOON'),trial('p1','JSON')];
  const report=summarizeReplay(plans,plans.map(value=>receipt(value,'incorrect')));
  assert.equal(report.formats.TOON.tokensPerCorrectCompletion.status,'UNDEFINED');
  assert.equal(report.formats.TOON.tokensPerCorrectCompletion.value,null);
  assert.equal(report.ledger.providerSends.knownTotal,2);
});

test('duplicate, unknown, or changed receipt identities fail instead of hiding work',()=>{
  const plans=[trial('p1','TOON'),trial('p1','JSON')];
  assert.throws(()=>summarizeReplay(plans,[receipt(plans[0]),receipt(plans[0])]),/Duplicate/);
  assert.throws(()=>summarizeReplay(plans,[receipt({...plans[0],trialId:'unplanned'})]),/Unplanned/);
  assert.throws(()=>summarizeReplay(plans,[receipt(plans[0],'correct',{pairId:'changed'})]),/identity/);
  assert.throws(()=>summarizeReplay(plans,[receipt(plans[0],'unknown')]),/outcome/);
});

test('the declared screen accounts for six cases, twelve pairs, and every failed send',()=>{
  const plans=Array.from({length:6},(_,index)=>Array.from({length:2},(_,repeat)=>['TOON','JSON'].map(format=>({...trial(`c${index+1}-r${repeat+1}`,format,`C${index+1}`),repetition:repeat+1})))).flat(2);
  const rows=plans.map((plan,index)=>receipt(plan,index===0?'incomplete':index===1?'incorrect':'correct'));
  const before=JSON.stringify({plans,rows});
  const report=summarizeReplay(plans,rows);
  assert.equal(report.plannedTrials,24);
  assert.equal(report.plannedPairs,12);
  assert.equal(report.attemptedPairs,12);
  assert.equal(report.distinctCases,6);
  assert.equal(report.paired.correctness.count,12);
  assert.equal(report.paired.correctness.ties,12);
  assert.equal(report.formats.TOON.correctness.eligible,12);
  assert.equal(report.formats.JSON.correctness.eligible,12);
  assert.equal(report.ledger.providerSends.knownTotal,24);
  assert.equal(report.ledger.tokens.knownTotal,2640);
  assert.equal(report.ledger.tokens.complete,true);
  assert.equal(JSON.stringify({plans,rows}),before);
});

test('production grader dimensions and causes reach the aggregate independently',()=>{
  const plans=[trial('p1','TOON','C5'),trial('p1','JSON','C5')];
  const rows=[receipt(plans[0],'incorrect',{grade:{type:'GRADED_INCORRECT',correct:false,dimensions:{coverage:'FAIL',arguments:'PASS'},reasons:['WRONG_COVERAGE','WRONG_SCOPE']}}),receipt(plans[1],'incorrect',{grade:{type:'GRADED_INCORRECT',correct:false,dimensions:{coverage:'NOT_APPLICABLE',arguments:'FAIL'},reasons:['ARGUMENT_REJECTED'],productionCause:'INVALID_FIELDS'}})];
  const report=summarizeReplay(plans,rows);
  assert.deepEqual(report.formats.TOON.misuse,{WRONG_COVERAGE:1,WRONG_SCOPE:1});
  assert.deepEqual(report.formats.JSON.argumentRejections,{INVALID_FIELDS:1});
  assert.deepEqual(report.formats.TOON.coverage,{correct:0,incorrect:1,notApplicable:0,unavailable:[]});
  assert.deepEqual(report.formats.JSON.coverage,{correct:0,incorrect:0,notApplicable:1,unavailable:[]});
});
