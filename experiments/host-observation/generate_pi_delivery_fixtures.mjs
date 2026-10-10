#!/usr/bin/env node
// Invoke the actual pinned #995 shared client. Only its transport is doubled;
// no pagination, envelope construction or canonical reply production lives here.
import fs from 'node:fs';
import crypto from 'node:crypto';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const [clientFile,canonicalFile,outputFile]=process.argv.slice(2);
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const source=fs.readFileSync(clientFile,'utf8'),canonicalBytes=fs.readFileSync(canonicalFile);
assert.equal(sha(source),'681d1291a6afabe9614efbdc8d0f12095a421f0ade72fb8e18d7a54fd3b366fd');
assert.equal(sha(canonicalBytes),'868a92ac907a860b35ea08dee088f25661aa1535c011467786fc2000231705ad');
const fixture=JSON.parse(canonicalBytes);
// Same export-only exposure used by #995's production adapter test; the shared
// source bytes and owning rules are unchanged. There are no external imports.
const client=vm.runInNewContext(source+'\n({awaitQueryDelivery,queryDeliveryFailed})',{Buffer,Date,JSON,Error});
const params={request:{type:'RUN',source:{type:'ALL_DECLARATIONS'},output:{type:'SYMBOLS',fields:['NAME']},executionBudget:{maxResults:43}}};
const policy={callTimeoutMillis:100,maxResponseBytes:4194304};
const cases={};
async function produce(name,sequence,{cancelAt,loseAt,elapsedPerCall=0}={}) {
  const controller=new AbortController(),requestTypes=[];let time=0,index=0;
  const result=await client.awaitQueryDelivery(params,async arguments_=>{
    const number=index++;
    requestTypes.push(arguments_.request.type);
    if(number>0)assert.ok(['READ_RESULT','RESUME'].includes(arguments_.request.type),'No semantic resubmission');
    if(number===loseAt)throw Error('SYNTHETIC_LOST_DELIVERY_REPLY');
    assert.ok(number<sequence.length,'No excess physical RPC');
    time+=elapsedPerCall;
    if(number===cancelAt)controller.abort();
    return structuredClone(sequence[number]);
  },policy,controller.signal,()=>time);
  cases[name]={result:JSON.parse(JSON.stringify(result)),hostFailure:client.queryDeliveryFailed(result),requestTypes};
}
await produce('complete',[fixture.prefix,fixture.rows,fixture.tail]);
await produce('qualified',[fixture.outputPrefix,fixture.outputFinal]);
await produce('rejectedProof',[fixture.rejected,fixture.proof,fixture.proofTail]);
await produce('unavailable',[fixture.outputPrefix,fixture.outputUnavailable]);
await produce('expired',[fixture.prefix,fixture.expiredResult]);
await produce('budgetIncrease',[fixture.outputPrefix,fixture.outputBudget]);
await produce('readBudgetIncrease',[fixture.prefix,fixture.readBudget]);
await produce('cancelled',[fixture.prefix],{cancelAt:0});
await produce('cancelledAfterPage',[fixture.prefix,fixture.rows],{cancelAt:1});
await produce('lostDelivery',[fixture.prefix],{loseAt:1});
await produce('deadline',[fixture.prefix,fixture.rows],{elapsedPerCall:50});
// Exact deliberately oversized input control from #995's owning client test.
// The envelope is still produced by the real client, never assembled here.
const oversized=structuredClone(fixture.prefix);oversized.document.items[0].name='x'.repeat(262145);
await produce('byteLimitWithoutInitial',[oversized]);
fs.writeFileSync(outputFile,JSON.stringify({sourceRevision:'36a2e6d256d620cf37a4e7e622ee643d4c38c178',clientSha256:sha(source),canonicalSha256:sha(canonicalBytes),cases})+'\n');
