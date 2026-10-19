import test from 'node:test';
import assert from 'node:assert/strict';
import { admitSessionPlan, finalizedUsage, usageQualification } from './agent_efficiency_session.mjs';

const plan = () => ({version:1,workspaceRoot:'/tmp/project',outputRoot:'/tmp/result',
  piPackageRoot:'/tmp/pi',piVersion:'1.3.0',authPath:'/tmp/auth.json',
  provider:'openai-codex',model:'gpt-6.1-sol',effort:'high',arm:'control',
  exposure:'codemode',mcpCommand:'/tmp/kast-mcp',onboarding:'',skills:[],
  prompt:'Find the caller.',maximumRequests:20,maximumReportedTokens:150000,
  maximumPayloadBytes:1048576,maximumOutputTokens:6000,maximumTools:40,wallSeconds:180});

test('control cannot receive Kast instructions or experimental skills', () => {
  assert.equal(admitSessionPlan(plan()).type,'admitted');
  assert.equal(admitSessionPlan({...plan(),onboarding:'Use Kast'}).type,'rejected');
  assert.equal(admitSessionPlan({...plan(),skills:['/tmp/skill']}).type,'rejected');
});
test('native exposure is finite and bounds reject nonfinite numbers', () => {
  for (const exposure of ['codemode','deferred','direct'])
    assert.equal(admitSessionPlan({...plan(),arm:'treatment',exposure}).type,'admitted');
  for (const value of [0,-1,NaN,Infinity,1.5])
    assert.equal(admitSessionPlan({...plan(),maximumRequests:value}).type,'rejected');
  assert.equal(admitSessionPlan({...plan(),exposure:'custom-proxy'}).type,'rejected');
  assert.equal(admitSessionPlan({...plan(),tools:[]}).type,'rejected');
});
test('usage counts output once, keeps cache separate, and never invents missing cost', () => {
  assert.deepEqual(finalizedUsage({input:10,output:7,cacheRead:20,cacheWrite:0,
    totalTokens:37,cost:{total:0.0004}}),
  {type:'usage',input:10,output:7,cacheRead:20,cacheWrite:0,totalTokens:37,catalogCostUsd:0.0004});
  assert.equal(finalizedUsage({input:10,output:7,cacheRead:20,cacheWrite:0,totalTokens:37}).catalogCostUsd,null);
  assert.equal(finalizedUsage({input:10,output:7,cacheRead:20,cacheWrite:0,totalTokens:44}).type,'rejected');
  assert.equal(finalizedUsage({input:-1,output:0,cacheRead:0,cacheWrite:0,totalTokens:-1}).type,'rejected');
});
test('a provider termination with synthetic zero counters cannot prove complete usage',()=>{
  const usage=finalizedUsage({input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0});
  assert.equal(usageQualification(1,[{type:'assistant_end',stopReason:'error',usage}]),'INCOMPLETE');
  assert.equal(usageQualification(1,[{type:'assistant_end',stopReason:'stop',usage}]),'FINALIZED_PROVIDER_USAGE');
  assert.equal(usageQualification(2,[{type:'assistant_end',stopReason:'stop',usage}]),'INCOMPLETE');
});
