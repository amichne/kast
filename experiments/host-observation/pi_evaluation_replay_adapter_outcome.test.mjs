import test from 'node:test';
import assert from 'node:assert/strict';
import { replayAdapterOutcome } from './pi_evaluation_replay_worker.mjs';

test('model-triggered admission output exhaustion stays unsuccessful in the denominator',()=>{
 const failure={trigger:'MODEL_ADMISSION',failure:{operation:'ADMIT',reason:'ADAPTER_REJECTED',cause:'RESPONSE_TOO_LARGE'}};
 assert.deepEqual(replayAdapterOutcome(failure),{type:'incomplete',reasons:['ADAPTER_OUTPUT_BUDGET']});
 for(const change of [{trigger:'FIXTURE_RENDER'},{failure:{...failure.failure,operation:'RENDER'}},{failure:{...failure.failure,cause:'INPUT_IO'}},{failure:{...failure.failure,reason:'RESPONSE_TIMEOUT'}},{failure:null}]){
  assert.deepEqual(replayAdapterOutcome({...failure,...change}),{type:'invalid',reasons:['ADAPTER_SESSION_UNAVAILABLE']});
 }
 assert.deepEqual(replayAdapterOutcome(null),{type:'invalid',reasons:['ADAPTER_SESSION_UNAVAILABLE']});
});
