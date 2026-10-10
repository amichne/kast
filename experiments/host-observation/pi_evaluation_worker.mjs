import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { CasePolicy, Outcome } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import { driveSession } from './pi_evaluation_controller.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';

// Only the explicit --execute parent starts this effect boundary. Importing the
// policy, guard or controller tests cannot load Pi, authenticate or query Kast.
async function worker(planFile,index) {
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
  const {createAgentSession,SessionManager,SettingsManager,DefaultResourceLoader,ModelRuntime}=await load('dist/index.js');
  const {ReadOnlyAuthStorage}=await load('dist/core/auth-storage.js');
  const {closeOpenAICodexWebSocketSessions}=await import(pathToFileURL(path.join(plan.piPackageRoot,'../pi-ai/dist/api/openai-codex-responses.js')).href);
  const policy=new CasePolicy(item);
  const record=value=>fs.appendFileSync(path.join(directory,'guard.jsonl'),JSON.stringify(value)+'\n',{mode:0o600});
  const settingsManager=SettingsManager.inMemory({defaultProvider:'openai-codex',defaultModel:'gpt-6.1-sol',defaultThinkingLevel:'high',packages:[],extensions:[],enableInstallTelemetry:false,compaction:{enabled:false},retry:{enabled:false,maxRetries:0,provider:{maxRetries:0}}});
  const modelRuntime=await ModelRuntime.create({credentials:new ReadOnlyAuthStorage(plan.authPath),modelsPath:null,modelsStorePath:plan.modelsStorePath,allowModelNetwork:false});
  const model=modelRuntime.getPhysicalModel('openai-codex','gpt-6.1-sol');if(!model)throw Error('Exact model unavailable');
  const loader=new DefaultResourceLoader({cwd:plan.workspaceRoot,agentDir:directory,settingsManager,noExtensions:true,additionalExtensionPaths:[plan.kastAdapter],extensionFactories:[evaluationGuard(policy,record)],noSkills:true,noPromptTemplates:true,noThemes:true,noContextFiles:true});
  await loader.reload();if(loader.getExtensions().errors.length)throw Error('Pinned extension load failed');
  let manager;
  if(item.mode==='received-result') {
    manager=SessionManager.open(item.receivedSessionFile);
    const result=manager.getEntries().find(e=>e.id===item.receivedResultEntryId);
    if(result?.message?.role!=='toolResult'||result.message.toolName!=='query_symbols')throw Error('Exact saved received result unavailable');
    manager.branch(result.id);
    const context=manager.buildSessionContext().messages;
    if(context.at(-1)?.role!=='toolResult')throw Error('Received-result context lost');
    record({type:'received_context',sessionId:manager.getSessionId(),contextSha256:sha(JSON.stringify(context)),toolResultSha256:sha(JSON.stringify(result.message)),newUserMessages:0});
    // Continuation starts with a separate explicitly declared delivery allowance.
    // It cannot replay the saved semantic request or issue a new tool call.
    policy.phase='DELIVERY';
    policy.contextGrowthCeiling=Buffer.byteLength(JSON.stringify(result.message));
  } else manager=SessionManager.create(plan.workspaceRoot,path.join(directory,'sessions'));
  const {session,modelFallbackMessage}=await createAgentSession({cwd:plan.workspaceRoot,agentDir:directory,settingsManager,resourceLoader:loader,modelRuntime,model,thinkingLevel:'high',tools:['query_symbols','check_diagnostics','health_check'],sessionManager:manager});
  if(modelFallbackMessage||session.thinkingLevel!=='high')throw Error('Exact model/effort unavailable');
  await session.bindExtensions({mode:'rpc',abortHandler:()=>session.agent.abort()});
  session.subscribe(event=>fs.appendFileSync(path.join(directory,'events.jsonl'),JSON.stringify(event)+'\n',{mode:0o600}));
  const cancel=()=>{policy.stop(Outcome.CANCELLED);session.agent.abort();};
  process.on('message',m=>{if(m.type==='cancel')cancel();});
  process.once('SIGTERM',cancel);
  const timer=setTimeout(cancel,item.wallSeconds*1000);
  try {await driveSession(session,{mode:item.mode,prompt:item.prompt});}
  finally {clearTimeout(timer);session.dispose();closeOpenAICodexWebSocketSessions(manager.getSessionId());}
  const protectedUnchanged=protectedPaths.every((p,i)=>sha(fs.readFileSync(p))===before[i]);
  const report={...policy.report(),sessionId:manager.getSessionId(),adapterSha256:plan.kastAdapterSha256,piVersion:plan.piVersion,workspaceRoot:plan.workspaceRoot,protectedFilesUnchanged:protectedUnchanged};
  if(!protectedUnchanged)report.outcome='HARNESS_PROTECTED_FILE_CHANGED';
  fs.writeFileSync(path.join(directory,'report.json'),JSON.stringify(report,null,2)+'\n',{mode:0o600});
  process.send({type:'report',report});
}
worker(process.argv[2],Number(process.argv[3])).then(()=>process.exit(0)).catch(()=>{process.send?.({type:'worker_failure',reason:'PI_EVALUATION_SETUP_OR_EXECUTION_FAILED'});process.exit(1);});
