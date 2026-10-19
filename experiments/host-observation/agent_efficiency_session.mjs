// Pi owns tools, MCP, discovery, approval, execution, and session lifetime.
// This adapter supplies explicit experiment inputs and records observed events.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { runOwnedWorker, WorkerDeadlineEnvironmentKey } from './pi_evaluation_controller.mjs';
import { admitProviderRequest } from './agent_efficiency_limits.mjs';
import { projectCall, projectResult, createReferenceExposureAudit, projectProviderTiming } from './agent_efficiency_audit.mjs';

const fields = ['version','workspaceRoot','outputRoot','piPackageRoot','piVersion','authPath',
  'provider','model','effort','arm','exposure','mcpCommand','onboarding','skills','prompt',
  'maximumRequests','maximumReportedTokens','maximumPayloadBytes','maximumOutputTokens',
  'maximumTools','wallSeconds'];
const reject = reason => ({type:'rejected',reason});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const positive = value => Number.isSafeInteger(value) && value > 0;
export function admitSessionPlan(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || Object.keys(value).length !== fields.length || fields.some(key=>!Object.hasOwn(value,key)))
    return reject('PLAN_SHAPE');
  if (value.version!==1 || !['control','treatment'].includes(value.arm)
      || !['codemode','deferred','direct'].includes(value.exposure)
      || value.provider!=='openai-codex' || value.model!=='gpt-6.1-sol' || value.effort!=='high')
    return reject('IDENTITY');
  if (['workspaceRoot','outputRoot','piPackageRoot','authPath','mcpCommand'].some(key=>
    typeof value[key]!=='string' || !path.isAbsolute(value[key]) || /[\x00-\x1f]/.test(value[key])))
    return reject('PATH');
  if (typeof value.piVersion!=='string' || !value.piVersion.length
      || typeof value.prompt!=='string' || !value.prompt.length
      || typeof value.onboarding!=='string' || !Array.isArray(value.skills)
      || value.skills.some(item=>typeof item!=='string'||!path.isAbsolute(item))) return reject('RESOURCE');
  if (['maximumRequests','maximumReportedTokens','maximumPayloadBytes','maximumOutputTokens',
    'maximumTools','wallSeconds'].some(key=>!positive(value[key])) || value.wallSeconds>600
      || value.maximumRequests>100 || value.maximumOutputTokens>16000) return reject('BOUNDS');
  if (value.arm==='control' && (value.onboarding!==''||value.skills.length)) return reject('CONTROL_CONTAMINATION');
  return {type:'admitted',plan:Object.freeze({...value,skills:Object.freeze([...value.skills])})};
}

export function finalizedUsage(value) {
  const names=['input','output','cacheRead','cacheWrite','totalTokens'];
  if (!value || names.some(key=>!Number.isSafeInteger(value[key])||value[key]<0)
      || value.totalTokens!==value.input+value.output+value.cacheRead+value.cacheWrite)
    return reject('USAGE_UNAVAILABLE');
  const cost=value.cost?.total;
  return {type:'usage',...Object.fromEntries(names.map(key=>[key,value[key]])),
    catalogCostUsd:typeof cost==='number'&&Number.isFinite(cost)&&cost>0?cost:null};
}

export function usageQualification(requests,events) {
  const responses=events.filter(event=>event.type==='assistant_end');
  return requests>0&&responses.length===requests&&responses.every(event=>
    event.usage?.type==='usage'&&['stop','length','toolUse'].includes(event.stopReason))
    ?'FINALIZED_PROVIDER_USAGE':'INCOMPLETE';
}

