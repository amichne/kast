import fs from 'node:fs';
import {createHash} from 'node:crypto';
const unavailable=reason=>({type:'unavailable',reason});
const sha=value=>createHash('sha256').update(value).digest('hex');
// Parsing establishes local shape and expiry only. The provider establishes access.
export function decodeReplayCredential(document,source,now) {
 if(typeof document!=='string'||Buffer.byteLength(document)>262144||!Number.isFinite(now))return unavailable('CREDENTIAL_DOCUMENT_REJECTED');
 let decoded;try{decoded=JSON.parse(document);}catch{return unavailable('CREDENTIAL_DOCUMENT_REJECTED');}
 let credential,account;
 if(source==='PI_OAUTH'){
  credential=decoded?.['openai-codex'];
  if(credential?.type!=='oauth'||typeof credential.access!=='string'||typeof credential.refresh!=='string')return unavailable('CREDENTIAL_UNAVAILABLE');
 }else if(source==='CODEX_CHATGPT'){
  if(decoded?.auth_mode!=='chatgpt'||typeof decoded.tokens?.access_token!=='string'||typeof decoded.tokens?.refresh_token!=='string')return unavailable('CODEX_CHATGPT_LOGIN_UNAVAILABLE');
  let claims;try{const parts=decoded.tokens.access_token.split('.');if(parts.length!==3)throw Error();claims=JSON.parse(Buffer.from(parts[1],'base64url').toString('utf8'));}catch{return unavailable('CREDENTIAL_DOCUMENT_REJECTED');}
  account=claims?.['https://api.openai.com/auth']?.chatgpt_account_id;
  if(typeof account!=='string'||!account||typeof decoded.tokens.account_id!=='string'||decoded.tokens.account_id!==account)return unavailable('CREDENTIAL_ACCOUNT_MISMATCH');
  credential={type:'oauth',access:decoded.tokens.access_token,refresh:decoded.tokens.refresh_token,expires:claims.exp*1000};
 }else return unavailable('CREDENTIAL_SOURCE_REJECTED');
 if(!Number.isSafeInteger(credential.expires)||credential.expires-now<15*60*1000)return unavailable('CREDENTIAL_UNAVAILABLE');
 if(account===undefined){try{account=JSON.parse(Buffer.from(credential.access.split('.')[1],'base64url').toString('utf8'))?.['https://api.openai.com/auth']?.chatgpt_account_id;}catch{return unavailable('CREDENTIAL_DOCUMENT_REJECTED');}}
 if(typeof account!=='string'||!account)return unavailable('CREDENTIAL_ACCOUNT_MISMATCH');
 return {type:'ready',source,credential,principalSha256:sha(account)};
}
export function loadReplayCredential(auth,plan,now) {
 let document;try{document=fs.readFileSync(plan.authPath,'utf8');}catch{return unavailable('CREDENTIAL_FILE_UNAVAILABLE');}
 const parsed=decodeReplayCredential(document,plan.credentialSource??'PI_OAUTH',now);
 if(parsed.type!=='ready')return parsed;
 const storage=parsed.source==='CODEX_CHATGPT'?auth.AuthStorage.inMemory({'openai-codex':parsed.credential}):new auth.ReadOnlyAuthStorage(plan.authPath);
 return {type:'ready',storage,source:parsed.source,principalSha256:parsed.principalSha256};
}
