#!/usr/bin/env node
// Opt-in real installed SDK validation. Only external network transports are
// doubles; no provider, credentials, semantic tools or model inference are used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import * as zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';
import { CasePolicy, decodeEnvelope } from './pi_evaluation_policy.mjs';
import { createEvaluationSession } from './pi_evaluation_worker.mjs';
import { driveSession } from './pi_evaluation_controller.mjs';
import { inspectProviderPayload, verifyInputCalibration } from './pi_evaluation_payload.mjs';
import { withPrivateReplaySession } from './pi_evaluation_records.mjs';

const [piPackageRoot,kastAdapter,expectedAdapterSha256]=process.argv.slice(2);
if(!path.isAbsolute(piPackageRoot??'')||!path.isAbsolute(kastAdapter??'')||!/^[a-f0-9]{64}$/.test(expectedAdapterSha256??'')) throw Error('Explicit installed SDK root, actual adapter path and digest required');
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
assert.equal(sha(fs.readFileSync(kastAdapter)),expectedAdapterSha256);
assert.equal(process.env.KAST_TOOL_RPC_COMMAND,undefined,'Installed canonical RPC route required; no command override');
const directory=fs.mkdtempSync(path.join(os.tmpdir(),'kast-pi-sdk-check-'));
const originals={fetch:globalThis.fetch,WebSocket:globalThis.WebSocket};
let fetchCalls=0,socketSends=0,sockets=0;const bodies=[],expectedFetchHashes=[],socketBodies=[];
const scriptedResponses=[];
const events=()=>{
  if(scriptedResponses.length) return scriptedResponses.shift();
  const item={id:'synthetic-message',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'Synthetic transport response; no inference occurred.'}]};
  return [{type:'response.created',response:{id:'synthetic-response'}},{type:'response.output_item.added',output_index:0,item:{...item,content:[]}},{type:'response.output_item.done',output_index:0,item},{type:'response.completed',response:{id:'synthetic-response',status:'completed',output:[item],usage:{input_tokens:100,output_tokens:10,total_tokens:110,input_tokens_details:{cached_tokens:0}}}}];
};
class SyntheticSocket extends EventTarget {
  readyState=0;
  constructor(){super();sockets++;queueMicrotask(()=>{this.readyState=1;this.dispatchEvent(new Event('open'));});}
  send(encoded){
    const {type,...body}=JSON.parse(encoded);
    assert.equal(type,'response.create');assert.equal(body.max_output_tokens,2000);
    socketBodies.push(body);socketSends++;
    setImmediate(()=>{for(const value of events())this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify(value)}));});
  }
  close(){this.readyState=3;this.dispatchEvent(new Event('close'));}
}
globalThis.WebSocket=SyntheticSocket;
globalThis.fetch=async(_url,options)=>{
  assert.equal(options?.method,'POST','Unexpected catalog/background network request');
  const encoded=new Headers(options.headers).get('content-encoding')==='zstd'?zlib.zstdDecompressSync(options.body).toString('utf8'):options.body;
  const body=JSON.parse(encoded);
  assert.equal(body.max_output_tokens,2000,'Every admitted SDK request must carry the provider output cap');
  const payload=inspectProviderPayload(body,4194304);
  assert.equal(payload.type,'admitted-payload');
  assert.equal(payload.payloadSha256,expectedFetchHashes.shift(),'Fetch must carry the entire inspected projection');
  bodies.push(body);fetchCalls++;
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
  const makePolicy=(overrides={})=>new CasePolicy({name:'sdk-check',expectedSemanticCalls:1,inputTokenCeiling:20000,declarationByteCeiling:100000,work:{reportedTokens:30000,requests:1,tools:1,outputReserve:2000},delivery:{reportedTokens:25000,requests:1,outputReserve:2000},maximumToolTextBytes:1048576,...overrides});
  const plan={workspaceRoot:directory,kastAdapter};
  // Public inline extension observation leaves the committed guard and session
  // construction intact. It observes the final payload after the guard's cap.
  const observingSdk=captured=>({...sdk,DefaultResourceLoader:class extends sdk.DefaultResourceLoader {
    constructor(options) {super({...options,extensionFactories:[...options.extensionFactories,pi=>{
      pi.on('before_provider_request',event=>{captured.push(structuredClone(event.payload));});
    }]});}
  }});
  const privateStage=async(name,sourceFile,run)=>{
    let callbackError;
    const result=await withPrivateReplaySession(sdk.SessionManager,{directory:path.join(directory,name),workspaceRoot:directory,sourceFile},async manager=>{
      try {return await run(manager);} catch(error) {callbackError=error;throw error;}
    });
    assert.equal(result.type,'completed',callbackError?.stack??JSON.stringify(result.failure));
    return result.value;
  };
  const syntheticCalibration=(name,request)=>{
    // This receipt is explicitly constructed from synthetic transport evidence.
    // It cannot establish tokenizer accuracy or live provider calibration.
    const receipt=Buffer.from(JSON.stringify({type:'FULL_PAYLOAD_CALIBRATION',provider:'openai-codex',model:'gpt-6.1-sol',request,
      usage:{input:100,cacheRead:0,cacheWrite:0,output:10,totalTokens:110},calibratedInputCeiling:100,
      method:'EXACT_PROJECTED_REQUEST_FINALIZED_USAGE',qualification:'CALIBRATION_NOT_TOKENIZER_PROOF'}));
    const file=path.join(directory,`${name}-synthetic-calibration.json`),digest=sha(receipt);
    fs.writeFileSync(file,receipt,{mode:0o600,flag:'wx'});
    const calibration=verifyInputCalibration(fs.readFileSync(file),digest);
    assert.equal(calibration.type,'verified-input-calibration');
    return calibration;
  };
  const preflight=async(name,sourceFile,makeCasePolicy,action)=>{
    const captured=[],policy=makeCasePolicy({maximumProviderPayloadBytes:1000}),records=[],fetchBefore=fetchCalls,sendsBefore=socketSends;
    await privateStage(`${name}-preflight`,sourceFile,async manager=>{
      const created=await createEvaluationSession({sdk:observingSdk(captured),plan,directory,modelRuntime:runtime,policy,record:e=>records.push(e),manager});
      if(created.type==='failure')return created;
      activeSession=created.session;
      try {await action(activeSession,manager);} finally {activeSession.dispose();activeSession=undefined;cleanupSocket(manager.getSessionId());}
    });
    const shape=body=>{
      let nodes=0,depth=0;const anomalies=[];
      const visit=(value,level)=>{
        nodes++;depth=Math.max(depth,level);
        if(value&&typeof value==='object') {
          if(!Array.isArray(value)&&Object.getPrototypeOf(value)!==Object.prototype) anomalies.push('NON_PLAIN_OBJECT');
          for(const child of Object.values(value))visit(child,level+1);
        } else if(!['string','boolean','number','undefined'].includes(typeof value)&&value!==null) anomalies.push(typeof value);
      };
      visit(body,0);return {nodes,depth,anomalies:anomalies.slice(0,3)};
    };
    assert.equal(policy.report().outcome,'HARNESS_BUDGET_LIMIT',JSON.stringify({records,capturedFacts:captured.map(body=>inspectProviderPayload(body,4194304)),shape:captured.map(shape)}));
    assert.equal(captured.length,1);
    assert.equal(fetchCalls,fetchBefore,'Oversize preflight must deny before fetch');
    assert.equal(socketSends,sendsBefore);
    const admission=records.find(e=>e.type==='provider_admission');
    assert.equal(admission.decision.allow,false);
    assert.equal(admission.decision.reason,'HARNESS_BUDGET_LIMIT');
    const structure=shape(captured[0]);
    assert.deepEqual(structure.anomalies,[]);
    assert.ok(structure.nodes<=32768);assert.ok(structure.depth<=64);
    return {calibration:syntheticCalibration(name,captured[0]),report:policy.report(),structure};
  };
  const prompt='Fixed synthetic transport check.';
  const freshPreflight=await preflight('fresh',undefined,makePolicy,session=>session.prompt(prompt,{source:'rpc',expandPromptTemplates:false}));
  assert.equal(freshPreflight.structure.depth,33,'Pinned SDK declarations retain their complete nested schema');
  assert.equal(freshPreflight.structure.nodes,3895);
  const policy=makePolicy(),records=[];

  const startup=await privateStage('fresh-admitted',undefined,async manager=>{
    const created=await createEvaluationSession({sdk,plan,directory,modelRuntime:runtime,policy,record:e=>records.push(e),manager});
    if(created.type==='failure')return created;
    activeSession=created.session;
    const startup={transport:created.settingsManager.getTransport(),cacheWarming:created.settingsManager.getCacheWarmingMode(),provider:activeSession.model.provider,model:activeSession.model.id,thinking:activeSession.thinkingLevel,tools:activeSession.getActiveToolNames()};
    assert.equal(startup.transport,'sse');assert.equal(startup.cacheWarming,'off');
    assert.deepEqual(startup.tools,['query_symbols','check_diagnostics','health_check']);
    expectedFetchHashes.push(freshPreflight.calibration.payloadSha256);
    await activeSession.prompt(prompt,{source:'rpc',expandPromptTemplates:false});
    assert.equal(policy.report().outcome,'FINAL_ANSWER',activeSession.agent.state.messages.at(-1)?.errorMessage);assert.equal(fetchCalls,1);assert.equal(socketSends,0);
    await activeSession.prompt('Intentionally denied second synthetic request.',{source:'rpc',expandPromptTemplates:false});
    assert.equal(records.filter(e=>e.type==='provider_admission').length,2);
    assert.equal(records.filter(e=>e.type==='provider_admission').at(-1).decision.allow,false);
    assert.equal(fetchCalls,1,'Denied second request must not reach fetch');assert.equal(socketSends,0);
    activeSession.dispose();activeSession=undefined;cleanupSocket(manager.getSessionId());
    return startup;
  });
  // Restore a real private SDK transcript at the already received product reply.
  // The provider receives canonical text once; duplicate details stay archived.
  const deliveryFixture=JSON.parse(fs.readFileSync(new URL('./pi-fixtures/query-delivery-cases.json',import.meta.url),'utf8')).cases.rejectedProof;
  const content=[{type:'text',text:JSON.stringify(deliveryFixture.result)}];
  const savedResult={role:'toolResult',toolCallId:'saved-call',toolName:'query_symbols',content,isError:true,details:{duplicate:deliveryFixture.result,omitted:'SDK_OMITTED_METADATA_'.repeat(12000)},timestamp:3};
  const saved=await privateStage('received-source',undefined,async manager=>{
    manager.appendMessage({role:'user',content:[{type:'text',text:'Find callers for the fixed fixture target.'}],timestamp:1});
    manager.appendMessage({role:'assistant',provider:'openai-codex',model:'gpt-6.1-sol',api:'openai-codex-responses',content:[{type:'toolCall',id:'saved-call',name:'query_symbols',arguments:{request:{type:'RUN'}}}],stopReason:'toolUse',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}},timestamp:2});
    manager.appendMessage(savedResult);
    return {sourceFile:manager.getSessionFile(),context:structuredClone(manager.buildSessionContext().messages)};
  });
  const savedSourceHash=sha(fs.readFileSync(saved.sourceFile));
  const makeSavedPolicy=(overrides={})=>{
    const result=new CasePolicy({...makePolicy().config,delivery:{reportedTokens:64000,requests:1,outputReserve:2000},evidenceOnlyDelivery:true,...overrides});
    assert.equal(result.restoreReceivedResult(decodeEnvelope(content),Buffer.byteLength(content[0].text)).allow,true);
    return result;
  };
  const resume=async(session,manager)=>{
    assert.deepEqual(manager.buildSessionContext().messages,saved.context);
    assert.deepEqual(session.agent.state.messages,saved.context);
    await driveSession(session,{mode:'received-result'});
  };
  const savedPreflight=await preflight('restored',saved.sourceFile,makeSavedPolicy,resume);
  assert.equal(savedPreflight.structure.depth,35,'Restored declarations preserve their complete inline schema');
  assert.equal(savedPreflight.structure.nodes,3908);
  const savedPolicy=makeSavedPolicy(),savedRecords=[];

  await privateStage('restored-admitted',saved.sourceFile,async manager=>{
    const created=await createEvaluationSession({sdk,plan,directory,modelRuntime:runtime,policy:savedPolicy,record:e=>savedRecords.push(e),manager});
    if(created.type==='failure')return created;
    activeSession=created.session;
    expectedFetchHashes.push(savedPreflight.calibration.payloadSha256);
    await resume(activeSession,manager);activeSession=undefined;cleanupSocket(manager.getSessionId());
  });
  assert.equal(sha(fs.readFileSync(saved.sourceFile)),savedSourceHash,'Replay must preserve the received source archive');
  const projected=bodies.at(-1),admission=savedPolicy.report().requestObservations[0];
  assert.equal(admission.allow,true,JSON.stringify(admission));assert.equal(admission.calibrationSourceSha256,null,'No calibration receipt is needed for bounded novel input');assert.equal(admission.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');
  assert.equal(fetchCalls,2,'Saved result continuation makes one admitted request');
  assert.equal(projected.input.filter(item=>item.type==='function_call_output').length,1);
  assert.equal(projected.input.find(item=>item.type==='function_call_output').output,content[0].text);
  assert.equal(JSON.stringify(projected).includes('SDK_OMITTED_METADATA_'),false);
  assert.equal(projected.input.filter(item=>item.role==='user').length,1,'Continuation adds no coached user prompt');
  assert.equal(admission.inputBytes,Buffer.byteLength(JSON.stringify(projected.input)));
  assert.equal(admission.outputTokenCap,2000);
  assert.equal(savedPolicy.report().nativeOutcome,'REJECTED');assert.equal(savedPolicy.report().exhaustiveEvidence,false);
  assert.equal(savedPolicy.report().semanticRepliesObserved,0);assert.equal(savedPolicy.report().modelProposals.length,0);
  assert.equal(savedPolicy.report().finalAnswer,true);assert.equal(savedPolicy.report().outcome,'INTENTIONAL_REJECTION');
  const savedProjection={storedContextBytes:Buffer.byteLength(JSON.stringify(saved.context)),providerInputBytes:admission.inputBytes,providerContextBytes:admission.contextBytes,declarationInputBytes:admission.declarationInputBytes,requiredEstimate:admission.required,budget:admission.remaining,outputTokenCap:projected.max_output_tokens,newUserMessages:0,semanticToolCalls:0,sourceArchiveUnchanged:true};
  const calibrationProof=(preflight,report)=>({qualification:'SYNTHETIC_FINALIZED_USAGE',payloadSha256:preflight.calibration.payloadSha256,calibrationSourceSha256:preflight.calibration.sourceSha256,structure:preflight.structure,
    calibratedInputCeiling:preflight.calibration.calibratedInputCeiling,boundQualification:preflight.calibration.qualification,
    oversizeAdmission:preflight.report.requestObservations[0]??{allow:false,reason:preflight.report.outcome},oversizeTransports:{fetchCalls:0,socketSends:0},
    admittedWithoutCalibration:report.requestObservations[0],admittedTransports:{fetchCalls:1,socketSends:0},finalizedUsage:report.usage});
  // Reproduce the installed SDK's cached-WebSocket abort defect independently
  // using its real provider and guard, with synthetic transport only. This is
  // why the committed isolated worker selects supported SSE explicitly.
  const legacySettings=sdk.SettingsManager.inMemory({transport:'websocket',cacheWarming:'off',compaction:{enabled:false},retry:{enabled:false}});
  const legacyManager=sdk.SessionManager.inMemory(directory),legacyPolicy=makePolicy(),legacyRecords=[];

  const {evaluationGuard}=await import('./pi_evaluation_guard.mjs');
  const loader=new sdk.DefaultResourceLoader({cwd:directory,agentDir:directory,settingsManager:legacySettings,noExtensions:true,additionalExtensionPaths:[kastAdapter],extensionFactories:[evaluationGuard(legacyPolicy,e=>legacyRecords.push(e))],noSkills:true,noPromptTemplates:true,noThemes:true,noContextFiles:true});
  await loader.reload();assert.equal(loader.getExtensions().errors.length,0);
  const legacy=await sdk.createAgentSession({cwd:directory,agentDir:directory,settingsManager:legacySettings,resourceLoader:loader,modelRuntime:runtime,model:runtime.getPhysicalModel('openai-codex','gpt-6.1-sol'),thinkingLevel:'high',tools:['query_symbols','check_diagnostics','health_check'],sessionManager:legacyManager});
  activeSession=legacy.session;await activeSession.bindExtensions({mode:'rpc',abortHandler:()=>activeSession.agent.abort()});
  await activeSession.prompt(prompt,{source:'rpc',expandPromptTemplates:false});
  assert.equal(socketSends,1);assert.equal(sockets,1);
  assert.equal(inspectProviderPayload(socketBodies[0],4194304).payloadSha256,freshPreflight.calibration.payloadSha256);
  await activeSession.prompt('Denied second synthetic WebSocket request.',{source:'rpc',expandPromptTemplates:false});
  assert.equal(socketSends,2,'Cached socket currently sends before observing the abort');assert.equal(sockets,1,'Regression must reuse the existing connection');
  assert.equal(legacyRecords.filter(e=>e.type==='provider_admission').length,2);
  assert.equal(legacyRecords.filter(e=>e.type==='provider_admission').at(-1).decision.allow,false);
  for(const report of [policy.report(),savedPolicy.report(),legacyPolicy.report()])assert.equal(report.modelProposals.length,0);
  activeSession.dispose();activeSession=undefined;cleanupSocket(legacyManager.getSessionId());
  assert.equal(expectedFetchHashes.length,0);
  // A complete real-SDK fresh -> newly generated tool result -> final-answer
  // sequence. Preserve the actual adapter declarations; replace only execute
  // capabilities at this test boundary, and fail if any other tool is invoked.
  const canonicalNegative={type:'complete',document:JSON.parse(fs.readFileSync(new URL('./pi-fixtures/complete-document.json',import.meta.url),'utf8'))};
  const canonicalText=JSON.stringify(canonicalNegative);
  const dynamicPolicy=makePolicy({work:{reportedTokens:30000,requests:2,tools:1,outputReserve:2000},maximumProviderRequests:3});
  const dynamicRecords=[],dynamicBodies=[];let syntheticToolCalls=0;
  const dynamicSdk={...observingSdk(dynamicBodies),createAgentSession:async options=>{
    const definitions=options.resourceLoader.getExtensions().extensions.flatMap(extension=>[...extension.tools.values()].map(tool=>tool.definition));
    assert.equal(definitions.filter(tool=>tool.name==='query_symbols').length,1);
    const customTools=definitions.map(definition=>({...definition,execute:async()=>{
      assert.equal(definition.name,'query_symbols','No other native capability may execute');
      syntheticToolCalls++;
      return {content:[{type:'text',text:canonicalText}],details:{qualification:'SYNTHETIC_CANONICAL_REPLY_NOT_NATIVE_PROOF'}};
    }}));
    return sdk.createAgentSession({...options,customTools});
  },DefaultResourceLoader:class extends sdk.DefaultResourceLoader {
    constructor(options){super({...options,extensionFactories:[...options.extensionFactories,pi=>{
      pi.on('before_provider_request',event=>{
        const body=structuredClone(event.payload);dynamicBodies.push(body);
        expectedFetchHashes.push(inspectProviderPayload(body,262144).payloadSha256);
      });
    }]});}
  }};
  const fetchBeforeDynamic=fetchCalls;
  await privateStage('fresh-dynamic-no-calibration',undefined,async manager=>{
    const created=await createEvaluationSession({sdk:dynamicSdk,plan,directory,modelRuntime:runtime,policy:dynamicPolicy,record:event=>dynamicRecords.push(event),manager});
    if(created.type==='failure')return created;
    activeSession=created.session;
    const callId=`synthetic-new-call-${fetchCalls+1}`;
    const item={id:'synthetic-new-function-id',type:'function_call',call_id:callId,name:'query_symbols',arguments:JSON.stringify({request:{type:'RUN',source:{type:'SYMBOL_REFS',symbolRefs:['exact:v2:synthetic-negative']}}}),status:'completed'};
    scriptedResponses.push([{type:'response.created',response:{id:'synthetic-new-tool-response'}},{type:'response.output_item.added',output_index:0,item:{...item,arguments:''}},{type:'response.output_item.done',output_index:0,item},{type:'response.completed',response:{id:'synthetic-new-tool-response',status:'completed',output:[item],usage:{input_tokens:100,output_tokens:10,total_tokens:110,input_tokens_details:{cached_tokens:0}}}}]);
    await activeSession.prompt('Find the exact synthetic negative target and describe its qualified result.',{source:'rpc',expandPromptTemplates:false});
    assert.equal(dynamicPolicy.report().outcome,'FINAL_ANSWER',JSON.stringify(dynamicPolicy.report()));
    assert.equal(fetchCalls-fetchBeforeDynamic,2);assert.equal(syntheticToolCalls,1);
    assert.equal(dynamicBodies.length,2);assert.notEqual(inspectProviderPayload(dynamicBodies[0],262144).payloadSha256,inspectProviderPayload(dynamicBodies[1],262144).payloadSha256);
    assert.deepEqual(dynamicBodies[1].tools,dynamicBodies[0].tools,'Complete original tool declarations remain unchanged');
    assert.equal(dynamicBodies[1].instructions,dynamicBodies[0].instructions);
    const outputs=dynamicBodies[1].input.filter(item=>item.type==='function_call_output');
    assert.equal(outputs.length,1);assert.equal(outputs[0].call_id,callId);assert.equal(outputs[0].output,canonicalText);
    assert.equal(dynamicBodies[1].input.filter(item=>item.role==='user').length,1);
    for(const observation of dynamicPolicy.report().requestObservations){assert.equal(observation.calibrationSourceSha256,null);assert.equal(observation.inputEstimateQualification,'CONSERVATIVE_ESTIMATE_UNCERTAIN_NOT_TOKENIZER_OR_SPEND_PROOF');assert.equal(observation.outputTokenCap,2000);assert.ok(observation.payloadBytes<=262144);}
    activeSession.dispose();activeSession=undefined;cleanupSocket(manager.getSessionId());
  });
  assert.equal(expectedFetchHashes.length,0);assert.equal(scriptedResponses.length,0);
  const dynamicSequence={qualification:'REAL_INSTALLED_SDK_SYNTHETIC_TRANSPORT_AND_CANONICAL_TOOL_REPLY_NOT_NATIVE_OR_BACKEND_PROOF',priorCalibrationReceipts:0,admittedSseFetchCalls:fetchCalls-fetchBeforeDynamic,syntheticToolExecutions:syntheticToolCalls,newCallIdAndCanonicalResultPreserved:true,fullDeclarationsPreserved:true,newUserMessages:1,report:dynamicPolicy.report()};
  const identity={};
  for(const [name,files] of Object.entries({'pi-coding-agent':['dist/core/sdk.js','dist/core/session-manager.js','dist/core/resource-loader.js','dist/core/extensions/runner.js'],'pi-ai':['dist/api/openai-codex-responses.js'],'pi-agent-core':['dist/agent.js']})) {
    const root=name==='pi-coding-agent'?piPackageRoot:path.join(piPackageRoot,'..',name);
    identity[name]={version:JSON.parse(fs.readFileSync(path.join(root,'package.json'))).version,sha256:Object.fromEntries(['package.json',...files].map(file=>[file,sha(fs.readFileSync(path.join(root,file)))]))};
  }
  console.log(JSON.stringify({mode:'NO_INFERENCE_REAL_SDK_SYNTHETIC_TRANSPORT',startup,dynamicSequence,adapterSha256:expectedAdapterSha256,identity,savedProjection,calibrationProof:{fresh:calibrationProof(freshPreflight,policy.report()),restored:calibrationProof(savedPreflight,savedPolicy.report())},sse:{admittedFetchCalls:fetchCalls,deniedSecondFetchCalls:0,socketSends:0},cachedWebSocket:{connections:sockets,admittedSends:1,deniedSecondSends:1},semanticToolCalls:0,realProviderRequests:0},null,2));
} finally {activeSession?.dispose();cleanupSocket?.();globalThis.fetch=originals.fetch;globalThis.WebSocket=originals.WebSocket;fs.rmSync(directory,{recursive:true,force:true});}
