import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { replayCase, replayCases } from './pi_evaluation_replay_cases.mjs';

const sha=text=>createHash('sha256').update(text).digest('hex');
const withoutInstructions=item=>Object.fromEntries(Object.entries(item).filter(([key])=>!['visibleContract','instructionProfile'].includes(key)));

test('instruction profiles preserve every original task and replay-state fact',()=>{
  const full=replayCases('full'),concise=replayCases('concise');
  assert.equal(full.length,6);
  assert.deepEqual(full.map(withoutInstructions),concise.map(withoutInstructions));
  assert.deepEqual(replayCases(),full);
  for(const item of full) {
    assert.equal(item.version,'interface-replay-v3');
    assert.equal(item.instructionProfile,'full');
    const other=replayCase(item.id,'concise');
    assert.equal(other.instructionProfile,'concise');
    assert.equal(item.task,other.task);
    assert.deepEqual(item.prefixCall,other.prefixCall);
    assert.equal(item.canonicalSha256,other.canonicalSha256);
    assert.equal(item.canonicalResponse,other.canonicalResponse);
    assert.deepEqual(item.permittedDecisions,other.permittedDecisions);
    assert.deepEqual(replayCase(item.id),item);
  }
});

test('full profile preserves the exact prior v2 contract',()=>{
  assert.equal(sha(replayCase('C1','full').visibleContract),'8ffe8b429cdcf548ec475ca2fcab4216dd228156a165ae5772cd715dc131caab');
});

test('each profile supplies one global contract without case answers',()=>{
  for(const profile of ['full','concise']) {
    const cases=replayCases(profile);
    assert.equal(new Set(cases.map(item=>item.visibleContract)).size,1);
    for(const item of cases) {
      assert.equal(item.visibleContract.includes(item.id),false);
      assert.doesNotMatch(item.visibleContract,/submit|OrderService|MissingOrder|services\/orders|exact:v5:|result:v1:|WRONG_TARGET|GRADED_CORRECT|permittedDecisions|expected|oracle/);
    }
  }
});

test('concise contract preserves every command family flag enum and response contract',()=>{
  const text=replayCase('C1','concise').visibleContract;
  for(const command of ['kast query --name','kast query --ref','kast source read','kast result read','kast result resume'])assert.ok(text.includes(command),command);
  for(const flag of ['--name','--in','--source-set main','--kind','--fields','--ref','--relation','--region','--text','--cursor','--continuation','--request -'])assert.ok(text.includes(flag),flag);
  for(const values of ['class|function|property|type-alias','name,location,signature,source','references|callers|callees|implementations|inheritors|overrides|type-uses','callable-body|file|anchor','complete|none'])assert.ok(text.includes(values),values);
  for(const value of ['"kind":"finish"','"conclusion":"insufficient-evidence"|"absent"|"present"','"scope":{"directory":"...","sourceSets":["..."]}','"exhaustive":true|false','"limitations":["..."]','"kind":"clarify","question":"..."'])assert.ok(text.includes(value),value);
  assert.match(text,/public JSON.*stdin.*instead of semantic flags/);
  assert.match(text,/issued qualification\.limitations codes verbatim/);
  assert.match(text,/No prose or extra evidence fields/);
  assert.match(text,/Finish or clarify only if the original task supports it/);
  assert.match(text,/one shell call.*one attempted Kast invocation/);
  assert.match(text,/References.*fixture-local/);
  assert.match(text,/Copy issued references and tokens exactly/);
  assert.match(text,/bash result belongs to the preceding call/);
});

test('unknown profiles reject instead of silently selecting full instructions',()=>{
  for(const profile of ['',null,0,{},[],'short','FULL','full ','__proto__','constructor']) {
    assert.throws(()=>replayCases(profile),/REPLAY_INSTRUCTION_PROFILE_REJECTED/);
    assert.throws(()=>replayCase('C1',profile),/REPLAY_INSTRUCTION_PROFILE_REJECTED/);
  }
});

test('concise instructions reduce UTF-8 bytes without a token or inference claim',()=>{
  const full=Buffer.byteLength(replayCase('C1','full').visibleContract);
  const concise=Buffer.byteLength(replayCase('C1','concise').visibleContract);
  assert.ok(concise>0&&concise<full);
  assert.equal(full,1316);
});
