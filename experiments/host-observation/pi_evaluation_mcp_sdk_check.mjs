#!/usr/bin/env node
// Opt-in installed SDK proof. External MCP/provider transports are scripted.
// No credentials, model inference, installed Kast process, or IDE call occurs.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { findPackageJSON } from 'node:module';
import { pathToFileURL } from 'node:url';
import { zstdDecompressSync } from 'node:zlib';
import { CasePolicy } from './pi_evaluation_policy.mjs';
import { createEvaluationSession, disposeEvaluationSession } from './pi_evaluation_worker.mjs';

const [piPackageRoot,kastAdapter,adapterSha256]=process.argv.slice(2);
assert.ok(path.isAbsolute(piPackageRoot??'')&&path.isAbsolute(kastAdapter??''));
assert.equal(crypto.createHash('sha256').update(fs.readFileSync(kastAdapter)).digest('hex'),adapterSha256);
const load=relative=>import(pathToFileURL(path.join(piPackageRoot,relative)).href);
const dependency=(name,relative)=>import(pathToFileURL(path.join(path.dirname(findPackageJSON(name,
  pathToFileURL(path.join(piPackageRoot,'package.json')))),relative)).href);
const sdk=await load('dist/index.js');
const {AuthStorage}=await load('dist/core/auth-storage.js');
const {InMemoryModelsStore}=await dependency('@earendil-works/pi-ai','dist/models-store.js');
const {convertResponsesMessages}=await dependency('@earendil-works/pi-ai','dist/api/openai-responses-shared.js');
const {createInMemoryTransportPair}=await dependency('@earendil-works/pi-mcp','dist/testing/index.js');
const root=fs.mkdtempSync(path.join(os.tmpdir(),'kast-native-mcp-sdk-'));
const originalFetch=globalThis.fetch;
const document=JSON.parse(fs.readFileSync(new URL('./pi-fixtures/complete-document.json',import.meta.url)));
const request={type:'RUN',source:{type:'ALL_DECLARATIONS'},terminalStep:{type:'EMIT'}};
const names=['query_symbols','check_diagnostics','health_check','replace_body','query_symbols_renamed'];
const outcomes=[];
let active;
try {
  const modelsStore=new InMemoryModelsStore();
  await modelsStore.write('openai-codex',{models:[{id:'gpt-6.1-sol',name:'Synthetic catalog',provider:'openai-codex',
    api:'openai-codex-responses',baseUrl:'https://example.invalid',type:'chat',reasoning:true,input:['text'],
    contextWindow:272000,maxTokens:128000,cost:{input:0,output:0,cacheRead:0,cacheWrite:0},thinkingLevelMap:{high:'high'}}]});
  const access=`synthetic.${Buffer.from(JSON.stringify({'https://api.openai.com/auth':{chatgpt_account_id:'synthetic'}})).toString('base64url')}.unsigned`;
  const runtime=await sdk.ModelRuntime.create({credentials:AuthStorage.inMemory({'openai-codex':{
    type:'oauth',access,refresh:'synthetic',expires:Date.now()+86400000}}),modelsStore,modelsPath:null,allowModelNetwork:false});
  for(const [presentation,proposed] of [['canonical','mcp__kast__query_symbols'],['compact','mcp__kast__query_symbols'],
    ['canonical','mcp__kast__replace_body'],['canonical','mcp__kast__query_symbols_renamed']]) {
    const directory=path.join(root,presentation+'-'+proposed);fs.mkdirSync(directory,{mode:0o700});
    let providerCalls=0,connectorCalls=0,transportClosed=false;
    const methods=[],records=[],providerBodies=[];
    const policy=new CasePolicy({name:proposed,expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:100000,
      work:{reportedTokens:30000,requests:1,tools:1,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},
      maximumToolTextBytes:1048576,finalPresentation:presentation});
    globalThis.fetch=async(_url,options)=>{
      assert.equal(options.method,'POST');
      const bytes=new Headers(options.headers).get('content-encoding')==='zstd'?zstdDecompressSync(options.body).toString('utf8'):options.body;
      const body=JSON.parse(bytes);providerBodies.push(body);
      assert.equal(body.max_output_tokens,2000);
      assert.ok(++providerCalls<=2,'No provider retry or excess request');
      const tool=providerCalls===1;
      const item=tool?{id:'synthetic-function',type:'function_call',call_id:'synthetic-call',name:proposed,
        arguments:JSON.stringify({request}),status:'completed'}:
        {id:'synthetic-message',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'Synthetic final answer.'}]};
      const events=[{type:'response.created',response:{id:`synthetic-${providerCalls}`}},
        {type:'response.output_item.added',output_index:0,item:tool?{...item,arguments:''}:{...item,content:[]}},
        {type:'response.output_item.done',output_index:0,item},
        {type:'response.completed',response:{id:`synthetic-${providerCalls}`,status:'completed',output:[item],
          usage:{input_tokens:100,output_tokens:10,total_tokens:110,input_tokens_details:{cached_tokens:0}}}}];
      return new Response(events.map(event=>`data: ${JSON.stringify(event)}\n\n`).join(''),{headers:{'content-type':'text/event-stream'}});
    };
    const realConnectorSdk={...sdk,createMcpExtension:options=>sdk.createMcpExtension({...options,
      createTransport:(entry,cwd)=>{
        assert.equal(entry.name,'kast');assert.equal(cwd,directory);
        assert.equal(entry.config.command,path.join(os.homedir(),'.local/share/kast/installation/bin/kast-mcp-complete'));
        assert.equal(entry.config.timeout,180);
        assert.equal(entry.config.exposure,'hidden');
        assert.deepEqual(entry.config.toolExposure,{query_symbols:'direct',check_diagnostics:'direct',health_check:'direct'});
        const {client,server}=createInMemoryTransportPair();
        client.onClose(()=>{transportClosed=true;});
        server.onMessage(message=>{
          methods.push(message.method);
          if(message.id===undefined)return;
          let result;
          if(message.method==='initialize')result={protocolVersion:message.params.protocolVersion,
            capabilities:{tools:{}},serverInfo:{name:'synthetic-kast',version:'1'}};
          else if(message.method==='tools/list')result={tools:names.map(name=>({name,
            inputSchema:{type:'object',properties:{request:{type:'object'}},required:['request']},
            outputSchema:{type:'object',properties:{status:{type:'string'}},required:['status']}}))};
          else if(message.method==='tools/call') {
            assert.equal(message.params.name,'query_symbols');assert.deepEqual(message.params.arguments,{request});
            assert.equal(++connectorCalls,1,'No repeated semantic call');
            result={content:[{type:'text',text:JSON.stringify(document)}],structuredContent:document,isError:false};
          } else assert.fail(`Unexpected MCP method ${message.method}`);
          void server.send({jsonrpc:'2.0',id:message.id,result});
        });
        void server.start();return client;
      }})};
    const created=await createEvaluationSession({sdk:realConnectorSdk,plan:{workspaceRoot:directory,kastAdapter},directory,
      modelRuntime:runtime,policy,record:event=>{records.push(event);return {type:'recorded'};},
      manager:sdk.SessionManager.inMemory(directory),
      projectProviderInput:(model,messages)=>convertResponsesMessages(model,{messages:sdk.convertToLlm(messages)},
        new Set(['openai-codex']),{includeSystemPrompt:false})});
    assert.equal(created.type,'session',created.type==='failure'?JSON.stringify(created):undefined);active=created.session;
    await active.prompt('Use the fixed synthetic tool request.',{source:'rpc',expandPromptTemplates:false});
    const permitted=proposed==='mcp__kast__query_symbols';
    assert.equal(connectorCalls,permitted?1:0);
    assert.equal(providerCalls,permitted?2:1);
    if(permitted) {
      assert.equal(policy.report().outcome,'FINAL_ANSWER',JSON.stringify(policy.report()));
      assert.equal(policy.report().nativeRepliesObserved,1);
      assert.equal(policy.report().semanticRepliesObserved,1);
      const result=active.agent.state.messages.find(message=>message.role==='toolResult');
      assert.deepEqual(result.details,{server:'kast',tool:'query_symbols',
        kastEvaluation:{type:'MCP_CANONICAL_RESULT',envelope:{type:'complete',document}}});
      assert.equal(Object.hasOwn(providerBodies[1],'tools'),false);
    }
    assert.deepEqual(providerBodies[0].tools.map(tool=>tool.name).sort(),
      ['mcp__kast__query_symbols','mcp__kast__check_diagnostics','mcp__kast__health_check'].sort());
    assert.equal((await disposeEvaluationSession(active)).type,'observed');active=undefined;
    assert.equal(transportClosed,true);
    assert.deepEqual(methods.filter(method=>method==='tools/call'),permitted?['tools/call']:[]);
    outcomes.push({presentation,proposed,providerCalls,connectorCalls,outcome:policy.report().outcome,transportClosed});
  }
  console.log(JSON.stringify({qualification:'REAL_INSTALLED_SDK_AND_NATIVE_CONNECTOR_WITH_SCRIPTED_EXTERNAL_TRANSPORTS',
    modelInferenceCalls:0,installedKastCalls:0,adapterSha256,piVersion:JSON.parse(fs.readFileSync(path.join(piPackageRoot,'package.json'))).version,outcomes},null,2));
} finally {
  if(active)await disposeEvaluationSession(active);
  globalThis.fetch=originalFetch;fs.rmSync(root,{recursive:true,force:true});
}
