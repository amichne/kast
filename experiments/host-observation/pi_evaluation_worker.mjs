import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { CasePolicy, Outcome, decodeEnvelope } from './pi_evaluation_policy.mjs';
import { evaluationGuard } from './pi_evaluation_guard.mjs';
import { driveSession } from './pi_evaluation_controller.mjs';
import { createEvaluationRecords, withPrivateReplaySession } from './pi_evaluation_records.mjs';
import { loadInputCalibrations } from './pi_evaluation_calibrations.mjs';
import { validatePlan } from './run_pi_evaluation.mjs';

const failed=value=>value?.type==='failure';
const failure=(operation,reason,stage='WORKER_SETUP')=>Object.freeze({type:'failure',
  failure:Object.freeze({stage,operation,reason})});
function effect(operation,run,stage='WORKER_SETUP') {
  try {return {type:'observed',value:run()};}
  catch {return failure(operation,'EFFECT_FAILED',stage);}
}
async function asyncEffect(operation,run,stage='WORKER_SETUP') {
  try {return {type:'observed',value:await run()};}
  catch {return failure(operation,'EFFECT_FAILED',stage);}
}

// Only the explicit --execute parent starts this effect boundary. Importing the
// policy, guard or controller tests cannot load Pi, authenticate or query Kast.
export function isolatedSettings(SettingsManager) {
  const created=effect('SETTINGS_CREATE',()=>SettingsManager.inMemory({defaultProvider:'openai-codex',defaultModel:'gpt-6.1-sol',defaultThinkingLevel:'high',transport:'sse',cacheWarming:'off',packages:[],extensions:[],enableInstallTelemetry:false,compaction:{enabled:false},retry:{enabled:false,maxRetries:0,provider:{maxRetries:0}}}));
  if(failed(created)) return created;
  const settings=created.value;
  // The installed cached-WebSocket implementation sends before checking an
  // abort. SSE checks before fetch. Background warming bypasses case accounting.
  const inspected=effect('SETTINGS_POLICY',()=>({transport:settings.getTransport(),warming:settings.getCacheWarmingMode()}));
  if(failed(inspected)) return inspected;
  if(inspected.value.transport!=='sse'||inspected.value.warming!=='off')return failure('SETTINGS_POLICY','SETTINGS_UNAVAILABLE');
  return {type:'settings',settingsManager:settings};
}

