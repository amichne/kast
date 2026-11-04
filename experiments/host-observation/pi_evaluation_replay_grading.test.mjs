import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { replayCases, replayCase, heldOutManifest, replayTransition } from './pi_evaluation_replay_cases.mjs';
import { gradeReplayDecision, replayOracleControls } from './pi_evaluation_replay_grading.mjs';

test('production admission preserves planning and workspace independently of request validity',()=>{
 const request=replayOracleControls('C3').accept[0].admission.request;
 const receipt={type:'ADMITTED',arguments:{request},invocation:{operation:'EXECUTE',workspace:{type:'fixture',root:'/fixture',selection:null}}};
 assert.equal(gradeReplayDecision('C3',{kind:'invoke'},receipt).correct,true);
 const planned=gradeReplayDecision('C3',{kind:'invoke'},{...receipt,invocation:{...receipt.invocation,operation:'PLAN'}});
 assert.equal(planned.dimensions.arguments,'PASS');assert.equal(planned.dimensions.target,'PASS');assert.equal(planned.dimensions.action,'FAIL');
 const foreign=gradeReplayDecision('C3',{kind:'invoke'},{...receipt,invocation:{...receipt.invocation,workspace:{type:'foreign',selection:'/another-worktree',resolved:'/another-worktree',root:'/fixture'}}});
 assert.equal(foreign.dimensions.arguments,'PASS');assert.equal(foreign.dimensions.target,'PASS');assert.equal(foreign.dimensions.scope,'FAIL');
 const missing=gradeReplayDecision('C3',{kind:'invoke'},{type:'ADMITTED',arguments:{request}});
 assert.equal(missing.correct,false);
 for(const invalid of [{...receipt,invocation:{...receipt.invocation,operation:'PLAN'}},{...receipt,invocation:{...receipt.invocation,workspace:{type:'foreign'}}},{type:'ADMITTED',arguments:{request}}])assert.equal(replayTransition('C3',invalid).type,'unsupported');
});

test('six case oracles accept independent controls and reject named misuses',()=>{
  assert.equal(replayCases().length,6);
  for(const item of replayCases()) {
    const controls=replayOracleControls(item.id);
    for(const positive of controls.accept) assert.equal(gradeReplayDecision(item.id,positive.decision,positive.admission).correct,true,`${item.id}: ${positive.name}`);
    for(const negative of controls.reject) {
      const grade=gradeReplayDecision(item.id,negative.decision,negative.admission);
      assert.equal(grade.correct,false,`${item.id}: ${negative.name}`);
      assert.ok(grade.reasons.includes(negative.reason),`${item.id}: ${negative.name}: ${grade.reasons}`);
    }
  }
});

test('admitted syntax remains distinct from target scope reference and coverage',()=>{
  const dimensions=new Set();
  for(const item of replayCases())for(const negative of replayOracleControls(item.id).reject) {
    const grade=gradeReplayDecision(item.id,negative.decision,negative.admission);
    if(negative.admission?.type==='admitted')assert.equal(grade.dimensions.arguments,'PASS');
    for(const [dimension,result] of Object.entries(grade.dimensions))if(result==='FAIL')dimensions.add(dimension);
  }
  for(const dimension of ['target','scope','reference','coverage'])assert.ok(dimensions.has(dimension),dimension);
});

test('case preparation keeps oracle controls out of visible records and pins raw bytes',()=>{
  for(const item of replayCases()) {
    const json=JSON.stringify(item);
    assert.equal(json.includes('expected'),false);
    assert.equal(json.includes('oracle'),false);
    assert.match(item.canonicalSha256,/^[a-f0-9]{64}$/);
    assert.equal(typeof item.canonicalResponse,'string');
    assert.equal(replayCase(item.id).canonicalSha256,item.canonicalSha256);
    assert.deepEqual([...item.permittedDecisions],item.id==='C5'?['finish']:['invoke']);
  }
  assert.equal(heldOutManifest().length,4);
  assert.deepEqual(heldOutManifest().map(item=>item.factor),['different-overloads','larger-table','nested-qualified','source-escaping']);
});

