import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { CasePolicy, Outcome, decodeEnvelope } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import { driveSession } from './pi_evaluation_controller.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';

// Only the explicit --execute parent starts this effect boundary. Importing the
// policy, guard or controller tests cannot load Pi, authenticate or query Kast.
export function isolatedSettings(SettingsManager) {
  const settings=SettingsManager.inMemory({defaultProvider:'openai-codex',defaultModel:'gpt-6.1-sol',defaultThinkingLevel:'high',transport:'sse',cacheWarming:'off',packages:[],extensions:[],enableInstallTelemetry:false,compaction:{enabled:false},retry:{enabled:false,maxRetries:0,provider:{maxRetries:0}}});
  // The installed cached-WebSocket implementation sends before checking an
  // abort. SSE checks before fetch. Background warming bypasses case accounting.
  if(settings.getTransport()!=='sse'||settings.getCacheWarmingMode()!=='off')throw Error('Isolated transport/warming policy unavailable');
  return settings;
}

// Shared real-SDK construction boundary. A no-inference SDK check can supply
// synthetic in-memory auth/catalog storage; it cannot replace these rules.
export async function createEvaluationSession({sdk,plan,directory,modelRuntime,policy,record,manager}) {
  const {createAgentSession,SettingsManager,DefaultResourceLoader}=sdk;
  const settingsManager=isolatedSettings(SettingsManager);
  const model=modelRuntime.getPhysicalModel('openai-codex','gpt-6.1-sol');if(!model)throw Error('Exact model unavailable');
  const loader=new DefaultResourceLoader({cwd:plan.workspaceRoot,agentDir:directory,settingsManager,noExtensions:true,additionalExtensionPaths:[plan.kastAdapter],extensionFactories:[evaluationGuard(policy,record)],noSkills:true,noPromptTemplates:true,noThemes:true,noContextFiles:true});
  await loader.reload();if(loader.getExtensions().errors.length)throw Error('Pinned extension load failed');
  const {session,modelFallbackMessage}=await createAgentSession({cwd:plan.workspaceRoot,agentDir:directory,settingsManager,resourceLoader:loader,modelRuntime,model,thinkingLevel:'high',tools:['query_symbols','check_diagnostics','health_check'],sessionManager:manager});
  if(modelFallbackMessage||session.thinkingLevel!=='high')throw Error('Exact model/effort unavailable');
  await session.bindExtensions({mode:'rpc',abortHandler:()=>session.agent.abort()});
  return {session,settingsManager};
}

export async function worker(planFile,index) {
  const plan=validatePlan(JSON.parse(fs.readFileSync(planFile,'utf8'))),item=plan.cases[index];
  if(!item||!process.send) throw Error('Owned worker/case required');
  const directory=path.join(plan.outputRoot,item.name);fs.mkdirSync(directory,{mode:0o700});
  const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
  if(sha(fs.readFileSync(plan.kastAdapter))!==plan.kastAdapterSha256||JSON.parse(fs.readFileSync(path.join(plan.piPackageRoot,'package.json'))).version!==plan.piVersion) throw Error('Pinned installation mismatch');
  const protectedPaths=[plan.authPath,plan.modelsStorePath];
  const before=protectedPaths.map(p=>sha(fs.readFileSync(p)));
  const credential=JSON.parse(fs.readFileSync(plan.authPath,'utf8'))['openai-codex'];
  if(credential?.type!=='oauth'||credential.expires-Date.now()<15*60*1000) throw Error('Existing credential validity insufficient; no login/refresh permitted');
  const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
  const sdk=await load('dist/index.js');
  const {SessionManager,ModelRuntime}=sdk;
  const {ReadOnlyAuthStorage}=await load('dist/core/auth-storage.js');
  const {closeOpenAICodexWebSocketSessions}=await import(pathToFileURL(path.join(plan.piPackageRoot,'../pi-ai/dist/api/openai-codex-responses.js')).href);
  const policy=new CasePolicy(item);
  const record=value=>fs.appendFileSync(path.join(directory,'guard.jsonl'),JSON.stringify(value)+'\n',{mode:0o600});
  const modelRuntime=await ModelRuntime.create({credentials:new ReadOnlyAuthStorage(plan.authPath),modelsPath:null,modelsStorePath:plan.modelsStorePath,allowModelNetwork:false});
  let manager;
  if(item.mode==='received-result') {
    manager=SessionManager.open(item.receivedSessionFile);
    if(manager.getCwd()!==plan.workspaceRoot)throw Error('Saved workspace identity mismatch');
    const result=manager.getEntries().find(e=>e.id===item.receivedResultEntryId);
    if(result?.message?.role!=='toolResult'||result.message.toolName!=='query_symbols')throw Error('Exact saved received result unavailable');
    manager.branch(result.id);
    const context=manager.buildSessionContext().messages;
    if(context.at(-1)?.role!=='toolResult'||sha(JSON.stringify(context))!==item.receivedContextSha256)throw Error('Received-result context lost or changed');
    record({type:'received_context',sessionId:manager.getSessionId(),contextSha256:sha(JSON.stringify(context)),toolResultSha256:sha(JSON.stringify(result.message)),newUserMessages:0});
    // Continuation starts with a separate explicitly declared delivery allowance.
    // It cannot replay the saved semantic request or issue a new tool call.
    const restored=policy.restoreReceivedResult(decodeEnvelope(result.message.content),result.message.content.reduce((sum,block)=>sum+(block.type==='text'?Buffer.byteLength(block.text):0),0));
    if(!restored.allow)throw Error('Saved result cannot be interpreted under this plan');
  } else manager=SessionManager.create(plan.workspaceRoot,path.join(directory,'sessions'));
  const {session,settingsManager}=await createEvaluationSession({sdk,plan,directory,modelRuntime,policy,record,manager});
  if(item.mode==='received-result'&&sha(JSON.stringify(session.agent.state.messages))!==item.receivedContextSha256)throw Error('SDK restoration changed the received context');
  session.subscribe(event=>fs.appendFileSync(path.join(directory,'events.jsonl'),JSON.stringify(event)+'\n',{mode:0o600}));
  const cancel=()=>{policy.stop(Outcome.CANCELLED);session.agent.abort();};
  process.on('message',m=>{if(m.type==='cancel')cancel();});
  process.once('SIGTERM',cancel);
  const timer=setTimeout(cancel,item.wallSeconds*1000);
  try {await driveSession(session,{mode:item.mode,prompt:item.prompt});}
  finally {clearTimeout(timer);session.dispose();closeOpenAICodexWebSocketSessions(manager.getSessionId());}
  const protectedUnchanged=protectedPaths.every((p,i)=>sha(fs.readFileSync(p))===before[i]);
  const report={...policy.report(),sessionId:manager.getSessionId(),adapterSha256:plan.kastAdapterSha256,piVersion:plan.piVersion,transport:settingsManager.getTransport(),cacheWarming:settingsManager.getCacheWarmingMode(),workspaceRoot:plan.workspaceRoot,protectedFilesUnchanged:protectedUnchanged};
  if(!protectedUnchanged)report.outcome='HARNESS_PROTECTED_FILE_CHANGED';
  fs.writeFileSync(path.join(directory,'report.json'),JSON.stringify(report,null,2)+'\n',{mode:0o600});
  process.send({type:'report',report});
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) worker(process.argv[2],Number(process.argv[3])).then(()=>process.exit(0)).catch(()=>{process.send?.({type:'worker_failure',reason:'PI_EVALUATION_SETUP_OR_EXECUTION_FAILED'});process.exit(1);});