// Shared real-SDK construction boundary. A no-inference SDK check can supply
// synthetic in-memory auth/catalog storage; it cannot replace these rules.
export async function createEvaluationSession({sdk,plan,directory,modelRuntime,policy,record,manager}) {
  const {createAgentSession,SettingsManager,DefaultResourceLoader}=sdk;
  let observationFailure;
  const observeRecord=value=>{
    const result=record(value);
    if(failed(result)&&observationFailure===undefined)observationFailure=result;
    return result;
  };
  const settings=isolatedSettings(SettingsManager);if(failed(settings)) return settings;
  const {settingsManager}=settings;
  const found=effect('MODEL_LOOKUP',()=>modelRuntime.getPhysicalModel('openai-codex','gpt-6.1-sol'));
  if(failed(found)) return found;
  const model=found.value;
  if(!model || model.id!=='gpt-6.1-sol' || model.provider!=='openai-codex')return failure('MODEL_LOOKUP','MODEL_UNAVAILABLE');
  const resources=effect('RESOURCE_CREATE',()=>new DefaultResourceLoader({cwd:plan.workspaceRoot,agentDir:directory,settingsManager,noExtensions:true,additionalExtensionPaths:[plan.kastAdapter],extensionFactories:[evaluationGuard(policy,observeRecord)],noSkills:true,noPromptTemplates:true,noThemes:true,noContextFiles:true}));
  if(failed(resources)) return resources;
  const loader=resources.value;
  const loaded=await asyncEffect('RESOURCE_LOAD',()=>loader.reload());if(failed(loaded)) return loaded;
  const extensions=effect('RESOURCE_EXTENSIONS',()=>loader.getExtensions());if(failed(extensions)) return extensions;
  if(!Array.isArray(extensions.value?.errors)||extensions.value.errors.length!==0)return failure('RESOURCE_EXTENSIONS','EXTENSION_LOAD_FAILED');
  const created=await asyncEffect('SESSION_CREATE',()=>createAgentSession({cwd:plan.workspaceRoot,agentDir:directory,settingsManager,resourceLoader:loader,modelRuntime,model,thinkingLevel:'high',tools:['query_symbols','check_diagnostics','health_check'],sessionManager:manager}));
  if(failed(created)) return created;
  const session=created.value?.session;
  if(!session)return failure('SESSION_CREATE','SESSION_UNAVAILABLE');
  let result;
  const inspected=effect('SESSION_IDENTITY',()=>({fallback:created.value.modelFallbackMessage,
    effort:session.thinkingLevel,provider:session.model?.provider,model:session.model?.id}));
  if(failed(inspected)) result=inspected;
  else if(inspected.value.fallback || inspected.value.effort!=='high' || inspected.value.provider!=='openai-codex'
      || inspected.value.model!=='gpt-6.1-sol')result=failure('SESSION_IDENTITY','MODEL_EFFORT_UNAVAILABLE');
  else {
    const bound=await asyncEffect('EXTENSIONS_BIND',()=>session.bindExtensions({mode:'rpc',abortHandler:()=>session.agent.abort()}));
    result=observationFailure??(failed(bound)?bound:{type:'session',session,settingsManager});
  }
  if(failed(result)) effect('SESSION_DISPOSE',()=>session.dispose());
  return result;
}

