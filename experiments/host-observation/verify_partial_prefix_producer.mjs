// Required cross-runtime fixture check, launched by PartialPrefixProducerTest.
// Inputs are actual retained-page and canonical projection output, not live authority.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { CasePolicy,decodeEnvelope } from './pi_evaluation_policy.mjs';
import { admittedQueryDocument } from './pi_evaluation_question.mjs';
import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
const cases=JSON.parse(readFileSync(process.argv[2],'utf8'));
const awaitDelivery=runInNewContext(readFileSync(new URL('../../cli/src/main/js/query-delivery.mjs',import.meta.url),'utf8')+';awaitQueryDelivery',{Buffer});
const config={name:'producer-prefix',expectedSemanticCalls:1,evidenceOnlyDelivery:true,inputTokenCeiling:20000,declarationByteCeiling:80000,
  work:{reportedTokens:30000,requests:2,tools:2,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576};
const pin={provider:'openai-codex',model:'gpt-6.1-sol',thinking:'high',payload:inspectProviderPayload({model:'gpt-6.1-sol',reasoning:{effort:'high'},tools:[],input:[],instructions:'fixture',max_output_tokens:2000},4194304)};
function receive(policy,canonical,saved) {
  const decoded=decodeEnvelope([{type:'text',text:canonical}]);
  return saved?policy.restoreReceivedResult(decoded,Buffer.byteLength(canonical)):policy.toolResult(decoded,Buffer.byteLength(canonical));
}
function denyMutation(result,mutate) {
  const changed=structuredClone(result);mutate(changed);
  assert.ok([changed.initial,...changed.pages].every(reply=>admittedQueryDocument(reply.document)));
  const canonical=JSON.stringify(changed);
  for(const saved of [false,true]) {
    const policy=new CasePolicy(config);
    assert.equal(receive(policy,canonical,saved).allow,false);
    assert.equal(policy.report().nativeOutcome,'REJECTED');
    assert.equal(policy.report().partialEvidence,null);
    assert.equal(policy.beforeProvider(pin).allow,false);
    assert.equal(policy.report().providerRequests,0);
    assert.equal(JSON.stringify(changed),canonical);
  }
}
for(const fixture of cases) {
  const initial={type:'rejected_document',document:JSON.parse(fixture.initial)},pages=fixture.pages.map(document=>({type:'qualified',document:JSON.parse(document)}));
  assert.ok([initial,...pages].every(reply=>admittedQueryDocument(reply.document)),fixture.name);
  const allRows=pages.flatMap(page=>page.document.items);
  assert.equal(allRows.length,fixture.rowCount);
  assert.equal(new Set(allRows.map(row=>row.row_id??row.ref)).size,fixture.rowCount);
  const replies=[initial,...pages],calls=[];
  const result=await awaitDelivery({request:{type:'RUN',output:initial.document.rejection.detail.evidence.nextQuery.request.output}},async request=>{
    calls.push(structuredClone(request));assert.ok(replies.length,'No unexpected call');return replies.shift();
  },{callTimeoutMillis:1000},undefined,()=>0);
  assert.equal(result.delivery.stop,'BYTE_LIMIT',fixture.name);
  assert.ok(result.pages.length>0&&result.pages.length<pages.length,fixture.name);
  assert.equal(calls.filter(call=>call.request.type==='RUN').length,1);
  assert.ok(calls.slice(1).every(call=>call.request.type==='READ_RESULT'));
  const canonical=JSON.stringify(result);
  for(const saved of [false,true]) {
    const policy=new CasePolicy(config);
    assert.equal(receive(policy,canonical,saved).allow,true,fixture.name);
    assert.equal(policy.report().nativeOutcome,'REJECTED');assert.equal(policy.report().exhaustiveEvidence,false);
    assert.equal(policy.beforeProvider(pin).allow,true);
    policy.modelUsage({input:100,output:50,totalTokens:150});
    assert.equal(policy.beforeProvider(pin).allow,false);
    assert.equal(policy.report().providerRequests,1);
    assert.equal(JSON.stringify(result),canonical);
    for(const type of ['RUN','READ_RESULT','RESUME']) {
      const stoppedTool=new CasePolicy(config);
      assert.equal(receive(stoppedTool,canonical,saved).allow,true);
      assert.equal(stoppedTool.toolCall('query_symbols',{request:{type}}).allow,false);
      assert.equal(stoppedTool.report().providerRequests,0);
    }
    const falsePolicy=new CasePolicy({...config,evidenceOnlyDelivery:false});
    assert.equal(receive(falsePolicy,canonical,saved).allow,false);
    assert.equal(falsePolicy.beforeProvider(pin).allow,false);
    assert.equal(falsePolicy.report().nativeOutcome,'REJECTED');
    assert.equal(falsePolicy.report().providerRequests,0);
  }
  denyMutation(result,e=>e.initial.document.next_action='restart_read');
  denyMutation(result,e=>{
    const cause={type:'ITEM_FAILURE'};e.initial.document.rejection.detail.cause=cause;
    for(const page of e.pages)page.document.interpretation.cause=structuredClone(cause);
  });
  denyMutation(result,e=>{
    for(const page of e.pages)page.document.impact_accounting={type:'EVIDENCE_ONLY',pagePathCount:999};
  });
  for(const limitation of ['STATIC_MODEL_UNPROVEN','RETENTION_LIMIT_REACHED'])denyMutation(result,e=>{
    const original=e.initial.document.rejection.detail.originalCoverage;original.limitations.push(limitation);
    for(const page of e.pages) {
      page.document.interpretation.originalCoverage=structuredClone(original);
      if(limitation==='RETENTION_LIMIT_REACHED')page.document.qualification.limitations.push('retention-limit-reached');
    }
  });
  const proof=initial.document.rejection.detail.evidence,last=result.pages.at(-1).document;
  assert.equal(proof.seedQuery!=null,fixture.name.startsWith('exact-symbol'));
  if(fixture.name.endsWith('evidence')) {
    assert.equal(last.next_cursor??null,null);assert.equal(last.evidence_window.type,'MORE');
    assert.ok(!last.qualification.limitations.includes('result-limit-reached'));
    assert.equal(result.pages.reduce((n,p)=>n+p.document.items.length,0),fixture.rowCount);
    for(const minimum of [fixture.rowCount-1,fixture.rowCount+1])denyMutation(result,e=>{
      e.initial.document.rejection.detail.originalCoverage.knownMinimum=minimum;
      for(const page of e.pages) {
        page.document.interpretation.originalCoverage.knownMinimum=minimum;
        page.document.qualification.knownMinimum=minimum;
      }
    });
    if(fixture.name==='location-occurrence-evidence') {
      assert.equal(new Set(allRows.map(row=>row.ref)).size,1,'Distinct occurrences share one target');
      const legacy=structuredClone(result);
      for(const row of legacy.initial.document.rejection.detail.evidence.preview)row.row_id=null;
      for(const page of legacy.pages)for(const row of page.document.items)row.row_id=null;
      for(const saved of [false,true]) {
        const policy=new CasePolicy(config);
        assert.equal(receive(policy,JSON.stringify(legacy),saved).allow,true);
        assert.equal(policy.report().partialEvidence.receivedRows,fixture.rowCount);
      }
    }
  } else assert.ok(last.next_cursor!=null);
  console.log(JSON.stringify({case:fixture.name,pages:result.pages.length,rows:result.pages.reduce((n,p)=>n+p.document.items.length,0),
    bytes:Buffer.byteLength(canonical),rpcCalls:calls.length,semanticExecutions:0,providerSends:0}));
}
