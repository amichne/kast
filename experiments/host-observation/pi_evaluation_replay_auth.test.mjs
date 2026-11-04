import test from 'node:test';import assert from 'node:assert/strict';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import * as auth from './pi_evaluation_replay_auth.mjs';
const now=1000000;
const access=(claims={exp:10000,'https://api.openai.com/auth':{chatgpt_account_id:'fixture-account'}})=>`header.${Buffer.from(JSON.stringify(claims)).toString('base64url')}.signature`;
const codex=(extra={})=>({auth_mode:'chatgpt',tokens:{access_token:access(),refresh_token:'private-refresh',account_id:'fixture-account'},...extra});
const decode=(document,source='CODEX_CHATGPT')=>{assert.equal(typeof auth.decodeReplayCredential,'function');return auth.decodeReplayCredential(JSON.stringify(document),source,now);};
test('Codex access credentials map exactly to the same provider without an ID token dependency',()=>{
 const result=decode(codex());assert.equal(result.type,'ready');assert.equal(result.credential.type,'oauth');
 assert.equal(result.credential.access,access());assert.equal(result.credential.refresh,'private-refresh');assert.equal(result.credential.expires,10000000);
 assert.equal(result.source,'CODEX_CHATGPT');assert.match(result.principalSha256,/^[0-9a-f]{64}$/);
});
test('expired ambiguous or unsupported Codex login states fail with finite reasons',()=>{
 const examples=[codex({auth_mode:'api_key'}),codex({tokens:{access_token:'malformed'}}),codex({tokens:{access_token:access({exp:100,'https://api.openai.com/auth':{chatgpt_account_id:'fixture-account'}}),refresh_token:'r',account_id:'fixture-account'}}),codex({tokens:{access_token:access(),refresh_token:'r',account_id:'another-account'}})];
 for(const value of examples){const result=decode(value);assert.equal(result.type,'unavailable');assert.equal(JSON.stringify(result).includes('private-refresh'),false);assert.equal(JSON.stringify(result).includes(access()),false);}
});
test('Pi credential behavior stays finite and exact',()=>{
 const credential={type:'oauth',access:access(),refresh:'private-refresh',expires:10000000};
 assert.equal(decode({'openai-codex':credential},'PI_OAUTH').credential.access,credential.access);
 assert.equal(decode({'openai-codex':{...credential,expires:now}},'PI_OAUTH').type,'unavailable');
});
test('read-only Codex loading uses in-memory SDK storage and leaves the source unchanged',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'kast-auth-proof-')),file=path.join(root,'auth.json');
 fs.writeFileSync(file,JSON.stringify(codex()),{mode:0o600});const before=fs.readFileSync(file);let stored;
 try{
  assert.equal(typeof auth.loadReplayCredential,'function');
  const result=auth.loadReplayCredential({AuthStorage:{inMemory:value=>{stored=value;return {kind:'memory'};}},ReadOnlyAuthStorage:class{constructor(){throw Error('PI_FILE_STORE_MUST_NOT_READ_CODEX_SHAPE');}}},{authPath:file,credentialSource:'CODEX_CHATGPT'},now);
  assert.equal(result.type,'ready');assert.equal(result.storage.kind,'memory');assert.equal(stored['openai-codex'].access,access());
  assert.deepEqual(fs.readFileSync(file),before);
 }finally{fs.rmSync(root,{recursive:true});}
});