export async function worker(planFile,index) {
  const read=effect('PLAN_READ',()=>fs.readFileSync(planFile,'utf8'));if(failed(read)) return read;
  const decoded=effect('PLAN_DECODE',()=>validatePlan(JSON.parse(read.value)));if(failed(decoded)) return decoded;
  const plan=decoded.value,item=plan.cases[index];
  if(!Number.isSafeInteger(index)||index<0||!item||typeof process.send!=='function')return failure('WORKER_OWNERSHIP','OWNED_WORKER_REQUIRED');
  const directory=path.join(plan.outputRoot,item.name);
  const made=effect('OUTPUT_DIRECTORY',()=>fs.mkdirSync(directory,{mode:0o700}));if(failed(made)) return made;
  const opened=effect('RECORDS_CREATE',()=>createEvaluationRecords(directory));if(failed(opened)) return opened;
  const records=opened.value;
  if(records.type==='failure') return records;
  let firstFailure;
  const retain=value=>{if(failed(value)&&firstFailure===undefined)firstFailure=value;return value;};
  const record=value=>retain(records.guard(value));
  const lifecycle=(stage,outcome)=>retain(records.lifecycle(stage,outcome));
  let result;
  let stage='WORKER_SETUP';
  try {
    const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
    const run=async()=>{
      const adapter=effect('ADAPTER_READ',()=>fs.readFileSync(plan.kastAdapter));if(failed(adapter))return adapter;
      const version=effect('INSTALLATION_READ',()=>JSON.parse(fs.readFileSync(path.join(plan.piPackageRoot,'package.json'))).version);
      if(failed(version))return version;
      if(sha(adapter.value)!==plan.kastAdapterSha256||version.value!==plan.piVersion)return failure('INSTALLATION_PIN','PIN_MISMATCH');
      const policy=new CasePolicy(item);
      stage='INPUT_CALIBRATION';
      const calibration=retain(loadInputCalibrations(item.inputCalibrations,policy));
      const calibrationSignal=lifecycle('INPUT_CALIBRATION',failed(calibration)?'FAILED':'PREPARED');
      if(failed(calibration)||failed(calibrationSignal))return firstFailure;
      stage='WORKER_SETUP';
      const protectedPaths=[plan.authPath,plan.modelsStorePath];
      const protectedRead=effect('PROTECTED_READ',()=>protectedPaths.map(p=>fs.readFileSync(p)));
      if(failed(protectedRead))return protectedRead;
      const before=protectedRead.value.map(sha);
      const decodedCredential=effect('CREDENTIAL_DECODE',()=>JSON.parse(protectedRead.value[0].toString('utf8'))['openai-codex']);
      if(failed(decodedCredential))return decodedCredential;
      const credential=decodedCredential.value;
      if(credential?.type!=='oauth'||!Number.isFinite(credential.expires)||credential.expires-Date.now()<15*60*1000)
        return failure('CREDENTIAL_VALIDITY','CREDENTIAL_UNAVAILABLE');
      const loaded=await asyncEffect('SDK_LOAD',async()=>{
        const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
        return {sdk:await load('dist/index.js'),auth:await load('dist/core/auth-storage.js'),
          transport:await import(pathToFileURL(path.join(plan.piPackageRoot,'../pi-ai/dist/api/openai-codex-responses.js')).href)};
      });
      if(failed(loaded))return loaded;
      const {sdk,auth,transport}=loaded.value;
      const runtime=await asyncEffect('MODEL_RUNTIME',()=>sdk.ModelRuntime.create({credentials:new auth.ReadOnlyAuthStorage(plan.authPath),modelsPath:null,modelsStorePath:plan.modelsStorePath,allowModelNetwork:false}));
      if(failed(runtime))return runtime;
      stage='REPLAY_ARCHIVE';
      const archive=await withPrivateReplaySession(sdk.SessionManager,{directory,workspaceRoot:plan.workspaceRoot,
        sourceFile:item.mode==='received-result'?item.receivedSessionFile:undefined},async manager=>{
        const runSession=async()=>{
        const replaySignal=lifecycle('REPLAY_ARCHIVE',item.mode==='received-result'?'RESTORED':'FRESH');
        if(failed(replaySignal))return replaySignal;
        stage='WORKER_SETUP';
        if(item.mode==='received-result') {
          const identity=effect('RECEIVED_WORKSPACE',()=>manager.getCwd());if(failed(identity))return identity;
          if(identity.value!==plan.workspaceRoot)return failure('RECEIVED_WORKSPACE','WORKSPACE_MISMATCH');
          const entries=effect('RECEIVED_ENTRY',()=>manager.getEntries().find(e=>e.id===item.receivedResultEntryId));
          if(failed(entries))return entries;
          const received=entries.value;
          if(received?.message?.role!=='toolResult'||received.message.toolName!=='query_symbols')return failure('RECEIVED_ENTRY','RECEIVED_RESULT_UNAVAILABLE');
          const context=effect('RECEIVED_BRANCH',()=>{manager.branch(received.id);return manager.buildSessionContext().messages;});
          if(failed(context))return context;
          if(context.value.at(-1)?.role!=='toolResult'||sha(JSON.stringify(context.value))!==item.receivedContextSha256)
            return failure('RECEIVED_CONTEXT','CONTEXT_MISMATCH');
          const receivedSignal=record({type:'received_context',contextSha256:sha(JSON.stringify(context.value)),toolResultSha256:sha(JSON.stringify(received.message)),newUserMessages:0});
          if(failed(receivedSignal))return receivedSignal;
          // Continuation uses its separate declared delivery allowance.
          const restored=effect('RECEIVED_POLICY',()=>policy.restoreReceivedResult(decodeEnvelope(received.message.content),received.message.content.reduce((sum,block)=>sum+(block.type==='text'?Buffer.byteLength(block.text):0),0)));
          if(failed(restored))return restored;
          if(!restored.value.allow)return failure('RECEIVED_POLICY','RECEIVED_RESULT_REJECTED');
        }
        const created=await createEvaluationSession({sdk,plan,directory,modelRuntime:runtime.value,policy,record,manager});
        if(failed(created))return created;
        const {session,settingsManager}=created;
        let sessionResult;
        try {
          if(firstFailure)sessionResult=firstFailure;
          else if(item.mode==='received-result'&&sha(JSON.stringify(session.agent.state.messages))!==item.receivedContextSha256)
            sessionResult=failure('RESTORED_CONTEXT','CONTEXT_MISMATCH');
          else {
            const subscribed=effect('EVENT_SUBSCRIBE',()=>session.subscribe(event=>{
              if(failed(retain(records.event(event))))retain(effect('SESSION_ABORT',()=>session.agent.abort(),stage));
            }));
            if(failed(subscribed))sessionResult=subscribed;
            else {
              const setupSignal=lifecycle('WORKER_SETUP','PREPARED');
              if(failed(setupSignal))sessionResult=setupSignal;
              else {
                stage='WORKER_EXECUTION';
                const cancel=()=>{policy.stop(Outcome.CANCELLED);retain(effect('SESSION_ABORT',()=>session.agent.abort(),stage));};
                const message=m=>{if(m?.type==='cancel')cancel();};
                process.on('message',message);process.once('SIGTERM',cancel);
                const timer=setTimeout(cancel,item.wallSeconds*1000);
                try {
                  const driven=await asyncEffect('SESSION_DRIVE',()=>driveSession(session,{mode:item.mode,prompt:item.prompt}),stage);
                  if(failed(driven))sessionResult=retain(driven);
                } finally {clearTimeout(timer);process.removeListener('message',message);process.removeListener('SIGTERM',cancel);}
              }
            }
          }
        } catch {sessionResult=retain(failure('SESSION_BOUNDARY','EFFECT_FAILED',stage));}
        finally {
          if(failed(sessionResult))retain(sessionResult);
          retain(effect('SESSION_DISPOSE',()=>session.dispose(),stage));
          retain(effect('TRANSPORT_CLOSE',()=>transport.closeOpenAICodexWebSocketSessions(manager.getSessionId()),stage));
        }
        if(firstFailure)return firstFailure;
        const unchanged=effect('PROTECTED_VERIFY',()=>protectedPaths.every((p,i)=>sha(fs.readFileSync(p))===before[i]),stage);
        if(failed(unchanged))return unchanged;
        const reportEffect=effect('REPORT_CREATE',()=>({...policy.report(),sessionId:manager.getSessionId(),adapterSha256:plan.kastAdapterSha256,piVersion:plan.piVersion,transport:settingsManager.getTransport(),cacheWarming:settingsManager.getCacheWarmingMode(),workspaceRoot:plan.workspaceRoot,protectedFilesUnchanged:unchanged.value,inputCalibrationProvenance:calibration.provenance}),stage);
        if(failed(reportEffect))return reportEffect;
        const report=reportEffect.value;if(!unchanged.value)report.outcome='HARNESS_PROTECTED_FILE_CHANGED';
        const written=effect('REPORT_WRITE',()=>fs.writeFileSync(path.join(directory,'report.json'),JSON.stringify(report,null,2)+'\n',{mode:0o600}),stage);
        if(failed(written))return written;
        const completedSignal=lifecycle('WORKER_EXECUTION','COMPLETED');if(failed(completedSignal))return completedSignal;
        const observationStatus=retain(records.status());if(failed(observationStatus))return observationStatus;
        return {type:'report',report};
        };
        return retain(await runSession());
      });
      return archive.type==='completed'?archive.value:archive;
    };
    result=retain(await run());
  } catch {result=retain(failure('WORKER_BOUNDARY','EFFECT_FAILED',stage));}
  finally {
    if(firstFailure)lifecycle(stage,'FAILED');
    retain(records.close());
  }
  return firstFailure??result;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  worker(process.argv[2],Number(process.argv[3])).then(result=>{
    if(result.type==='report') {process.send?.(result);process.exit(0);}
    else {process.send?.({type:'worker_failure',failure:result.failure});process.exit(1);}
  }).catch(()=>{
    process.send?.({type:'worker_failure',failure:failure('WORKER_BOUNDARY','EFFECT_FAILED').failure});process.exit(1);
  });
}
