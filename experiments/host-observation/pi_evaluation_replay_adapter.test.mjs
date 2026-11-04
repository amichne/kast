import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { createReplayShell } from './pi_evaluation_replay_shell.mjs';
import { replayOracleControls, gradeReplayDecision } from './pi_evaluation_replay_grading.mjs';
import { replayCases, replayTransition } from './pi_evaluation_replay_cases.mjs';

const manifestPath=process.env.KAST_REPLAY_ADAPTER_MANIFEST;
const integrationAvailable=process.platform==='darwin'&&typeof manifestPath==='string'&&path.isAbsolute(manifestPath)&&fs.existsSync(manifestPath);
const manifest=integrationAvailable?JSON.parse(fs.readFileSync(manifestPath,'utf8')):undefined;
function admission(request,format='JSON') {
 const result=spawnSync(manifest.command[0],[...manifest.command.slice(1),'admit'],{
  input:JSON.stringify(request),encoding:'utf8',timeout:10000,maxBuffer:1048576,
  env:{PATH:process.env.PATH,KAST_OUTPUT_TOON:format==='JSON'?'0':'1'},
 });
 assert.equal(result.status,0,result.stderr);
 return JSON.parse(result.stdout);
}
async function shellTrial(command,{format='JSON',caseId}={}) {
 const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-adapter-')));
 const directory=path.join(root,'fixture');
 let shellCalls=0,entries=0;
 const budget={shell:()=>++shellCalls<=1,invocation:()=>++entries<=1};
 const shell=await createReplayShell({directory,socket:path.join(root,'broker.sock'),budget,
  admit:request=>admission(request,format),transition:receipt=>caseId?replayTransition(caseId,receipt):({stdout:'REPLAY_TRANSITION_UNAVAILABLE\n',stderr:'',exitStatus:69})});
 try {
  const result=await shell.operations.exec(command,directory,{onData:()=>{},signal:new AbortController().signal,timeout:10});
  return {result,invocations:structuredClone(shell.invocations),captures:structuredClone(shell.captures),shellCalls,entries,failed:shell.failed};
 } finally {await shell.dispose();fs.rmSync(root,{recursive:true});}
}
const integration={skip:integrationAvailable?false:'Set KAST_REPLAY_ADAPTER_MANIFEST to the prepared JVM adapter manifest on macOS'};

test('declared preceding commands explicitly request the captured signature fields',integration,async()=>{
 for(const item of replayCases()){
  const row=await shellTrial(item.prefixCall.input.command);
  const receipt=row.invocations[0].admission;
  if(item.id==='C6'){
   assert.equal(receipt.type,'ARGUMENT_REJECTED');assert.equal(receipt.cause,'INVALID_FIELDS');assert.equal(receipt.stderr,item.stderr);
  }else{
   assert.equal(receipt.type,'ADMITTED');
   const request=receipt.arguments.request;
   assert.deepEqual(request.type==='RUN'?request.terminalStep.output:request.output,{type:'SYMBOLS',fields:['NAME','LOCATION','SIGNATURE']},item.id);
  }
  assert.equal(receipt.nativeCalls,0);
 }
});

