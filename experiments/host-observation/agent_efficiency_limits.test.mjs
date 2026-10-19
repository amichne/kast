import test from 'node:test';
import assert from 'node:assert/strict';
import { admitProviderRequest } from './agent_efficiency_limits.mjs';

const plan={maximumRequests:2,maximumReportedTokens:100,maximumPayloadBytes:1000,maximumOutputTokens:30};
function effects() {
  const events=[];
  return {events,record:value=>events.push(value),stop:reason=>events.push({stop:reason}),abort:()=>events.push({abort:true})};
}
test('accepted serialized request carries its exact bytes and output cap',()=>{
  const observed=effects();
  const result=admitProviderRequest({model:'pinned'},{requests:1,totalTokens:20},plan,observed);
  assert.equal(result.type,'admitted');
  assert.deepEqual(result.payload,{model:'pinned',max_output_tokens:30});
  assert.equal(result.bytes,Buffer.byteLength('{"model":"pinned","max_output_tokens":30}'));
  assert.equal(observed.events.length,1);
  assert.equal(observed.events[0].request,2);
});
test('request, token, and payload denials abort rather than depend on swallowed exceptions',()=>{
  for(const [state,limits] of [
    [{requests:2,totalTokens:20},plan],
    [{requests:1,totalTokens:100},plan],
    [{requests:1,totalTokens:20},{...plan,maximumPayloadBytes:1}],
  ]) {
    const observed=effects();
    const result=admitProviderRequest({model:'pinned'},state,limits,observed);
    assert.equal(result.type,'rejected');
    assert.deepEqual(observed.events[0],{stop:'RESOURCE_LIMIT'});
    assert.equal(observed.events[1].type,'provider_denied');
    assert.deepEqual(observed.events[2],{abort:true});
    assert.ok(!observed.events.some(value=>value.type==='provider_request'));
  }
});
test('a failed journal stops provider admission',()=>{
  const observed=effects();
  const result=admitProviderRequest({}, {requests:0,totalTokens:0},plan,
    {...observed,record:()=>{throw Error('controlled journal failure');}});
  assert.equal(result.type,'rejected');
  assert.deepEqual(observed.events,[{stop:'OBSERVATION_FAILED'},{abort:true}]);
});
test('a failed denial record still aborts and returns finite failure',()=>{
  const observed=effects();
  const result=admitProviderRequest({}, {requests:2,totalTokens:0},plan,
    {...observed,record:()=>{throw Error('controlled journal failure');}});
  assert.equal(result.type,'rejected');
  assert.deepEqual(observed.events,[{stop:'RESOURCE_LIMIT'},{stop:'OBSERVATION_FAILED'},{abort:true}]);
});