test('malformed decisions and unsupported clarification fail as model outcomes',()=>{
  for(const item of replayCases()) {
    assert.equal(gradeReplayDecision(item.id,null).correct,false);
    assert.ok(gradeReplayDecision(item.id,{kind:'clarify',question:'Which one?'}).reasons.includes('WRONG_ACTION'));
    assert.equal(gradeReplayDecision(item.id,{kind:'invoke'},{type:'rejected',cause:'INVALID_FIELDS'}).dimensions.arguments,'FAIL');
  }
});

test('synthetic references map to independent source annotations rather than row position',()=>{
  const root=new URL('./interface-replay/fixtures/',import.meta.url);
  const annotations=JSON.parse(readFileSync(new URL('annotations.json',root),'utf8'));
  const source=readFileSync(new URL('Orders.kt',root),'utf8').split('\n');
  assert.equal(annotations.nativeAuthority,false);
  for(const item of annotations.declarations) {
    assert.match(item.reference,/^exact:v5:[A-Za-z0-9_-]{22}$/);
    const line=source[item.line-1];
    const parameterTypes=[...line.matchAll(/: (String|Long|Int)/g)].slice(0,-1).map(match=>match[1]);
    assert.equal(`submit(${parameterTypes.join(',')})`,item.signature);
    const rows=JSON.parse(replayCase('C1').canonicalResponse).items;
    const row=rows.find(value=>value.ref===item.reference);
    assert.deepEqual(row.signature.valueParameters,parameterTypes.map(value=>`kotlin.${value}`));
    assert.equal(gradeReplayDecision('C1',{kind:'invoke'},replayOracleControls('C1').accept[0].admission).correct,true);
    assert.equal(rows.toReversed().find(value=>value.ref===item.reference).ref,item.reference);
  }
});

test('held-out significant source and integer bytes survive fixture loading without numeric conversion',()=>{
  const heldout=heldOutManifest();
  for(const id of ['H3','H4']) {
    const bytes=readFileSync(heldout.find(item=>item.id===id).canonicalPath,'utf8');
    assert.ok(bytes.includes('9223372036854775807'));
    assert.equal(bytes.includes('9223372036854776000'),false);
  }
  const source=readFileSync(heldout.find(item=>item.id==='H4').canonicalPath,'utf8');
  assert.ok(source.includes('✓'));
  assert.ok(source.includes('\\n'));
  assert.ok(source.includes('\\\\t'));
});

test('production-admitted null defaults preserve their documented default meaning',()=>{
  for(const caseId of ['C3','C6']) {
    const receipt=structuredClone(replayOracleControls(caseId).accept[0].admission);
    receipt.request.source.nameMatch=null;
    receipt.request.steps=null;
    if(caseId==='C3')receipt.request.terminalStep.output=null;
    assert.equal(gradeReplayDecision(caseId,{kind:'invoke'},receipt).correct,true);
    receipt.request.source.nameMatch='FUZZY';
    assert.ok(gradeReplayDecision(caseId,{kind:'invoke'},receipt).reasons.includes('WRONG_TARGET'));
  }
  const receipt=structuredClone(replayOracleControls('C4').accept[0].admission);
  receipt.request.evidence_cursor=null;
  assert.equal(gradeReplayDecision('C4',{kind:'invoke'},receipt).correct,true);
  receipt.request.evidence_cursor=2;
  assert.ok(gradeReplayDecision('C4',{kind:'invoke'},receipt).reasons.includes('WRONG_REFERENCE'));
});

test('declared fixture transitions preserve supported requests and reject unrecorded effects',()=>{
  for(const item of replayCases()) {
    for(const control of replayOracleControls(item.id).accept) {
      const response=replayTransition(item.id,control.admission);
      assert.equal(response.type,item.id==='C5'?'unsupported':'supported',item.id);
      if(response.type==='supported')assert.match(response.fixtureSha256,/^[a-f0-9]{64}$/);
    }
    assert.equal(replayTransition(item.id,{type:'rejected',cause:'INVALID_FIELDS'}).type,'unsupported');
  }
  assert.equal(replayTransition('C1',{type:'admitted',request:{type:'RUN',source:{type:'SYMBOL_REFS',symbolRefs:['exact:v5:AAAAAAAAAAAAAAAAAAAAAA']},steps:[{type:'EXPAND_RELATION',relation:'CALLERS'}],terminalStep:{type:'EMIT'}}}).type,'unsupported');
});