test('production admission keeps caller domains retained projections and complete-text defaults',integration,async()=>{
 const caller=replayOracleControls('C1').accept[0].admission.request;
 const retained=replayOracleControls('C4').accept[0].admission.request;
 const stdinCommand=request=>`kast query --request - <<'REQUEST'\n${JSON.stringify({request})}\nREQUEST`;
 const cases=[
  ['C1',{...caller,steps:[{...caller.steps[0],expansionScope:{type:'SOURCE_DOMAIN',sourceSets:['test'],directory:'services/unrelated',sourcePolicy:'PRODUCTION_AND_TEST',generatedSources:'EXCLUDE'}}]},'scope'],
  ...['BINDING_ROWS','VALUE_PATHS'].map(type=>['C1',{...caller,terminalStep:{type:'EMIT',output:{type}}},'action']),
  ...[{type:'OCCURRENCES'},{type:'TRAVERSAL_RECORDS'},{type:'SYMBOLS',fields:['SOURCE']}].map(output=>['C4',{...retained,output},'action']),
 ];
 for(const [caseId,request,dimension]of cases){
  const row=await shellTrial(stdinCommand(request),{caseId});
  const receipt=row.invocations[0].admission;
  assert.equal(receipt.type,'ADMITTED');assert.equal(receipt.nativeCalls,0);
  const grade=gradeReplayDecision(caseId,{kind:'invoke'},receipt);
  assert.equal(grade.dimensions.arguments,'PASS');assert.equal(grade.dimensions.target,'PASS');assert.equal(grade.dimensions.reference,'PASS');assert.equal(grade.dimensions[dimension],'FAIL');
  assert.equal(row.captures[0].stdout,'');assert.equal(row.captures[0].exitStatus,2);
 }
 const positives=[
  ['C1',{...caller,steps:[{...caller.steps[0],expansionScope:{type:'WORKSPACE'}}]}],
  ...[{type:'SYMBOLS',fields:[]},{type:'OCCURRENCES'},{type:'TRAVERSAL_RECORDS'}].map(output=>['C1',{...caller,terminalStep:{type:'EMIT',output}}]),
  ...[[],['NAME'],['SIGNATURE','LOCATION']].map(fields=>['C4',{...retained,output:{type:'SYMBOLS',fields}}]),
  ...[undefined,null].map(text=>['C2',{...replayOracleControls('C2').accept[0].admission.request,text}]),
 ];
 for(const [caseId,request]of positives){
  const row=await shellTrial(stdinCommand(request));
  const receipt=row.invocations[0].admission;
  assert.equal(receipt.type,'ADMITTED');assert.equal(receipt.nativeCalls,0);
  assert.equal(gradeReplayDecision(caseId,{kind:'invoke'},receipt).correct,true,JSON.stringify(receipt));
  assert.equal(replayTransition(caseId,receipt).type,'supported');
 }
 const occurrence=await shellTrial(replayOracleControls('C4').accept[0].command+' --output occurrences',{caseId:'C4'});
 assert.equal(occurrence.invocations[0].admission.type,'ADMITTED');
 assert.equal(gradeReplayDecision('C4',{kind:'invoke'},occurrence.invocations[0].admission).correct,false);
 assert.equal(occurrence.captures[0].exitStatus,2);
});

test('production planning and foreign workspaces cannot supply successful fixture reads',integration,async()=>{
 for(const caseId of ['C3','C6']) {
  const original=replayOracleControls(caseId).accept[0].command;
  for(const [command,dimension]of [[original.replace('kast query ','kast query plan '),'action'],[original+' --workspace /another-worktree','scope']]) {
   const row=await shellTrial(command,{caseId});
   const receipt=row.invocations[0].admission;
   assert.equal(receipt.type,'ADMITTED');assert.equal(receipt.nativeCalls,0);
   const grade=gradeReplayDecision(caseId,{kind:'invoke'},receipt);
   assert.equal(grade.dimensions.arguments,'PASS');assert.equal(grade.dimensions.target,'PASS');
   assert.equal(grade.dimensions[dimension],'FAIL');assert.equal(grade.correct,false);
   assert.equal(row.captures[0].stdout,'');assert.equal(row.captures[0].exitStatus,2);
   assert.equal(row.captures[0].stderr,'kast replay: FIXTURE_TRANSITION_UNAVAILABLE\n');
  }
  const current=await shellTrial(original+' --workspace .');
  assert.equal(gradeReplayDecision(caseId,{kind:'invoke'},current.invocations[0].admission).correct,true);
 }
});

test('restricted real shell preserves equivalent commands through production admission and independent grades',integration,async()=>{
 for(const caseId of ['C1','C2','C3','C4','C6'])for(const control of replayOracleControls(caseId).accept) {
  const receipt=await shellTrial(control.command);
  assert.equal(receipt.failed,false,`${caseId}: ${JSON.stringify(receipt)}`);
  assert.equal(receipt.shellCalls,1);
  assert.equal(receipt.entries,1,JSON.stringify(receipt));
  assert.equal(receipt.invocations.length,1);
  assert.deepEqual(receipt.invocations[0].request.argv,control.argv);
  assert.equal(receipt.invocations[0].request.stdin,'');
  assert.equal(receipt.invocations[0].admission.nativeCalls,0);
  assert.equal(receipt.invocations[0].admission.type,'ADMITTED',JSON.stringify(receipt));
  const grade=gradeReplayDecision(caseId,control.decision,receipt.invocations[0].admission);
  assert.equal(grade.correct,true,`${caseId}: ${JSON.stringify(grade)}`);
  assert.equal(receipt.captures[0].command,control.command);
  assert.equal(receipt.captures[0].stdout,'REPLAY_TRANSITION_UNAVAILABLE\n');
  assert.equal(receipt.captures[0].stderr,'');
  assert.equal(receipt.captures[0].exitStatus,69);
 }
});

