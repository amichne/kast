import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { replayCases,heldOutManifest } from './pi_evaluation_replay_cases.mjs';
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
export function replaySourceIdentity(root) {
 const git=(...args)=>execFileSync('git',['-C',root,...args],{encoding:'utf8'}).trim();
 const paths=new Set([...git('diff','HEAD','--name-only').split('\n'),...git('ls-files','--others','--exclude-standard').split('\n')].filter(Boolean));
 const files=[...paths].sort().map(file=>({path:file,sha256:fs.existsSync(path.join(root,file))?sha(fs.readFileSync(path.join(root,file))):'DELETED'}));
 return {sourceRevision:git('rev-parse','HEAD'),diffSha256:sha(JSON.stringify(files)),files};
}
export function prepareReplayPlan(planPath,root,caseId,{credentialSource='PI_OAUTH',instructionProfile='full',executionProfile='fresh'}={}) {
 if(!['PI_OAUTH','CODEX_CHATGPT'].includes(credentialSource))throw Error('CREDENTIAL_SOURCE_REJECTED');
 if(!path.isAbsolute(planPath))throw Error('ABSOLUTE_PLAN_PATH_REQUIRED');
 const home=os.homedir(),install=path.join(home,'.pi/agent/install');
 const version=fs.readFileSync(path.join(install,'current-version'),'utf8').trim();
 if(!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(version))throw Error('SDK_VERSION_REJECTED');
 const piPackageRoot=path.join(install,'releases',version,'node_modules/@earendil-works/pi-coding-agent');
 const adapterManifest=path.join(root,'cli/build/replay-adapter/manifest.json');
 if(!['fresh','session'].includes(executionProfile))throw Error('REPLAY_EXECUTION_PROFILE_REJECTED');
 const identity=replaySourceIdentity(root),cases=replayCases(instructionProfile),sdkFiles=['package.json','dist/core/sdk.js','dist/core/session-manager.js','dist/core/tools/bash.js','dist/core/extensions/runner.js','../pi-ai/package.json','../pi-ai/dist/api/openai-codex-responses.js','../pi-ai/dist/api/openai-responses-shared.js','../pi-agent-core/package.json','../pi-agent-core/dist/agent.js'];
 const plan={mode:'replay',instructionProfile,executionProfile,publicContractSha256:sha(fs.readFileSync(path.join(root,'agent-tools/references/query-schema.json'))),workers:8,repetitions:2,caseIds:caseId?[caseId]:cases.map(item=>item.id),maximumProviderRequests:caseId?4:24,wallSeconds:60,
  piPackageRoot,piVersion:version,credentialSource,authPath:credentialSource==='CODEX_CHATGPT'?path.join(process.env.CODEX_HOME??path.join(home,'.codex'),'auth.json'):path.join(home,'.pi/agent/auth.json'),modelsStorePath:path.join(home,'.pi/agent/models-store.json'),
  adapterManifest,adapterManifestSha256:sha(fs.readFileSync(adapterManifest)),outputRoot:planPath+'.run',
  ...identity,sdkIdentity:sdkFiles.map(file=>({file,sha256:sha(fs.readFileSync(path.join(piPackageRoot,file)))})),
  cases:cases.map(item=>({id:item.id,version:item.version,canonicalSha256:item.canonicalSha256,promptSha256:sha(item.task),contractSha256:sha(item.visibleContract)})),heldOut:heldOutManifest(),
  model:{provider:'openai-codex',id:'gpt-6.1-sol',effort:'high'},
  caps:{maximumProviderPayloadBytes:262144,maximumProviderResponseBytes:65536,maximumOutputTokens:2000,maximumShellCalls:1,maximumInvocations:1,maximumNativeCalls:0},
  settings:{retries:0,compaction:false,cacheWarming:'off',transport:'sse'}};
 fs.writeFileSync(planPath,JSON.stringify(plan,null,2)+'\n',{mode:0o600,flag:'wx'});return plan;
}
export function examineReplayPins(plan) {
 if(sha(fs.readFileSync(plan.adapterManifest))!==plan.adapterManifestSha256)throw Error('ADAPTER_MANIFEST_CHANGED');
 const manifest=JSON.parse(fs.readFileSync(plan.adapterManifest));
 for(const artifact of manifest.artifacts)if(sha(fs.readFileSync(artifact.path))!==artifact.sha256)throw Error('ADAPTER_ARTIFACT_CHANGED');
 for(const entry of plan.sdkIdentity??[])if(sha(fs.readFileSync(path.join(plan.piPackageRoot,entry.file)))!==entry.sha256)throw Error('SDK_ARTIFACT_CHANGED');
 if(plan.publicContractSha256&&sha(fs.readFileSync(new URL('../../agent-tools/references/query-schema.json',import.meta.url)))!==plan.publicContractSha256)throw Error('PUBLIC_CONTRACT_CHANGED');
 for(const item of replayCases(plan.instructionProfile??'full')){
  const expected=plan.cases.find(c=>c.id===item.id);
  if(!expected||expected.canonicalSha256!==item.canonicalSha256||expected.promptSha256!==sha(item.task)||expected.contractSha256!==sha(item.visibleContract))throw Error('CASE_IDENTITY_CHANGED');
 }
}
