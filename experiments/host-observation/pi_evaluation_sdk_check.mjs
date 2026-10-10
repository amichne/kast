#!/usr/bin/env node
// Opt-in real installed SDK validation. Only external network transports are
// doubles; no provider, credentials, semantic tools or model inference are used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { createEvaluationSession } from './pi_evaluation_worker.mjs';

const [piPackageRoot,kastAdapter,expectedAdapterSha256]=process.argv.slice(2);
if(!path.isAbsolute(piPackageRoot??'')||!path.isAbsolute(kastAdapter??'')||!/^[a-f0-9]{64}$/.test(expectedAdapterSha256??'')) throw Error('Explicit installed SDK root, actual adapter path and digest required');
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
assert.equal(sha(fs.readFileSync(kastAdapter)),expectedAdapterSha256);
const directory=fs.mkdtempSync(path.join(os.tmpdir(),'kast-pi-sdk-check-'));
const originals={fetch:globalThis.fetch,WebSocket:globalThis.WebSocket};
let fetchCalls=0,socketSends=0,sockets=0;
const events=()=>{
  const item={id:'synthetic-message',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'Synthetic transport response; no inference occurred.'}]};
  return [{type:'response.created',response:{id:'synthetic-response'}},{type:'response.output_item.added',output_index:0,item:{...item,content:[]}},{type:'response.output_item.done',output_index:0,item},{type:'response.completed',response:{id:'synthetic-response',status:'completed',output:[item],usage:{input_tokens:100,output_tokens:10,total_tokens:110,input_tokens_details:{cached_tokens:0}}}}];
};
class SyntheticSocket extends EventTarget {
  readyState=0;
  constructor(){super();sockets++;queueMicrotask(()=>{this.readyState=1;this.dispatchEvent(new Event('open'));});}
  send(){socketSends++;setImmediate(()=>{for(const value of events())this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify(value)}));});}
  close(){this.readyState=3;this.dispatchEvent(new Event('close'));}
}
globalThis.WebSocket=SyntheticSocket;
globalThis.fetch=async(_url,options)=>{
  assert.equal(options?.method,'POST','Unexpected catalog/background network request');
  fetchCalls++;
  return new Response(events().map(e=>`data: ${JSON.stringify(e)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
};
const load=relative=>import(pathToFileURL(path.join(piPackageRoot,relative)).href);
let activeSession,cleanupSocket;
try {
  const sdk=await load('dist/index.js');
  const {AuthStorage}=await load('dist/core/auth-storage.js');
  const {InMemoryModelsStore}=await load('../pi-ai/dist/models-store.js');
  const {closeOpenAICodexWebSocketSessions}=await load('../pi-ai/dist/api/openai-codex-responses.js');cleanupSocket=closeOpenAICodexWebSocketSessions;
  const modelsStore=new InMemoryModelsStore();
  // Public synthetic catalog metadata, with exact model/effort routing. This is
  // transport compatibility proof, not live model availability/auth proof.
  await modelsStore.write('openai-codex',{models:[{id:'gpt-6.1-sol',name:'GPT-6.1 Sol',provider:'openai-codex',api:'openai-codex-responses',baseUrl:'https://example.invalid',type:'chat',reasoning:true,input:['text'],contextWindow:272000,maxTokens:128000,cost:{input:0,output:0,cacheRead:0,cacheWrite:0},thinkingLevelMap:{high:'high'}}]});
  const access=`synthetic.${Buffer.from(JSON.stringify({'https://api.openai.com/auth':{chatgpt_account_id:'synthetic-account'}})).toString('base64url')}.unsigned`;
  const credentials=AuthStorage.inMemory({'openai-codex':{type:'oauth',access,refresh:'synthetic-never-refresh',expires:Date.now()+86400000}});
  const runtime=await sdk.ModelRuntime.create({credentials,modelsStore,modelsPath:null,allowModelNetwork:false});
  const makePolicy=()=>new CasePolicy({name:'sdk-check',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:100000,work:{reportedTokens:30000,requests:1,tools:1,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576});
  const plan={workspaceRoot:directory,kastAdapter};
  const records=[];
  const manager=sdk.SessionManager.inMemory(directory);
  const policy=makePolicy();
  const created=await createEvaluationSession({sdk,plan,directory,modelRuntime:runtime,policy,record:e=>records.push(e),manager});activeSession=created.session;
  const startup={transport:created.settingsManager.getTransport(),cacheWarming:created.settingsManager.getCacheWarmingMode(),provider:activeSession.model.provider,model:activeSession.model.id,thinking:activeSession.thinkingLevel,tools:activeSession.getActiveToolNames()};
  assert.equal(startup.transport,'sse');assert.equal(startup.cacheWarming,'off');
  assert.deepEqual(startup.tools,['query_symbols','check_diagnostics','health_check']);
  await activeSession.prompt('Fixed synthetic transport check.',{source:'rpc',expandPromptTemplates:false});
  assert.equal(policy.report().outcome,'FINAL_ANSWER');assert.equal(fetchCalls,1);assert.equal(socketSends,0);
  await activeSession.prompt('Intentionally denied second synthetic request.',{source:'rpc',expandPromptTemplates:false});
  assert.equal(records.filter(e=>e.type==='provider_admission').length,2);
  assert.equal(records.filter(e=>e.type==='provider_admission').at(-1).decision.allow,false);
  assert.equal(fetchCalls,1,'Denied second request must not reach fetch');assert.equal(socketSends,0);
  activeSession.dispose();activeSession=undefined;cleanupSocket(manager.getSessionId());
  // Reproduce the installed SDK's cached-WebSocket abort defect independently
  // using its real provider and guard, with synthetic transport only. This is
  // why the committed isolated worker selects supported SSE explicitly.
  const legacySettings=sdk.SettingsManager.inMemory({transport:'websocket',cacheWarming:'off',compaction:{enabled:false},retry:{enabled:false}});
  const legacyManager=sdk.SessionManager.inMemory(directory),legacyPolicy=makePolicy();
  const {evaluationGuard}=await import('./pi_evaluation_guard.mjs');
  const loader=new sdk.DefaultResourceLoader({cwd:directory,agentDir:directory,settingsManager:legacySettings,noExtensions:true,additionalExtensionPaths:[kastAdapter],extensionFactories:[evaluationGuard(legacyPolicy,()=>{})],noSkills:true,noPromptTemplates:true,noThemes:true,noContextFiles:true});
  await loader.reload();assert.equal(loader.getExtensions().errors.length,0);
  const legacy=await sdk.createAgentSession({cwd:directory,agentDir:directory,settingsManager:legacySettings,resourceLoader:loader,modelRuntime:runtime,model:runtime.getPhysicalModel('openai-codex','gpt-6.1-sol'),thinkingLevel:'high',tools:['query_symbols','check_diagnostics','health_check'],sessionManager:legacyManager});
  activeSession=legacy.session;await activeSession.bindExtensions({mode:'rpc',abortHandler:()=>activeSession.agent.abort()});
  await activeSession.prompt('First synthetic WebSocket request.',{source:'rpc',expandPromptTemplates:false});
  assert.equal(socketSends,1);assert.equal(sockets,1);
  await activeSession.prompt('Denied second synthetic WebSocket request.',{source:'rpc',expandPromptTemplates:false});
  assert.equal(socketSends,2,'Cached socket currently sends before observing the abort');assert.equal(sockets,1,'Regression must reuse the existing connection');
  activeSession.dispose();activeSession=undefined;cleanupSocket(legacyManager.getSessionId());
  const identity={};
  for(const [name,files] of Object.entries({'pi-coding-agent':['dist/core/sdk.js','dist/core/session-manager.js'],'pi-ai':['dist/api/openai-codex-responses.js'],'pi-agent-core':['dist/agent.js']})) {
    const root=name==='pi-coding-agent'?piPackageRoot:path.join(piPackageRoot,'..',name);
    identity[name]={version:JSON.parse(fs.readFileSync(path.join(root,'package.json'))).version,sha256:Object.fromEntries(['package.json',...files].map(file=>[file,sha(fs.readFileSync(path.join(root,file)))]))};
  }
  console.log(JSON.stringify({mode:'NO_INFERENCE_REAL_SDK_SYNTHETIC_TRANSPORT',startup,adapterSha256:expectedAdapterSha256,identity,sse:{admittedFetchCalls:fetchCalls,deniedSecondFetchCalls:0,socketSends:0},cachedWebSocket:{connections:sockets,admittedSends:1,deniedSecondSends:1},semanticToolCalls:0,realProviderRequests:0},null,2));
} finally {activeSession?.dispose();cleanupSocket?.();globalThis.fetch=originals.fetch;globalThis.WebSocket=originals.WebSocket;fs.rmSync(directory,{recursive:true,force:true});}