async function runSession(plan) {
  const started=performance.now();
  fs.mkdirSync(plan.outputRoot,{mode:0o700});
  const journal=path.join(plan.outputRoot,'journal.jsonl');
  fs.writeFileSync(journal,'',{mode:0o600,flag:'wx'});
  const audit=path.join(plan.outputRoot,'semantic-audit.jsonl');
  fs.writeFileSync(audit,'',{mode:0o600,flag:'wx'});
  const recordAudit=projection=>{
    if(projection!==null)fs.appendFileSync(audit,JSON.stringify(projection)+'\n');
  };
  const events=[],usage=[],toolTimes=new Map();
  const referenceExposure=createReferenceExposureAudit();
  const emptyProviderTiming=()=>({sdkDurationMillis:null,headersReceivedMillis:null,
    firstObservableDeltaMillis:null,serverCreatedAtSeconds:null,serverCompletedAtSeconds:null,
    providerProcessingMillis:null});
  let providerTiming=emptyProviderTiming();
  let session,setupMillis,reason='COMPLETED',requests=0,toolCalls=0,totalTokens=0;
  let payloadBytes=0,toolOutputBytes=0,retries=0,compactions=0;
  const write=(name,value)=>fs.writeFileSync(path.join(plan.outputRoot,name),JSON.stringify(value,null,2)+'\n',{mode:0o600,flag:'wx'});
  const event=value=>{
    const record={...value,elapsedMillis:performance.now()-started};
    fs.appendFileSync(journal,JSON.stringify(record)+'\n');
    events.push(record);
  };
  const fail=code=>{if(reason==='COMPLETED')reason=code;};
  const load=relative=>import(pathToFileURL(path.join(plan.piPackageRoot,relative)).href);
  const packageBytes=fs.readFileSync(path.join(plan.piPackageRoot,'package.json'));
  event({type:'stage',stage:'PACKAGE_ADMISSION'});
  if(JSON.parse(packageBytes).version!==plan.piVersion)throw Error('PI_VERSION_CHANGED');
  const authBefore=hash(fs.readFileSync(plan.authPath));
  const sdk=await load('dist/index.js');
  event({type:'stage',stage:'SDK_LOADED'});
  const auth=await load('dist/core/auth-storage.js');
  const modelRuntime=await sdk.ModelRuntime.create({credentials:new auth.ReadOnlyAuthStorage(plan.authPath),
    modelsPath:null,allowModelNetwork:false,refreshOnCreate:false});
  const model=modelRuntime.getPhysicalModel(plan.provider,plan.model);
  if(!model)throw Error('MODEL_UNAVAILABLE');
  const settingsManager=sdk.SettingsManager.inMemory({defaultProvider:plan.provider,defaultModel:plan.model,
    defaultThinkingLevel:plan.effort,defaultTools:['read','bash','edit','write','codemode','tool_search'],
    transport:'sse',cacheWarming:'off',packages:[],extensions:[],enableInstallTelemetry:false,
    compaction:{enabled:false},retry:{enabled:false,maxRetries:0,provider:{maxRetries:0}}});
  if(settingsManager.getTransport()!=='sse'||settingsManager.getCacheWarmingMode()!=='off')throw Error('SETTINGS_REJECTED');
  const guard=pi=>{
    pi.on('before_provider_request',(eventData,ctx)=>{
      if(reason!=='COMPLETED'){ctx.abort();return eventData.payload;}
      const admission=admitProviderRequest(eventData.payload,{requests,totalTokens},plan,
        {record:event,stop:fail,abort:()=>ctx.abort()});
      if(admission.type==='admitted'){requests++;payloadBytes+=admission.bytes;providerTiming=emptyProviderTiming();}
      return admission.payload;
    });
    pi.on('after_provider_response',response=>{
      providerTiming.headersReceivedMillis=performance.now()-started;
      providerTiming.providerProcessingMillis=projectProviderTiming(response.headers,null).providerProcessingMillis;
    });
    pi.on('provider_stream_event',stream=>{
      const value=stream.data;
      if(['response.done','response.completed','response.incomplete'].includes(value?.type)){
        const timing=projectProviderTiming(null,value.response);
        providerTiming.serverCreatedAtSeconds=timing.serverCreatedAtSeconds;
        providerTiming.serverCompletedAtSeconds=timing.serverCompletedAtSeconds;
      }
    });
    pi.on('tool_call',(call,ctx)=>{
      if(toolCalls>=plan.maximumTools){fail('RESOURCE_LIMIT');ctx.abort();return {block:true,reason:'Experiment tool allowance exhausted'};}
      referenceExposure.call(call);
      recordAudit(projectCall(call));
      toolCalls++;event({type:'tool_call',toolName:call.toolName,toolCallId:call.toolCallId,inputBytes:Buffer.byteLength(JSON.stringify(call.input)),
        inputSha256:hash(JSON.stringify(call.input))});
    });
    pi.on('tool_result',result=>{referenceExposure.result(result);recordAudit(projectResult(result));});
  };
  const factories=[sdk.createCodemodeExtension({mode:'on'}),sdk.createToolSearchExtension(),guard];
  if(plan.arm==='treatment')factories.push(sdk.createMcpExtension({
    loadConfig:()=>({servers:[{name:'kast',source:'experiment',scope:'extension',config:{
      command:plan.mcpCommand,cwd:plan.workspaceRoot,timeout:180,exposure:plan.exposure}}],errors:[]}),
    logPath:path.join(plan.outputRoot,'mcp.log')}));
  const loader=new sdk.DefaultResourceLoader({cwd:plan.workspaceRoot,agentDir:plan.outputRoot,settingsManager,
    noExtensions:true,noSkills:true,additionalSkillPaths:plan.skills,noPromptTemplates:true,noThemes:true,
    noContextFiles:true,appendSystemPromptOverride:()=>plan.onboarding?[plan.onboarding]:[],extensionFactories:factories});
  await loader.reload();
  event({type:'stage',stage:'RESOURCES_LOADED'});
  if(loader.getExtensions().errors.length)throw Error('EXTENSION_LOAD_FAILED');
  const created=await sdk.createAgentSession({cwd:plan.workspaceRoot,agentDir:plan.outputRoot,settingsManager,
    resourceLoader:loader,modelRuntime,model,thinkingLevel:plan.effort,sessionManager:sdk.SessionManager.inMemory(plan.workspaceRoot)});
  session=created.session;
  event({type:'stage',stage:'SESSION_CREATED'});
  const cancel=()=>{fail('CANCELLED');session.abort().catch(()=>{});};
  process.on('message',message=>{if(message?.type==='cancel')cancel();});
  process.once('SIGTERM',cancel);
  try {
    if(created.modelFallbackMessage||session.model?.id!==plan.model||session.thinkingLevel!==plan.effort)throw Error('MODEL_CHANGED');
    session.subscribe(data=>{
      if(data.type==='message_update'&&providerTiming.firstObservableDeltaMillis===null
        &&['text_delta','thinking_delta','toolcall_delta'].includes(data.assistantMessageEvent?.type))
        providerTiming.firstObservableDeltaMillis=performance.now()-started;
      if(data.type==='message_end'&&data.message.role==='assistant') {
        const observed=finalizedUsage(data.message.usage);
        usage.push(observed);
        if(observed.type==='rejected')fail('USAGE_UNAVAILABLE');
        else totalTokens+=observed.totalTokens;
        const duration=data.message.durationMs;
        providerTiming.sdkDurationMillis=typeof duration==='number'&&Number.isFinite(duration)&&duration>=0?duration:null;
        event({type:'assistant_end',stopReason:data.message.stopReason,usage:observed,providerTiming:{...providerTiming}});
        fs.writeFileSync(path.join(plan.outputRoot,'last-assistant.json'),JSON.stringify(data.message),{mode:0o600});
        if(data.message.stopReason==='error')fail('PROVIDER_FAILURE');
      }
      if(data.type==='tool_execution_start')toolTimes.set(data.toolCallId,performance.now());
      if(data.type==='tool_execution_end') {
        const bytes=Buffer.byteLength(JSON.stringify(data.result??null));toolOutputBytes+=bytes;
        event({type:'tool_end',toolName:data.toolName,toolCallId:data.toolCallId,isError:data.isError,outputBytes:bytes,
          durationMillis:performance.now()-(toolTimes.get(data.toolCallId)??performance.now()),
          ...(data.toolName==='codemode'?{referenceExposure:referenceExposure.modelOutput(data.result)}:{})});
      }
      if(data.type==='auto_retry_start')retries++;
      if(data.type==='compaction_start')compactions++;
    });
    await session.bindExtensions({mode:'rpc',abortHandler:cancel});
    event({type:'stage',stage:'EXTENSIONS_BOUND'});
    setupMillis=performance.now()-started;
    event({type:'session_ready',tools:session.getActiveToolNames(),systemPromptSha256:hash(session.systemPrompt),
      systemPromptBytes:Buffer.byteLength(session.systemPrompt)});
    try {await session.prompt(plan.prompt,{source:'rpc',expandPromptTemplates:false});}
    catch {fail('SESSION_FAILURE');}
    const finalText=session.getLastAssistantText()??'';
    write('transcript-index.json',session.messages.map(message=>({role:message.role,
      bytes:Buffer.byteLength(JSON.stringify(message)),sha256:hash(JSON.stringify(message))})));
    write('answer.json',{text:finalText});
    if(!finalText)fail('NO_ANSWER');
  } finally {
    const {emitSessionShutdownEvent}=await load('dist/core/extensions/runner.js');
    await emitSessionShutdownEvent(session.extensionRunner,{type:'session_shutdown',reason:'exit'});
    session.dispose();
    event({type:'stage',stage:'SESSION_SHUTDOWN'});
  }
  if(hash(fs.readFileSync(plan.authPath))!==authBefore)fail('AUTH_CHANGED');
  write('events.json',events);
  const known=usage.filter(value=>value.type==='usage');
  const qualification=usageQualification(requests,events);
  const cost=qualification==='FINALIZED_PROVIDER_USAGE'&&known.every(value=>value.catalogCostUsd!==null)
    ?known.reduce((sum,value)=>sum+value.catalogCostUsd,0):null;
  return {version:1,outcome:reason,wallSeconds:(performance.now()-started)/1000,setupSeconds:setupMillis/1000,
    provider:plan.provider,model:plan.model,effort:plan.effort,arm:plan.arm,requests,toolCalls,retries,compactions,
    inputTokens:known.reduce((sum,item)=>sum+item.input,0),outputTokens:known.reduce((sum,item)=>sum+item.output,0),
    cacheReadTokens:known.reduce((sum,item)=>sum+item.cacheRead,0),cacheWriteTokens:known.reduce((sum,item)=>sum+item.cacheWrite,0),
    totalTokens,payloadBytes,toolOutputBytes,catalogCostUsd:cost,billedCostUsd:null,
    costQualification:'CATALOG_ESTIMATE_NOT_BILLED',usageQualification:qualification,
    outputCapQualification:'REQUESTED_BACKEND_ENFORCEMENT_UNPROVEN',planSha256:hash(JSON.stringify(plan)),
    piPackageSha256:hash(packageBytes),mcpExecutableSha256:plan.arm==='treatment'?hash(fs.readFileSync(plan.mcpCommand)):null,
    onboardingSha256:hash(plan.onboarding)};
}

