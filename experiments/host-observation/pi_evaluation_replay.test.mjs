import test from 'node:test';
import assert from 'node:assert/strict';
import { expandReplayPlan, runReplayPool, admitReplayPair, ReplayBudget } from './pi_evaluation_replay.mjs';

const plan={mode:'replay',workers:8,repetitions:2,caseIds:['C1','C2','C3','C4','C5','C6'],maximumProviderRequests:24,wallSeconds:60};
test('twelve fixed balanced pairs and one-case filter preserve the send ceiling',()=>{
 const trials=expandReplayPlan(plan);assert.equal(trials.length,24);
 assert.equal(new Set(trials.map(t=>t.pairId)).size,12);
 assert.equal(trials.filter(t=>t.armOrder===0&&t.format==='TOON').length,6);
 assert.equal(expandReplayPlan({...plan,caseIds:['C1'],maximumProviderRequests:4}).length,4);
 assert.throws(()=>expandReplayPlan({...plan,maximumProviderRequests:23}));
});
test('pool isolates inputs and permits eight in flight, with complete records on failure',async()=>{
 let active=0,maximum=0;const visited=[];
 const results=await runReplayPool(expandReplayPlan(plan),8,async trial=>{
  active++;maximum=Math.max(maximum,active);visited.push(trial.trialId);
  await new Promise(resolve=>setImmediate(resolve));active--;
  if(trial.trialId==='C1-r1-TOON')throw Error('owned failure');
  return {trialId:trial.trialId,outcome:{type:'correct',reasons:[]}};
 });
 assert.equal(maximum,8);assert.equal(results.length,24);assert.equal(new Set(visited).size,24);
 assert.equal(results.find(r=>r.trialId==='C1-r1-TOON').outcome.type,'invalid');
});
test('cancellation prevents queued starts and preserves every planned identity',async()=>{
 const abort=new AbortController();let starts=0;
 const results=await runReplayPool(expandReplayPlan(plan),2,async()=>{
  starts++;abort.abort();return {outcome:{type:'incomplete',reasons:['DEADLINE']}};
 },abort.signal);
 assert.equal(starts,1);assert.equal(results.length,24);
 assert.equal(results.filter(r=>r.outcome.type==='cancelled-before-start').length,23);
});
test('one oversize or changed arm invalidates both before sends',()=>{
 const valid={type:'projected',payloadBytes:100,presentationOccurrences:1,complete:true,encodingEquivalent:true};
 assert.equal(admitReplayPair([valid,valid],262144).type,'admitted');
 for(const bad of [{...valid,payloadBytes:262145},{...valid,complete:false},{...valid,presentationOccurrences:2},{...valid,encodingEquivalent:false}])
  assert.equal(admitReplayPair([valid,bad],262144).type,'invalid');
});
test('request and tool budgets debit before effect and cannot admit a second entry',()=>{
 const budget=new ReplayBudget();
 assert.equal(budget.provider(),true);assert.equal(budget.provider(),false);
 assert.equal(budget.shell(),true);assert.equal(budget.shell(),false);
 assert.equal(budget.invocation(),true);assert.equal(budget.invocation(),false);
 assert.deepEqual(budget.report(),{providerSends:1,shellCalls:1,kastInvocations:2,blockedProviderSends:1,blockedShellCalls:1});
});

test('dry replay planning needs no SDK installation credentials or output directory',async()=>{
 const fs=await import('node:fs'),os=await import('node:os'),path=await import('node:path');
 const {spawnSync}=await import('node:child_process');
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'replay-plan-proof-'));
 const fake={...plan,piPackageRoot:'/not-installed/sdk',piVersion:'1.4.0',authPath:'/not-read/auth',modelsStorePath:'/not-read/models',adapterManifest:'/not-read/adapter',adapterManifestSha256:'a'.repeat(64),outputRoot:path.join(root,'not-created'),sourceRevision:'b'.repeat(40),diffSha256:'c'.repeat(64),sdkIdentity:[{file:'package.json',sha256:'d'.repeat(64)}],cases:Array.from({length:6},(_,i)=>({id:`C${i+1}`})),model:{provider:'openai-codex',id:'gpt-6.1-sol',effort:'high'}};
 const file=path.join(root,'plan.json');fs.writeFileSync(file,JSON.stringify(fake));
 try{
  const result=spawnSync(process.execPath,['experiments/host-observation/run_pi_evaluation.mjs','--plan',file],{encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);const expanded=JSON.parse(result.stdout);
  assert.equal(expanded.mode,'NO_INFERENCE');assert.equal(expanded.trials.length,24);assert.equal(fs.existsSync(fake.outputRoot),false);
 }finally{fs.rmSync(root,{recursive:true});}
});
