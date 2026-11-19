#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateReplayPlan, expandReplayPlan } from './pi_evaluation_replay.mjs';
import { prepareReplayPlan } from './pi_evaluation_replay_identity.mjs';
import { CasePolicy, Outcome } from './pi_evaluation_policy.mjs';

export const expectedPolicyOutcomes = Object.freeze([Outcome.FINAL_ANSWER, Outcome.QUALIFIED_ANSWER, Outcome.REJECTION]);

export function validatePlan(plan) {
  if(plan.mode==='replay')return validateReplayPlan(plan);
  for(const name of ['piPackageRoot','kastAdapter','workspaceRoot','outputRoot','authPath','modelsStorePath']) {
    if(typeof plan[name]!=='string'||!path.isAbsolute(plan[name])) throw Error(`Explicit absolute ${name} required`);
  }
  if(!/^[a-f0-9]{64}$/.test(plan.kastAdapterSha256)||typeof plan.piVersion!=='string'||!plan.piVersion.length) throw Error('Pinned Pi version and installed adapter digest required');
  if(!Array.isArray(plan.cases)||plan.cases.length===0||new Set(plan.cases.map(c=>c.name)).size!==plan.cases.length) throw Error('Independent named cases required');
  for(const item of plan.cases) {
    if(!/^[a-z0-9-]+$/.test(item.name)||!Number.isSafeInteger(item.wallSeconds)||item.wallSeconds<1||item.wallSeconds>480) throw Error('Safe case name and finite wall bound required');
    new CasePolicy(item);
    if(item.expectedOutcome!==undefined&&!expectedPolicyOutcomes.includes(item.expectedOutcome)) throw Error('Explicit terminal policy outcome required');
    if(item.inputCalibrations!==undefined) {
      if(!Array.isArray(item.inputCalibrations)||item.inputCalibrations.length>64||new Set(item.inputCalibrations.map(value=>value.sha256)).size!==item.inputCalibrations.length) throw Error('Bounded unique offline input calibration references required');
      for(const reference of item.inputCalibrations) {
        if(!reference||Object.keys(reference).some(key=>!['type','path','sha256'].includes(key))||reference.type!=='OFFLINE_FULL_PAYLOAD_CALIBRATION'||typeof reference.path!=='string'||!path.isAbsolute(reference.path)||! /^[a-f0-9]{64}$/.test(reference.sha256)) throw Error('Absolute digest-pinned offline full-payload calibration required');
      }
    }
    if(item.mode==='fresh') {
      if(typeof item.prompt!=='string'||!item.prompt.length||item.receivedSessionFile!==undefined) throw Error('Fresh case requires its fixed prompt');
    } else if(item.mode==='received-result') {
      if(item.prompt!==undefined||!path.isAbsolute(item.receivedSessionFile??'')||typeof item.receivedResultEntryId!=='string'||!/^[a-f0-9]{64}$/.test(item.receivedContextSha256??'')||typeof item.receivedSessionSha256!=='string'||!/^[a-f0-9]{64}$/.test(item.receivedSessionSha256)) throw Error('Continuation requires digest-pinned saved session/result/context identity and no prompt');
    } else throw Error('Explicit mode required');
  }
  return plan;
}

export async function main(args) {
  if(args[0]==='--execute'||args[0]==='--assess-target'){
    const variable=args[0]==='--execute'?'KAST_PI_EVAL_PLAN':'KAST_PI_EVAL_TARGET';
    console.error(`Use ${variable}=/absolute/input.json npm run eval -- -c pi.config.mjs from experiments/host-observation/promptfoo`);
    process.exitCode=1;
    return;
  }
  if(args[0]==='--prepare-replay'&&args.length>=2&&args.length<=6){
    const options=args.slice(2),flags=options.filter(value=>value.startsWith('--')),cases=options.filter(value=>!value.startsWith('--'));
    if(cases.length>1||flags.some(value=>!/^--(auth=(codex|pi)|instructions=(full|concise)|execution=(fresh|session))$/.test(value))
      ||new Set(flags.map(value=>value.split('=')[0])).size!==flags.length)throw Error('REPLAY_PREPARATION_REJECTED');
    const selected=name=>flags.find(value=>value.startsWith(`--${name}=`))?.split('=')[1];
    const root=fileURLToPath(new URL('../..',import.meta.url));
    const plan=prepareReplayPlan(args[1],root,cases[0],{credentialSource:selected('auth')==='codex'?'CODEX_CHATGPT':'PI_OAUTH',instructionProfile:selected('instructions')??'full',executionProfile:selected('execution')??'fresh'});
    console.log(JSON.stringify({plan:args[1],trials:expandReplayPlan(plan),sourceRevision:plan.sourceRevision,diffSha256:plan.diffSha256,kastGuidanceSha256:plan.kastGuidanceSha256},null,2));return;
  }
  if(args.length!==2||args[0]!=='--plan') throw Error('Usage: node run_pi_evaluation.mjs --plan /absolute/plan.json');
  const planFile=path.resolve(args[1]);
  const plan=validatePlan(JSON.parse(fs.readFileSync(planFile,'utf8')));
  if(plan.mode==='replay'){
    console.log(JSON.stringify({mode:'NO_INFERENCE',trials:expandReplayPlan(plan),maximumProviderRequests:plan.maximumProviderRequests,workers:plan.workers,wallSeconds:60,nativeCalls:0,adapterManifestSha256:plan.adapterManifestSha256,kastGuidanceSha256:plan.kastGuidanceSha256},null,2));
    return;
  }
  console.log(JSON.stringify({mode:'NO_INFERENCE',cases:plan.cases.map(c=>({name:c.name,finalPresentation:c.finalPresentation??'canonical',work:c.work,delivery:c.delivery,bounds:new CasePolicy({...c,wallSeconds:Math.min(c.wallSeconds,c.mode==='received-result'?60:120),maximumProviderRequests:Math.min(c.maximumProviderRequests??(c.mode==='received-result'?1:3),c.mode==='received-result'?1:3)}).report().bounds,requestAdmissionMethod:'EXACT_SERIALIZED_REQUEST_BYTES_AND_FINITE_CALLS',tokenAccountingQualification:'POST_RESPONSE_THRESHOLD_ONE_RESPONSE_MAY_OVERSHOOT',inputCalibrationReferences:(c.inputCalibrations??[]).length}))},null,2));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) main(process.argv.slice(2)).catch(()=>{console.error('PI_EVALUATION_CONFIGURATION_OR_WORKER_FAILURE');process.exitCode=1;});
