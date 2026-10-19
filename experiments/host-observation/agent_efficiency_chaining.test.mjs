import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const brief = fs.readFileSync(new URL('./agent-efficiency-configs/reference-chain-onboarding.md',import.meta.url),'utf8');
const code = brief.match(/```javascript\n(\/\/ reference-chain-example[\s\S]*?)\n```/)[1];
const run = new (Object.getPrototypeOf(async function(){}).constructor)('tools','text',code);
const seed = {type:'exact-symbol',ref:'issued-exact-ref',name:'ExampleType',
  signature:{qualifiedIdentity:'com.example.ExampleType'},location:{file:'/src/Example.kt'}};
const document = items => ({isError:false,structuredContent:{status:'complete',coverage:{exhaustive:true},
  invocation:{stop:{type:'COMPLETED'},preview:{type:'INLINE',row_count:items.length}},
  evidence_window:{type:'FINAL'},items,live:{root:'/src'},relation_observations:[{subject:{token:seed.ref},relation:'references',coverage:{type:'EXHAUSTED'}}]}});
const occurrence = {type:'reference-occurrence',occurrence:{target:{selector:seed.ref},
  occurrence:{file:'/src/Use.kt',range:{startInclusive:10,endExclusive:21}},context:'TYPE',
  provenance:'k2-authored-source',coverage:'exact-compiler-confirmed'}};
async function exercise(replies) {
  const calls=[],emissions=[];
  await run({mcp__kast__query_symbols:async value=>{
    calls.push(value);
    assert.ok(replies.length,'Unexpected native call');
    return replies.shift();
  }},value=>emissions.push(value));
  assert.equal(replies.length,0,'Unconsumed native result');
  return {calls,emissions};
}
test('the authored recipe emits terminal evidence once and reuses the issued seed',async()=>{
  const {calls,emissions}=await exercise([document([seed]),document([occurrence])]);
  assert.equal(calls.length,2);
  assert.deepEqual(calls[1].request.source,{type:'SYMBOL_REFS',symbolRefs:['issued-exact-ref']});
  assert.equal(emissions.length,1);
  assert.equal(emissions[0].stage,'REFERENCES');
  assert.deepEqual(emissions[0].occurrences,[{file:'/src/Use.kt',range:{startInclusive:10,endExclusive:21},
    context:'TYPE',provenance:'k2-authored-source',coverage:'exact-compiler-confirmed'}]);
  assert.equal(JSON.stringify(emissions).includes(seed.ref),false);
  assert.deepEqual(emissions[0].relation_observations,[{relation:'references',coverage:{type:'EXHAUSTED'}}]);
  assert.equal(emissions[0].target,'com.example.ExampleType');
});
for(const [name,items] of [['absent',[]],['ambiguous',[seed,seed]],['different identity',[{...seed,signature:{qualifiedIdentity:'other.ExampleType'}}]]]) {
  test(`the recipe stops before expansion for ${name} seeds`,async()=>{
    const {calls,emissions}=await exercise([document(items)]);
    assert.equal(calls.length,1);
    assert.equal(emissions[0].stage,'SELECTION_STOP');
    assert.equal('occurrences' in emissions[0],false);
  });
}
for(const [name,change] of [
  ['stale rejection',r=>{r.structuredContent.status='rejected';r.structuredContent.failure='FRESHNESS_REJECTED';}],
  ['incomplete coverage',r=>{r.structuredContent.coverage.exhaustive=false;}],
  ['paged preview',r=>{r.structuredContent.invocation.preview.type='PAGED';}],
  ['missing rows',r=>{r.structuredContent.invocation.preview.row_count=2;}],
  ['MCP error',r=>{r.isError=true;}],
]) {
  test(`the recipe preserves a terminal stop for ${name}`,async()=>{
    const end=document([occurrence]);change(end);
    const {emissions}=await exercise([document([seed]),end]);
    assert.equal(emissions.length,1);
    assert.equal(emissions[0].stage,'REFERENCES_STOP');
    assert.equal('occurrences' in emissions[0],false);
    assert.deepEqual(emissions[0].coverage,end.structuredContent.coverage);
  });
}
test('the recipe rejects a result for another seed',async()=>{
  const wrong=structuredClone(occurrence);wrong.occurrence.target.selector='other-ref';
  const {emissions}=await exercise([document([seed]),document([wrong])]);
  assert.deepEqual(emissions,[{stage:'SHAPE_STOP',status:'complete'}]);
});
