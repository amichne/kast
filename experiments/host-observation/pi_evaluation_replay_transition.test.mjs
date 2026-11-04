import test from 'node:test';
import assert from 'node:assert/strict';
import { replayTransition } from './pi_evaluation_replay_cases.mjs';
import { replayOracleControls } from './pi_evaluation_replay_grading.mjs';

test('fixture transitions reject a directory scope that excludes descendants',()=>{
 for(const caseId of ['C3','C6'])for(const value of [false,true,null,undefined]){
  const admission=structuredClone(replayOracleControls(caseId).accept[0].admission);
  if(value!==undefined)admission.request.source.scope.includeSubdirectories=value;
  assert.equal(replayTransition(caseId,admission).type,value===false?'unsupported':'supported',`${caseId}:${value}`);
 }
});

test('fixture transitions reject excluded class targets and a narrowed repair query',()=>{
 const variants=[
  [undefined,'supported','supported'],
  [null,'supported','supported'],
  [['CLASS'],'supported','unsupported'],
  [['CLASS','FUNCTION'],'supported','unsupported'],
  [['FUNCTION'],'unsupported','unsupported'],
  [['PROPERTY','TYPE_ALIAS'],'unsupported','unsupported'],
  [['CLASS','FUNCTION','PROPERTY','TYPE_ALIAS'],'supported','supported'],
  [['TYPE_ALIAS','PROPERTY','FUNCTION','CLASS'],'supported','supported'],
 ];
 for(const [kinds,c3,c6]of variants)for(const caseId of ['C3','C6']){
  const admission=structuredClone(replayOracleControls(caseId).accept[0].admission);
  if(kinds!==undefined)admission.request.source.declarationKinds=kinds;
  assert.equal(replayTransition(caseId,admission).type,caseId==='C3'?c3:c6,`${caseId}:${JSON.stringify(kinds)}`);
 }
});