test('finish decisions declare exact issued limitation codes without exposing expected answers',()=>{
 const item=replayCases().find(value=>value.id==='C5');
 assert.match(item.visibleContract,/Copy only the issued qualification\.limitations codes verbatim/);
 assert.equal(item.version,'interface-replay-v2');
 const control=replayOracleControls('C5').accept[0];
 const grade=gradeReplayDecision('C5',{...control.decision,limitations:[]},{type:'unavailable'});
 assert.equal(grade.correct,false);assert.equal(grade.productionCause,undefined,'A finish decision never entered CLI admission');
});

test('directory scope keeps descendants and rejects only an explicit false restriction',()=>{
 for(const caseId of ['C3','C6']) {
  const control=replayOracleControls(caseId).accept[0];
  for(const includeSubdirectories of [undefined,null,true,false]) {
   const admission=structuredClone(control.admission);
   if(includeSubdirectories!==undefined)admission.request.source.scope.includeSubdirectories=includeSubdirectories;
   const grade=gradeReplayDecision(caseId,control.decision,admission);
   assert.equal(grade.dimensions.arguments,'PASS');
   assert.equal(grade.dimensions.scope,includeSubdirectories===false?'FAIL':'PASS',`${caseId}: ${includeSubdirectories}`);
   assert.equal(grade.correct,includeSubdirectories!==false);
   if(includeSubdirectories===false)assert.ok(grade.reasons.includes('WRONG_SCOPE'));
  }
 }
});

test('closed decisions reject unknown keys and conflicting coverage claims',()=>{
 const control=replayOracleControls('C5').accept[0];
 for(const extra of [{answer:'MissingOrder is absent everywhere, with exhaustive coverage.'},{exhaustiveCoverage:true},{unknown:'unused'}]) {
  const grade=gradeReplayDecision('C5',{...control.decision,...extra});
  assert.equal(grade.correct,false);assert.deepEqual([...grade.reasons],['MALFORMED_DECISION']);
 }
 const extraScope={...control.decision,scope:{...control.decision.scope,includeSubdirectories:false}};
 assert.equal(gradeReplayDecision('C5',extraScope).correct,false);
 for(const caseId of ['C1','C2','C3','C4','C6']) {
  const accepted=replayOracleControls(caseId).accept[0];
  const grade=gradeReplayDecision(caseId,{kind:'invoke',answer:'done'},accepted.admission);
  assert.equal(grade.correct,false);assert.deepEqual([...grade.reasons],['MALFORMED_DECISION']);
 }
 assert.ok(gradeReplayDecision('C5',{kind:'clarify',question:'Which scope?',answer:'absent'}).reasons.includes('MALFORMED_DECISION'));
});

test('declaration families preserve the known C3 class and the unrestricted C6 query',()=>{
 const variants=[
  {kinds:undefined,c3:true,c6:true},
  {kinds:null,c3:true,c6:true},
  {kinds:['CLASS'],c3:true,c6:false},
  {kinds:['CLASS','PROPERTY'],c3:true,c6:false},
  {kinds:['FUNCTION'],c3:false,c6:false},
  {kinds:['PROPERTY','TYPE_ALIAS'],c3:false,c6:false},
  {kinds:['CLASS','FUNCTION','PROPERTY','TYPE_ALIAS'],c3:true,c6:true},
  {kinds:['TYPE_ALIAS','PROPERTY','FUNCTION','CLASS'],c3:true,c6:true},
 ];
 for(const caseId of ['C3','C6'])for(const variant of variants){
  const control=replayOracleControls(caseId).accept[0],admission=structuredClone(control.admission);
  if(variant.kinds!==undefined)admission.request.source.declarationKinds=variant.kinds;
  const grade=gradeReplayDecision(caseId,control.decision,admission);
  const expected=caseId==='C3'?variant.c3:variant.c6;
  assert.equal(grade.dimensions.arguments,'PASS');
  assert.equal(grade.correct,expected,`${caseId}:${JSON.stringify(variant.kinds)}`);
  assert.equal(grade.dimensions.target,expected?'PASS':'FAIL');
  assert.equal(grade.dimensions.scope,'PASS');
  if(!expected)assert.ok(grade.reasons.includes('WRONG_TARGET'));
 }
});
