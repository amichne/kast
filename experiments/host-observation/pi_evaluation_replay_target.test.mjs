import { fileURLToPath } from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';
import * as accounting from './pi_evaluation_replay_accounting.mjs';

const hash=character=>character.repeat(64);
const target={factor:3,dimensions:'TOKENS_AND_LATENCY',caseIds:['C5','C6'],repetitions:2};
const rows=(tokens,millis)=>target.caseIds.flatMap(caseId=>[1,2].map(repetition=>({
 trialId:`${caseId}-r${repetition}-JSON`,caseId,repetition,format:'JSON',outcome:{type:'correct',reasons:[]},
 measurement:'LIVE_MODEL',protectedFilesUnchanged:true,providerSends:1,nativeCalls:0,shellCalls:caseId==='C5'?0:1,kastInvocations:caseId==='C5'?0:1,
 usage:{input:tokens-10,output:10,cacheRead:0,cacheWrite:0,totalTokens:tokens},
 latency:{trialMs:millis,queueMs:0,providerMs:millis-1,shellMs:0},
 grade:{correct:true,misuse:[],dimensions:{coverage:caseId==='C5'?'PASS':'NOT_APPLICABLE'}},
 identity:{provider:'openai-codex',model:'gpt-6.1-sol',effort:'high',piVersion:'1.4.0',fixtureVersion:'interface-replay-v1',
  canonicalSha256:caseId==='C5'?hash('a'):hash('b'),promptSha256:hash('c'),contractSha256:hash('d'),graderSha256:hash('e'),
  sdkIdentity:[{file:'package.json',sha256:hash('f')}],budgetSha256:hash('0'),timingMethod:'CONTROLLER_REAPED_WALL_V1',credentialSource:'CODEX_CHATGPT',principalSha256:hash('2')},
 projection:{type:'projected',complete:true,encodingEquivalent:true,presentationOccurrences:1,payloadSha256:hash('1')},
 actualProviderProjection:{type:'projected',complete:true,presentationOccurrences:1,payloadSha256:hash('1')},
})));
const assess=(baseline,candidate,extra={})=>{
 assert.equal(typeof accounting.assessReplayTarget,'function','The existing accounting owner needs an explicit performance target gate');
 return accounting.assessReplayTarget({target,...extra,baseline,candidate});
};

