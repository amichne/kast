import test from 'node:test';
import assert from 'node:assert/strict';
import * as worker from './pi_evaluation_replay_worker.mjs';
import { gradeReplayDecision } from './pi_evaluation_replay_grading.mjs';

test('model-produced invalid bytes stay unsuccessful and output exhaustion stays incomplete',()=>{
 for(const reason of ['STDIN_INVALID_UTF8','DRIVER_REQUEST_INVALID_UTF8','DRIVER_REQUEST_REJECTED','SHELL_STDOUT_INVALID_UTF8','SHELL_STDERR_INVALID_UTF8']){
  assert.deepEqual(worker.replayShellFailureOutcome({reason}),{type:'incorrect',reasons:[reason]});
 }
 for(const reason of ['STDIN_BUDGET','DRIVER_REQUEST_TOO_LARGE','SHELL_OUTPUT_BUDGET'])assert.deepEqual(worker.replayShellFailureOutcome({reason}),{type:'incomplete',reasons:[reason]});
 for(const failure of [null,{reason:'UNRECOGNIZED'}])assert.deepEqual(worker.replayShellFailureOutcome(failure),{type:'invalid',reasons:['SHELL_ADAPTER_FAILURE']});
});

test('driver input rejection never claims a production parser diagnostic',()=>{
 const grade=gradeReplayDecision('C6',{kind:'invoke'},{type:'DRIVER_INPUT_REJECTED',cause:'STDIN_INVALID_UTF8'});
 assert.equal(grade.correct,false);assert.equal(grade.dimensions.arguments,'FAIL');
 assert.equal(grade.productionCause,undefined);
});
