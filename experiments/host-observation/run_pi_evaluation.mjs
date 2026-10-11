#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { runOwnedWorker } from './pi_evaluation_controller.mjs';

export function validatePlan(plan) {
  for(const name of ['piPackageRoot','kastAdapter','workspaceRoot','outputRoot','authPath','modelsStorePath']) {
    if(typeof plan[name]!=='string'||!path.isAbsolute(plan[name])) throw Error(`Explicit absolute ${name} required`);
  }
  if(!/^[a-f0-9]{64}$/.test(plan.kastAdapterSha256)||typeof plan.piVersion!=='string'||!plan.piVersion.length) throw Error('Pinned Pi version and installed adapter digest required');
  if(!Array.isArray(plan.cases)||plan.cases.length===0||new Set(plan.cases.map(c=>c.name)).size!==plan.cases.length) throw Error('Independent named cases required');
  for(const item of plan.cases) {
    if(!/^[a-z0-9-]+$/.test(item.name)||!Number.isSafeInteger(item.wallSeconds)||item.wallSeconds<1||item.wallSeconds>480) throw Error('Safe case name and finite wall bound required');
    new CasePolicy(item);
    if(item.inputCalibrations!==undefined) {
      if(!Array.isArray(item.inputCalibrations)||item.inputCalibrations.length>64||new Set(item.inputCalibrations.map(value=>value.sha256)).size!==item.inputCalibrations.length) throw Error('Bounded unique offline input calibration references required');
      for(const reference of item.inputCalibrations) {
        if(!reference||Object.keys(reference).some(key=>!['type','path','sha256'].includes(key))||reference.type!=='OFFLINE_FULL_PAYLOAD_CALIBRATION'||typeof reference.path!=='string'||!path.isAbsolute(reference.path)||! /^[a-f0-9]{64}$/.test(reference.sha256)) throw Error('Absolute digest-pinned offline full-payload calibration required');
      }
    }
    if(item.mode==='fresh') {
      if(typeof item.prompt!=='string'||!item.prompt.length||item.receivedSessionFile!==undefined) throw Error('Fresh case requires its fixed prompt');
    } else if(item.mode==='received-result') {
      if(item.prompt!==undefined||!path.isAbsolute(item.receivedSessionFile??'')||typeof item.receivedResultEntryId!=='string'||!/^[a-f0-9]{64}$/.test(item.receivedContextSha256??'')) throw Error('Continuation requires saved session/result/context identity and no prompt');
    } else throw Error('Explicit mode required');
  }
  return plan;
}

export async function main(args) {
  if(args.length!==2||!['--plan','--execute'].includes(args[0])) throw Error('Usage: node run_pi_evaluation.mjs --plan|--execute /absolute/plan.json');
  const planFile=path.resolve(args[1]);
  const plan=validatePlan(JSON.parse(fs.readFileSync(planFile,'utf8')));
  if(args[0]==='--plan') {
    console.log(JSON.stringify({mode:'NO_INFERENCE',cases:plan.cases.map(c=>({name:c.name,work:c.work,delivery:c.delivery,bounds:new CasePolicy({...c,wallSeconds:Math.min(c.wallSeconds,c.mode==='received-result'?60:120),maximumProviderRequests:Math.min(c.maximumProviderRequests??(c.mode==='received-result'?1:3),c.mode==='received-result'?1:3)}).report().bounds,requestAdmissionMethod:'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS',tokenAccountingQualification:'POST_RESPONSE_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT',inputCalibrationReferences:(c.inputCalibrations??[]).length}))},null,2));return;
  }
  // Existing pilot logs are never overwritten. Each execution owns a new root.
  fs.mkdirSync(plan.outputRoot,{mode:0o700});
  const controller=new AbortController();
  const cancel=()=>controller.abort();process.once('SIGINT',cancel);process.once('SIGTERM',cancel);
  const results=[];
  try {
    for(let index=0;index<plan.cases.length&&!controller.signal.aborted;index++) {
      const result=await runOwnedWorker(process.execPath,[fileURLToPath(new URL('pi_evaluation_worker.mjs',import.meta.url)),planFile,String(index)],
        {cwd:plan.workspaceRoot,env:{...process.env,KAST_TOOL_RPC_COMMAND:undefined},signal:controller.signal,wallMillis:Math.min(plan.cases[index].wallSeconds,plan.cases[index].mode==='received-result'?60:120)*1000});
      results.push({case:plan.cases[index].name,...result});
      fs.writeFileSync(path.join(plan.outputRoot,'report.json'),JSON.stringify({cases:results,timing:plan.timing??'UNQUALIFIED'},null,2)+'\n',{mode:0o600});
    }
  } finally {process.removeListener('SIGINT',cancel);process.removeListener('SIGTERM',cancel);}
  console.log(JSON.stringify({report:path.join(plan.outputRoot,'report.json'),caseOutcomes:results.map(r=>({case:r.case,type:r.type,outcome:r.report?.outcome}))}));
  if(results.some(r=>r.type!=='report'||r.exitCode!==0)) process.exitCode=1;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) main(process.argv.slice(2)).catch(()=>{console.error('PI_EVALUATION_CONFIGURATION_OR_WORKER_FAILURE');process.exitCode=1;});