test('threefold acceptance needs both cost and speed without lost task correctness',()=>{
 const baseline=rows(900,900),candidate=rows(300,300),before=JSON.stringify({baseline,candidate});
 const result=assess(baseline,candidate);
 assert.equal(result.status,'TARGET_MET');assert.equal(result.tokens.speedup,3);assert.equal(result.latency.speedup,3);
 assert.equal(result.cases,2);assert.equal(result.pairs.length,4);assert.equal(result.quality.noAccuracyLoss,true);
 assert.equal(JSON.stringify({baseline,candidate}),before);
});
test('a faster wrong target cannot count as an efficiency improvement',()=>{
 const baseline=rows(900,900),candidate=rows(100,100);
 candidate[0]={...candidate[0],outcome:{type:'incorrect',reasons:['WRONG_TARGET']},grade:{correct:false,misuse:['WRONG_TARGET']}};
 const result=assess(baseline,candidate);
 assert.equal(result.status,'NOT_MET');assert.equal(result.quality.noAccuracyLoss,false);assert.ok(result.reasons.includes('ACCURACY_REGRESSION'));
 assert.equal(result.tokens.candidate.correct,3);assert.equal(result.tokens.candidate.total,400);
});
test('all eligible failed work counts in cost per correct decision',()=>{
 const baseline=rows(900,900),candidate=rows(300,300);
 for(const records of [baseline,candidate])records[0]={...records[0],outcome:{type:'incomplete',reasons:['OUTPUT_BUDGET']},grade:null};
 const result=assess(baseline,candidate);
 assert.equal(result.tokens.baseline.total,3600);assert.equal(result.tokens.candidate.total,1200);
 assert.equal(result.tokens.baseline.perCorrect,1200);assert.equal(result.tokens.candidate.perCorrect,400);
 assert.equal(result.quality.baseline.eligible,4);assert.equal(result.quality.candidate.eligible,4);
});
test('missing or invalid arms cannot shrink the declared workload',()=>{
 const baseline=rows(900,900),candidate=rows(300,300);
 assert.equal(assess(baseline,candidate.slice(1)).status,'UNMEASURED');
 candidate[0]={...candidate[0],outcome:{type:'invalid',reasons:['TRANSPORT_FAILURE']},usage:null};
 const result=assess(baseline,candidate);assert.equal(result.status,'UNMEASURED');
 assert.ok(result.reasons.includes('INVALID_TRIAL'));assert.equal(result.accounting.candidate.sends,4);
});
test('unknown usage and zero correct decisions cannot create a ratio',()=>{
 const baseline=rows(900,900),candidate=rows(300,300);candidate[1].usage=null;
 const result=assess(baseline,candidate);assert.equal(result.status,'UNMEASURED');assert.equal(result.tokens.speedup,null);
 assert.deepEqual(result.tokens.candidate.missingUsage,[candidate[1].trialId]);
 for(const row of baseline){row.outcome={type:'incorrect',reasons:['WRONG_ACTION']};row.grade={correct:false,misuse:['WRONG_ACTION']};}
 assert.equal(assess(baseline,rows(300,300)).tokens.speedup,null);
});
test('workload model effort budget grader and SDK mismatches reject comparison',()=>{
 for(const key of ['canonicalSha256','promptSha256','contractSha256','graderSha256','budgetSha256','model','effort','sdkIdentity','timingMethod','credentialSource','principalSha256']){
  const candidate=rows(300,300);candidate[0].identity[key]=key==='sdkIdentity'?[]:'different';
  const result=assess(rows(900,900),candidate);assert.equal(result.status,'INVALID_COMPARISON',key);assert.ok(result.reasons.includes('IDENTITY_MISMATCH'),key);
 }
 const duplicate=rows(300,300);duplicate[1]=duplicate[0];assert.equal(assess(rows(900,900),duplicate).status,'INVALID_COMPARISON');
});
test('synthetic transport or missing payload evidence supplies no model performance proof',()=>{
 for(const key of ['measurement','actualProviderProjection']){
  const candidate=rows(300,300);candidate[0][key]=key==='measurement'?'SYNTHETIC_TRANSPORT':null;
  const result=assess(rows(900,900),candidate);assert.equal(result.status,'UNMEASURED');assert.equal(result.tokens.speedup,null);assert.equal(result.latency.speedup,null);
 }
});
test('repetitions do not increase the number of independent cases or hide slow cases',()=>{
 const baseline=rows(900,900),candidate=rows(300,300);
 candidate[0].latency.trialMs=900;candidate[1].latency.trialMs=900;
 const result=assess(baseline,candidate);
 assert.equal(result.cases,2);assert.equal(result.latency.caseRatios.length,2);
 assert.equal(result.latency.speedup,2);assert.equal(result.status,'NOT_MET');
});
test('a known token saving is not a complete performance or billed-cost claim',()=>{
 const candidate=rows(300,900);const result=assess(rows(900,900),candidate);
 assert.equal(result.tokens.speedup,3);assert.equal(result.latency.speedup,1);assert.equal(result.status,'NOT_MET');
 assert.equal(result.billedCost.status,'UNAVAILABLE');assert.equal(result.costUnit,'PROVIDER_TOKENS');
 assert.equal(assess(rows(900,900),candidate,{target:{...target,dimensions:'TOKENS'}}).status,'TARGET_MET');
});

test('malformed records unknown outcomes and contradictory grades fail closed',()=>{
 for(const row of [null,{}, {...rows(300,300)[0],outcome:{type:'unexpected',reasons:[]}}]){
  const candidate=rows(300,300);candidate[0]=row;assert.equal(assess(rows(900,900),candidate).status,'INVALID_COMPARISON');
 }
 const candidate=rows(300,300);candidate[0].grade.correct=false;
 assert.equal(assess(rows(900,900),candidate).status,'INVALID_COMPARISON');
});

test('the existing CLI exposes a machine-readable target assessment without model effects',async()=>{
 const fs=await import('node:fs'),os=await import('node:os'),path=await import('node:path');
 const {spawnSync}=await import('node:child_process');
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'kast-target-cli-'));const input=path.join(root,'target.json');
 const baseline=rows(900,900),candidate=rows(300,300);candidate[0].measurement='SYNTHETIC_TRANSPORT';
 fs.writeFileSync(input,JSON.stringify({target,baseline,candidate}));
 try{
  const result=spawnSync(process.execPath,[fileURLToPath(new URL('run_pi_evaluation.mjs',import.meta.url)),'--assess-target',input],{encoding:'utf8',env:{PATH:process.env.PATH,HOME:root}});
  assert.equal(result.status,2,result.stderr);const report=JSON.parse(result.stdout);
  assert.equal(report.status,'UNMEASURED');assert.ok(report.reasons.includes('LIVE_MEASUREMENT_UNAVAILABLE'));
 }finally{fs.rmSync(root,{recursive:true});}
});

test('excess or unavailable effects cannot support a goal claim',()=>{
 for(const [field,value]of [['providerSends',2],['nativeCalls',1],['shellCalls',2],['kastInvocations',null]]){
  const candidate=rows(300,300);candidate[0][field]=value;
  const result=assess(rows(900,900),candidate);assert.equal(result.status,'UNMEASURED');assert.ok(result.reasons.includes('EFFECT_COUNTS_UNAVAILABLE'));
 }
});
