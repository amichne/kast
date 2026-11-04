import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { replayCases } from './pi_evaluation_replay_cases.mjs';
import { replaySourceIdentity,examineReplayPins } from './pi_evaluation_replay_identity.mjs';

const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
function setup(t){
 const owned=fs.mkdtempSync(path.join(os.tmpdir(),'kast-source-pins-')),root=path.join(owned,'repo');fs.mkdirSync(root);
 t.after(()=>fs.rmSync(owned,{recursive:true,force:true}));
 const git=(...args)=>execFileSync('git',['-C',root,...args],{stdio:'pipe',env:{PATH:process.env.PATH,HOME:owned,GIT_CONFIG_NOSYSTEM:'1',GIT_CONFIG_GLOBAL:os.devNull,GIT_TERMINAL_PROMPT:'0'}});
 git('init','-q');fs.writeFileSync(path.join(root,'worker.mjs'),'original source\n');git('add','worker.mjs');
 const commit=()=>git('-c','user.name=Replay Test','-c','user.email=replay@example.invalid','commit','-qm','owned fixture');commit();
 const adapterManifest=path.join(owned,'adapter.json');fs.writeFileSync(adapterManifest,JSON.stringify({artifacts:[]}));
 const plan={...replaySourceIdentity(root),adapterManifest,adapterManifestSha256:sha(fs.readFileSync(adapterManifest)),sdkIdentity:[],cases:replayCases().map(item=>({id:item.id,canonicalSha256:item.canonicalSha256,promptSha256:sha(item.task),contractSha256:sha(item.visibleContract)}))};
 return {root,plan,git,commit};
}

test('source pin rejects tracked, staged, and committed changes before replay effects',t=>{
 const {root,plan,git,commit}=setup(t);
 assert.doesNotThrow(()=>examineReplayPins(plan,{root}));
 fs.writeFileSync(path.join(root,'worker.mjs'),'changed source\n');
 assert.throws(()=>examineReplayPins(plan,{root}),/REPLAY_SOURCE_CHANGED/);
 git('add','worker.mjs');assert.throws(()=>examineReplayPins(plan,{root}),/REPLAY_SOURCE_CHANGED/);
 commit();assert.throws(()=>examineReplayPins(plan,{root}),/REPLAY_SOURCE_CHANGED/);
});

test('only the owned untracked plan file is excluded from source identity',t=>{
 const {root,plan}=setup(t),planFile=path.join(root,'plan.json');
 fs.writeFileSync(planFile,JSON.stringify(plan));
 assert.doesNotThrow(()=>examineReplayPins(plan,{root,planFile}));
 assert.throws(()=>examineReplayPins(plan,{root}),/REPLAY_SOURCE_CHANGED/);
 fs.writeFileSync(path.join(root,'other.mjs'),'unexpected source\n');
 assert.throws(()=>examineReplayPins(plan,{root,planFile}),/REPLAY_SOURCE_CHANGED/);
});

test('an excluded plan path cannot conceal a changed tracked file',t=>{
 const {root,plan}=setup(t),planFile=path.join(root,'worker.mjs');
 fs.writeFileSync(planFile,'changed source\n');
 assert.throws(()=>examineReplayPins(plan,{root,planFile}),/REPLAY_SOURCE_CHANGED/);
});