async function main(args) {
  if(args.length!==2||!['--run','--worker'].includes(args[0]))throw Error('Usage: --run PLAN');
  const file=path.resolve(args[1]),admitted=admitSessionPlan(JSON.parse(fs.readFileSync(file,'utf8')));
  if(admitted.type==='rejected')throw Error(admitted.reason);
  const {plan}=admitted;
  if(args[0]==='--worker') {
    if(!process.send)throw Error('OWNED_WORKER_REQUIRED');
    // The parent enforces the deadline. Its private KAST_ input is not a
    // product configuration key and must not enter the native MCP process.
    delete process.env[WorkerDeadlineEnvironmentKey];
    const report=await runSession(plan);
    process.send({type:'report',report},()=>process.disconnect());
    return;
  }
  const controller=new AbortController();
  const cancel=()=>controller.abort();
  process.once('SIGINT',cancel);process.once('SIGTERM',cancel);
  const started=performance.now();
  const result=await runOwnedWorker(process.execPath,[fileURLToPath(import.meta.url),'--worker',file],{
    cwd:plan.workspaceRoot,env:{...process.env,PI_OFFLINE:'1',PI_TELEMETRY:'0',KAST_TOOL_RPC_COMMAND:undefined},
    signal:controller.signal,wallMillis:plan.wallSeconds*1000});
  fs.writeFileSync(file+'.result.json',JSON.stringify({...result,parentWallSeconds:(performance.now()-started)/1000},null,2)+'\n',{mode:0o600,flag:'wx'});
  process.removeListener('SIGINT',cancel);process.removeListener('SIGTERM',cancel);
  console.log(JSON.stringify({result:file+'.result.json',type:result.type,outcome:result.report?.outcome}));
  if(result.type!=='report'||result.report.outcome!=='COMPLETED')process.exitCode=1;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))
  main(process.argv.slice(2)).catch(error=>{console.error(String(error.message).slice(0,160));process.exitCode=1;});
