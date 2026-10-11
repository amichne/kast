#!/usr/bin/env node
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readPrivateCalibrationFile, loadInputCalibrations } from './pi_evaluation_calibrations.mjs';
import { inspectProviderPayload } from './pi_evaluation_payload.mjs';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';

const mode='NO_INFERENCE';
const qualification='INDEPENDENT_REQUEST_ADMISSION_NOT_SEQUENCE_OR_LIVE_PROOF';
const digest=value=>typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
const keys=(value,expected)=>value!==null && typeof value==='object' && !Array.isArray(value)
  && Object.keys(value).length===expected.length && expected.every(key=>Object.hasOwn(value,key));
const failure=(operation,reason)=>({type:'failure',mode,qualification,
  failure:{stage:'PROVIDER_PREFLIGHT',operation,reason}});
const privateReference=(file,sha256)=>({type:'OFFLINE_FULL_PAYLOAD_CALIBRATION',path:file,sha256});
const readJson=(reference,operation)=>{
  const read=readPrivateCalibrationFile(reference);
  if(read.type==='failure') return {...read,mode,qualification,source:operation};
  try {return {type:'decoded',value:JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(read.bytes)),
    sourceSha256:read.sourceSha256};}
  catch {return failure(operation,'JSON_REJECTED');}
};

// The manifest pins both the validated plan and complete provider bodies. The
// private reader's reference type is converted only here; no body bytes escape.
// DELIVERY checks use a new policy with its work grant replaced by the delivery
// grant. This admits one independent request, never a sequence or saved session.
export function preflightProviderRequests(options) {
  if(!keys(options,['planPath','manifestPath','manifestSha256'])) return failure('ARGUMENTS','ARGUMENTS_REJECTED');
  const {planPath,manifestPath,manifestSha256}=options;
  if(typeof planPath!=='string' || !path.isAbsolute(planPath) || typeof manifestPath!=='string'
      || !path.isAbsolute(manifestPath) || !digest(manifestSha256)) return failure('ARGUMENTS','ARGUMENTS_REJECTED');
  const manifestRead=readJson(privateReference(manifestPath,manifestSha256),'MANIFEST');
  if(manifestRead.type!=='decoded') return manifestRead;
  const manifest=manifestRead.value;
  if(!keys(manifest,['type','planSha256','requests']) || manifest.type!=='FULL_PROVIDER_REQUEST_PREFLIGHT'
      || !digest(manifest.planSha256) || !Array.isArray(manifest.requests)
      || manifest.requests.length<1 || manifest.requests.length>64) return failure('MANIFEST','MANIFEST_REJECTED');
  const planRead=readJson(privateReference(planPath,manifest.planSha256),'PLAN');
  if(planRead.type!=='decoded') return planRead;
  let plan;
  try {plan=validatePlan(planRead.value);} catch {return failure('PLAN','PLAN_REJECTED');}
  if(plan.cases.length>64 || plan.cases.some(item=>item.name.length>128)) return failure('PLAN','PLAN_REJECTED');
  const cases=new Map(plan.cases.map(item=>[item.name,item])),pairs=new Set();
  for(const request of manifest.requests) {
    if(!keys(request,['case','phase','body']) || !cases.has(request.case) || !['WORK','DELIVERY'].includes(request.phase)
        || !keys(request.body,['type','path','sha256']) || request.body.type!=='FULL_PROVIDER_PAYLOAD'
        || typeof request.body.path!=='string' || !path.isAbsolute(request.body.path) || !digest(request.body.sha256)
        || (cases.get(request.case).mode==='received-result' && request.phase!=='DELIVERY'))
      return failure('MANIFEST','REQUEST_REJECTED');
    const pair=`${request.case}:${request.phase}`;
    if(pairs.has(pair)) return failure('MANIFEST','DUPLICATE_REQUEST');
    pairs.add(pair);
  }
  if(plan.cases.some(item=>!pairs.has(`${item.name}:${item.mode==='fresh'?'WORK':'DELIVERY'}`)))
    return failure('MANIFEST','REQUEST_MISSING');
  const requests=[];
  for(const request of manifest.requests) {
    const item=cases.get(request.case),grant=request.phase==='WORK'?item.work:item.delivery;
    const modeRequestLimit=item.mode==='received-result'?1:3;
    const policy=new CasePolicy({...item,work:{...grant,tools:item.work.tools},
      maximumReportedTokens:item.maximumReportedTokens??(item.work.reportedTokens+item.delivery.reportedTokens),
      maximumProviderRequests:Math.min(item.maximumProviderRequests??modeRequestLimit,modeRequestLimit),
      wallSeconds:Math.min(item.wallSeconds,item.mode==='received-result'?60:120)});
    const calibration=loadInputCalibrations(item.inputCalibrations,policy);
    if(calibration.type==='failure') return {...calibration,mode,qualification,source:'CALIBRATION'};
    const body=readJson(privateReference(request.body.path,request.body.sha256),'BODY');
    if(body.type!=='decoded') return body;
    const outputTokenCap=policy.providerOutputCap();
    const payload=inspectProviderPayload(body.value,policy.maximumProviderPayloadBytes);
    const admission=policy.beforeProvider({provider:'openai-codex',model:'gpt-6.1-sol',thinking:'high',payload});
    const report=policy.report(),observation=report.requestObservations[0];
    requests.push({case:item.name,phase:request.phase,sourceSha256:body.sourceSha256,
      payloadSha256:payload.payloadSha256??null,method:observation?.boundMethod??'NO_VERIFIED_INPUT_BOUND',
      boundQualification:observation?.boundQualification??'NO_VERIFIED_INPUT_BOUND',
      calibrationMethod:observation?.calibrationMethod??null,
      calibrationQualification:observation?.calibrationQualification??null,
      calibrationSourceSha256:observation?.calibrationSourceSha256??null,
      inputEstimate:observation?.inputEstimate??null,
      inputEstimateMethod:observation?.inputEstimateMethod??null,
      inputEstimateQualification:observation?.inputEstimateQualification??null,
      calibratedInputCeiling:observation?.calibratedInputCeiling??null,
      allowance:grant.reportedTokens,requestCeiling:grant.requests,outputReserve:grant.outputReserve,outputTokenCap,
      outputCapRequested:outputTokenCap,outputCapApplied:report.outputCapApplied,
      backendOutputCapQualification:'UNQUALIFIED',
      maximumReportedTokens:report.bounds.maximumReportedTokens,
      maximumProviderRequests:policy.maximumProviderRequests,
      maximumProviderResponseBytes:policy.maximumProviderResponseBytes,wallSeconds:policy.config.wallSeconds,
      required:observation?.required??null,inputTokenCeiling:item.inputTokenCeiling,
      maximumProviderPayloadBytes:policy.maximumProviderPayloadBytes,declarationByteCeiling:item.declarationByteCeiling,
      payloadBytes:payload.payloadBytes??null,allow:admission.allow,reason:admission.reason});
  }
  return {type:'completed',mode,qualification,planSha256:planRead.sourceSha256,
    manifestSha256:manifestRead.sourceSha256,allow:requests.every(request=>request.allow),requests};
}

export function main(args) {
  const result=args.length===6 && args[0]==='--plan' && args[2]==='--manifest' && args[4]==='--sha256'
    ? preflightProviderRequests({planPath:args[1],manifestPath:args[3],manifestSha256:args[5]})
    : failure('ARGUMENTS','ARGUMENTS_REJECTED');
  console.log(JSON.stringify(result,null,2));
  if(result.type==='failure' || !result.allow) process.exitCode=1;
  return result;
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) main(process.argv.slice(2));
