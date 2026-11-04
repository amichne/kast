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