test('real stdin syntax admits valid wrong targets scope references and region then independent graders reject',integration,async()=>{
 const selections=[['C1',0],['C2',1],['C3',0],['C4',2],['C6',1]];
 for(const [caseId,index] of selections) {
  const control=replayOracleControls(caseId).reject[index];
  const stdin=JSON.stringify({request:control.admission.request});
  const command=`kast query --request - <<'REQUEST'\n${stdin}\nREQUEST`;
  const receipt=await shellTrial(command);
  assert.equal(receipt.entries,1,JSON.stringify(receipt));
  assert.equal(receipt.invocations[0].request.stdin,stdin+'\n');
  const admitted=receipt.invocations[0].admission;
  assert.equal(admitted.type,'ADMITTED',JSON.stringify(admitted));
  assert.equal(admitted.nativeCalls,0);
  const grade=gradeReplayDecision(caseId,control.decision,admitted);
  assert.equal(grade.correct,false);
  assert.ok(grade.reasons.includes(control.reason),JSON.stringify(grade));
 }
});

test('real shell preserves invalid-fields diagnostic stdout stderr status and raw quoting',integration,async()=>{
 const command="kast query --name OrderService --in services/orders --source-set main --fields 'name, signature'";
 const receipt=await shellTrial(command);
 assert.equal(receipt.entries,1,JSON.stringify(receipt));
 const invocation=receipt.invocations[0];
 assert.equal(invocation.request.argv.at(-1),'name, signature');
 assert.equal(invocation.admission.type,'ARGUMENT_REJECTED');
 assert.equal(invocation.admission.cause,'INVALID_FIELDS');
 assert.equal(invocation.admission.nativeCalls,0);
 assert.equal(receipt.captures[0].stdout,invocation.admission.stdout);
 assert.equal(receipt.captures[0].stderr,invocation.admission.stderr);
 assert.equal(receipt.captures[0].exitStatus,2);
 assert.match(receipt.captures[0].stderr,/^kast: INVALID_FIELDS\. Use unique, comma-separated --fields values:/);
 assert.equal(JSON.parse(receipt.captures[0].stdout).failure,'INVALID_COMMAND');
});

test('second attempted driver entry is counted and blocked before production parsing',integration,async()=>{
 const command="kast query --name OrderService; kast query --name OtherService";
 const receipt=await shellTrial(command);
 assert.equal(receipt.shellCalls,1);
 assert.equal(receipt.entries,2,JSON.stringify(receipt));
 assert.equal(receipt.invocations.length,1);
 assert.equal(receipt.captures[0].exitStatus,75);
 assert.match(receipt.captures[0].stderr,/REPLAY_INVOCATION_LIMIT/);
});

test('restricted shell cannot read evaluator files or start production Kast executable',integration,async()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'kast-replay-private-'));
 const secret=path.join(root,'oracle-private');fs.writeFileSync(secret,'PRIVATE_ORACLE_SENTINEL');
 try {
  const receipt=await shellTrial(`while IFS= read -r line; do printf '%s' "$line"; done < '${secret}'; /bin/echo KAST_PRODUCTION_EXECUTION`);
  assert.equal(receipt.entries,0);
  assert.equal(receipt.invocations.length,0);
  assert.equal(receipt.captures[0].stdout,'');
  assert.notEqual(receipt.captures[0].exitStatus,0);
  assert.match(receipt.captures[0].stderr,/Operation not permitted/);
 } finally {fs.rmSync(root,{recursive:true});}
});

test('production admission preserves declaration families and independent target grades reject changed intent',integration,async()=>{
 const variants=[
  {caseId:'C3',kinds:['FUNCTION'],correct:false},
  {caseId:'C3',kinds:['CLASS'],correct:true},
  {caseId:'C6',kinds:['FUNCTION'],correct:false},
  {caseId:'C6',kinds:['CLASS'],correct:false},
  {caseId:'C6',kinds:['TYPE_ALIAS','PROPERTY','FUNCTION','CLASS'],correct:true},
 ];
 for(const {caseId,kinds,correct}of variants){
  const control=structuredClone(replayOracleControls(caseId).accept[0]);
  control.admission.request.source.declarationKinds=kinds;
  const stdin=JSON.stringify({request:control.admission.request});
  const receipt=await shellTrial(`kast query --request - <<'REQUEST'\n${stdin}\nREQUEST`);
  assert.equal(receipt.failed,false);assert.equal(receipt.entries,1);
  const invocation=receipt.invocations[0],admitted=invocation.admission;
  assert.equal(invocation.request.stdin,stdin+'\n');
  assert.equal(admitted.type,'ADMITTED');assert.equal(admitted.nativeCalls,0);
  assert.deepEqual(admitted.arguments.request.source.declarationKinds,kinds);
  const grade=gradeReplayDecision(caseId,control.decision,admitted);
  assert.equal(grade.correct,correct,`${caseId}:${kinds}`);
  assert.equal(grade.dimensions.arguments,'PASS');assert.equal(grade.dimensions.scope,'PASS');
  assert.equal(grade.dimensions.target,correct?'PASS':'FAIL');
 }
});
