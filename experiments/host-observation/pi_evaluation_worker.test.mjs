import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { isolatedSettings, createEvaluationSession, worker } from './pi_evaluation_worker.mjs';
import { CasePolicy, Outcome } from './pi_evaluation_policy.mjs';

const failure=(operation,reason)=>({type:'failure',failure:{stage:'WORKER_SETUP',operation,reason}});
function construction(options={}) {
  const effects=[],model={id:options.model??'gpt-6.1-sol',provider:'openai-codex'};
  const settings={getTransport:()=>options.transport??'sse',getCacheWarmingMode:()=>options.warming??'off'};
  const session={model,thinkingLevel:options.effort??'high',agent:{abort:()=>effects.push('abort')},
    bindExtensions:async value=>{effects.push(['bind',value.mode]);if(options.bindError)throw Error('PRIVATE-BIND');},
    dispose:()=>{effects.push('dispose');if(options.disposeError)throw Error('PRIVATE-DISPOSE');}};
  const sdk={SettingsManager:{inMemory:value=>{effects.push(['settings',value]);return settings;}},
    DefaultResourceLoader:class {
      constructor(value){effects.push(['loader',value]);}
      async reload(){effects.push('reload');if(options.loadError)throw Error('PRIVATE-LOAD');}
      getExtensions(){return {errors:options.extensionErrors??[]};}
    },createAgentSession:async value=>{effects.push(['create',value]);if(options.createError)throw Error('PRIVATE-CREATE');
      return {session,modelFallbackMessage:options.fallback};}};
  return {effects,session,settings,input:{sdk,plan:{workspaceRoot:'/synthetic-workspace',kastAdapter:'/synthetic-adapter'},
    directory:'/synthetic-private',modelRuntime:{getPhysicalModel:(provider,id)=>{effects.push(['model',provider,id]);return options.missingModel?undefined:model;}},
    policy:{},record:()=>({type:'recorded'}),manager:{}}};
}

test('isolated settings retain exact policy or return finite unavailable failure',()=>{
  const fixture=construction();
  assert.deepEqual(isolatedSettings(fixture.input.sdk.SettingsManager),{type:'settings',settingsManager:fixture.settings});
  const options=fixture.effects[0][1];
  assert.equal(options.transport,'sse');assert.equal(options.cacheWarming,'off');
  assert.deepEqual(options.retry,{enabled:false,maxRetries:0,provider:{maxRetries:0}});
  assert.deepEqual(options.compaction,{enabled:false});assert.deepEqual(options.packages,[]);assert.deepEqual(options.extensions,[]);
  assert.deepEqual(isolatedSettings(construction({transport:'auto'}).input.sdk.SettingsManager),failure('SETTINGS_POLICY','SETTINGS_UNAVAILABLE'));
  assert.deepEqual(isolatedSettings(construction({warming:'streaming'}).input.sdk.SettingsManager),failure('SETTINGS_POLICY','SETTINGS_UNAVAILABLE'));
});

test('session construction preserves settings model resources and binding without provider execution',async()=>{
  const fixture=construction(),result=await createEvaluationSession(fixture.input);
  assert.deepEqual(result,{type:'session',session:fixture.session,settingsManager:fixture.settings});
  assert.deepEqual(fixture.effects.map(value=>Array.isArray(value)?value[0]:value),['settings','model','loader','reload','create','bind']);
  assert.deepEqual(fixture.effects[1],['model','openai-codex','gpt-6.1-sol']);
  const loader=fixture.effects[2][1],created=fixture.effects[4][1];
  assert.equal(loader.settingsManager,fixture.settings);assert.equal(loader.noExtensions,true);
  assert.deepEqual(loader.additionalExtensionPaths,['/synthetic-adapter']);
  assert.equal(loader.extensionFactories.length,1);
  for(const field of ['noSkills','noPromptTemplates','noThemes','noContextFiles'])assert.equal(loader[field],true);
  assert.equal(created.model,fixture.session.model);assert.equal(created.thinkingLevel,'high');
  assert.deepEqual(created.tools,['query_symbols','check_diagnostics','health_check']);
  assert.equal(created.sessionManager,fixture.input.manager);
});

test('missing model and extension load failures stop construction at their finite boundaries',async()=>{
  for(const [options,expected] of [
    [{missingModel:true},failure('MODEL_LOOKUP','MODEL_UNAVAILABLE')],
    [{model:'unknown-model'},failure('MODEL_LOOKUP','MODEL_UNAVAILABLE')],
    [{loadError:true},failure('RESOURCE_LOAD','EFFECT_FAILED')],
    [{extensionErrors:[{message:'PRIVATE-EXTENSION-ERROR'}]},failure('RESOURCE_EXTENSIONS','EXTENSION_LOAD_FAILED')],
    [{createError:true},failure('SESSION_CREATE','EFFECT_FAILED')],
  ]) {
    const fixture=construction(options),result=await createEvaluationSession(fixture.input);
    assert.deepEqual(result,expected);assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
    assert.equal(fixture.effects.includes('dispose'),false);
    assert.equal(fixture.effects.some(value=>Array.isArray(value)&&value[0]==='bind'),false);
  }
});

test('guard record failure during binding wins over later SDK failure and disposes session',async()=>{
  const fixture=construction(),observation=Object.freeze({type:'failure',failure:Object.freeze({stage:'RECORD_WRITE',reason:'IO_FAILED'})});
  const rule=new CasePolicy({name:'synthetic',work:{reportedTokens:2000,requests:1,outputReserve:100,tools:1},
    delivery:{reportedTokens:2000,requests:1,outputReserve:100},inputTokenCeiling:300,
    declarationByteCeiling:4096,expectedSemanticCalls:1,maximumToolTextBytes:4096});
  fixture.input.policy=rule;fixture.input.record=()=>observation;
  fixture.session.bindExtensions=async()=>{
    const handlers=new Map();
    fixture.effects.find(value=>Array.isArray(value)&&value[0]==='loader')[1].extensionFactories[0]({
      on:(event,callback)=>handlers.set(event,callback),setActiveTools:()=>{},getThinkingLevel:()=> 'high',
      getActiveTools:()=>['query_symbols','check_diagnostics','health_check'],
    });
    handlers.get('session_start')({}, {model:fixture.session.model,abort:()=>fixture.effects.push('abort')});
    throw Error('PRIVATE-LATER-SDK-FAILURE');
  };
  assert.equal(await createEvaluationSession(fixture.input),observation);
  assert.equal(rule.report().outcome,Outcome.INVALID);
  assert.equal(fixture.effects.filter(value=>value==='abort').length,1);
  assert.equal(fixture.effects.filter(value=>value==='dispose').length,1);
});

test('unsupported effort fallback and binding dispose rejected constructed session once',async()=>{
  for(const [options,expected] of [
    [{effort:'low'},failure('SESSION_IDENTITY','MODEL_EFFORT_UNAVAILABLE')],
    [{fallback:'PRIVATE-MODEL-FALLBACK'},failure('SESSION_IDENTITY','MODEL_EFFORT_UNAVAILABLE')],
    [{bindError:true},failure('EXTENSIONS_BIND','EFFECT_FAILED')],
    [{bindError:true,disposeError:true},failure('EXTENSIONS_BIND','EFFECT_FAILED')],
  ]) {
    const fixture=construction(options),result=await createEvaluationSession(fixture.input);
    assert.deepEqual(result,expected);assert.equal(fixture.effects.filter(value=>value==='dispose').length,1);
    assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
  }
});

function owned(t) {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'pi-worker-proof-'));fs.chmodSync(root,0o700);
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));return root;
}
test('worker missing plan maps setup I/O failure without SDK loading or arbitrary error text',async t=>{
  assert.deepEqual(await worker(path.join(owned(t),'missing-plan'),0),failure('PLAN_READ','EFFECT_FAILED'));
});

test('worker pin credential and SDK-load failures retain lifecycle and closed cleanup before return',async t=>{
  const send=process.send;
  process.send=()=>assert.fail('Worker must not publish before its final closed return');
  try {
    for(const [name,expected] of [
      ['pin',failure('INSTALLATION_PIN','PIN_MISMATCH')],
      ['pin-close',failure('INSTALLATION_PIN','PIN_MISMATCH')],
      ['credential',failure('CREDENTIAL_VALIDITY','CREDENTIAL_UNAVAILABLE')],
      ['sdk',failure('SDK_LOAD','EFFECT_FAILED')],
    ]) {
      const root=owned(t),pi=path.join(root,'pi'),output=path.join(root,'output');
      fs.mkdirSync(pi,{mode:0o700});fs.mkdirSync(output,{mode:0o700});
      const adapter=path.join(root,'adapter.mjs'),auth=path.join(root,'auth.json'),models=path.join(root,'models.json');
      fs.writeFileSync(adapter,'SYNTHETIC-ADAPTER',{mode:0o600});
      fs.writeFileSync(path.join(pi,'package.json'),JSON.stringify({version:'synthetic-pinned'}),{mode:0o600});
      fs.writeFileSync(auth,JSON.stringify({'openai-codex':{type:'oauth',expires:name==='sdk'?Date.now()+86400000:0,access:'PRIVATE-CREDENTIAL'}}),{mode:0o600});
      fs.writeFileSync(models,'{}',{mode:0o600});
      const plan={piPackageRoot:pi,kastAdapter:adapter,workspaceRoot:root,outputRoot:output,authPath:auth,modelsStorePath:models,
        kastAdapterSha256:name.startsWith('pin')?'0'.repeat(64):createHash('sha256').update('SYNTHETIC-ADAPTER').digest('hex'),
        piVersion:'synthetic-pinned',cases:[{name,mode:'fresh',prompt:'PRIVATE-PROMPT',wallSeconds:1,
          work:{reportedTokens:2000,requests:1,outputReserve:100,tools:1},delivery:{reportedTokens:2000,requests:1,outputReserve:100},
          inputTokenCeiling:300,declarationByteCeiling:4096,expectedSemanticCalls:1,maximumToolTextBytes:4096}]};
      const planFile=path.join(root,'plan.json');fs.writeFileSync(planFile,JSON.stringify(plan),{mode:0o600});
      const close=fs.closeSync,open=fs.openSync,recordDescriptors=new Set();let closed=0,result;
      try {
        if(name==='pin-close') {
          fs.openSync=(file,...args)=>{const fd=open(file,...args);
            if(file===path.join(output,name,'guard.jsonl')||file===path.join(output,name,'events.jsonl'))recordDescriptors.add(fd);
            return fd;};
          fs.closeSync=fd=>{close(fd);if(recordDescriptors.has(fd)){closed++;throw Error('PRIVATE-CLOSE-FAILURE');}};
        }
        result=await worker(planFile,0);
      } finally {fs.closeSync=close;fs.openSync=open;}
      assert.deepEqual(result,expected);
      if(name==='pin-close')assert.equal(closed,2,'Both record handles close despite a prior failure');
      assert.equal(JSON.stringify(result).includes('PRIVATE-'),false);
      const lines=fs.readFileSync(path.join(output,name,'guard.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
      assert.deepEqual(lines.at(-1),{stage:'WORKER_SETUP',outcome:'FAILED'});
      assert.equal(lines.some(value=>value.stage==='WORKER_SETUP'&&value.outcome==='PREPARED'),false);
      assert.equal(fs.existsSync(path.join(output,name,'report.json')),false);
      assert.equal(fs.readFileSync(auth,'utf8').includes('PRIVATE-CREDENTIAL'),true);
    }
  } finally {if(send===undefined)delete process.send;else process.send=send;}
});

test('actual worker retains received identity failure over later archive umask restoration failure',async t=>{
  const root=owned(t),pi=path.join(root,'pi'),output=path.join(root,'output');
  fs.mkdirSync(path.join(pi,'dist/core'),{recursive:true,mode:0o700});fs.mkdirSync(output,{mode:0o700});
  fs.writeFileSync(path.join(pi,'package.json'),JSON.stringify({type:'module',version:'synthetic-pinned'}),{mode:0o600});
  // These modules expose only the external observations needed to reach the
  // received-workspace boundary. No session construction/provider API exists.
  fs.writeFileSync(path.join(pi,'dist/index.js'),`export class ModelRuntime { static async create(){return {};}}
export class SessionManager { static open(){return {getCwd(){return '/synthetic-wrong-workspace';}};}}
`,{mode:0o600});
  fs.writeFileSync(path.join(pi,'dist/core/auth-storage.js'),'export class ReadOnlyAuthStorage {}\n',{mode:0o600});
  const ai=path.join(root,'pi-ai');fs.mkdirSync(path.join(ai,'dist/api'),{recursive:true,mode:0o700});
  fs.writeFileSync(path.join(ai,'package.json'),'{"type":"module"}',{mode:0o600});
  fs.writeFileSync(path.join(ai,'dist/api/openai-codex-responses.js'),
    'export function closeOpenAICodexWebSocketSessions(){throw Error("Unexpected transport effect");}\n',{mode:0o600});
  const adapter=path.join(root,'adapter'),auth=path.join(root,'auth.json'),models=path.join(root,'models.json'),source=path.join(root,'source.jsonl');
  fs.writeFileSync(adapter,'SYNTHETIC-ADAPTER',{mode:0o600});
  fs.writeFileSync(auth,JSON.stringify({'openai-codex':{type:'oauth',expires:Date.now()+86400000,access:'PRIVATE-CREDENTIAL'}}),{mode:0o600});
  fs.writeFileSync(models,'{}',{mode:0o600});fs.writeFileSync(source,'PRIVATE-EXACT-REPLAY\n',{mode:0o600});
  const plan={piPackageRoot:pi,kastAdapter:adapter,workspaceRoot:root,outputRoot:output,authPath:auth,modelsStorePath:models,
    kastAdapterSha256:createHash('sha256').update('SYNTHETIC-ADAPTER').digest('hex'),piVersion:'synthetic-pinned',
    cases:[{name:'received',mode:'received-result',receivedSessionFile:source,receivedResultEntryId:'synthetic-entry',
      receivedContextSha256:'0'.repeat(64),wallSeconds:1,work:{reportedTokens:2000,requests:1,outputReserve:100,tools:1},
      delivery:{reportedTokens:2000,requests:1,outputReserve:100},inputTokenCeiling:300,declarationByteCeiling:4096,
      expectedSemanticCalls:1,maximumToolTextBytes:4096}]};
  const planFile=path.join(root,'plan.json');fs.writeFileSync(planFile,JSON.stringify(plan),{mode:0o600});
  const send=process.send,umask=process.umask,originalMask=process.umask();let calls=0,result;
  try {
    process.send=()=>assert.fail('No receipt before final worker return');
    process.umask=value=>{const previous=umask(value);if(++calls===2)throw Error('PRIVATE-LATE-RESTORE-FAILURE');return previous;};
    result=await worker(planFile,0);
  } finally {process.umask=umask;umask(originalMask);if(send===undefined)delete process.send;else process.send=send;}
  assert.deepEqual(result,failure('RECEIVED_WORKSPACE','WORKSPACE_MISMATCH'));assert.equal(calls,2);
  assert.equal(process.umask(),originalMask);
  assert.equal(fs.readFileSync(source,'utf8'),'PRIVATE-EXACT-REPLAY\n');
  assert.equal(fs.readFileSync(path.join(output,'received/private-replay/received-session.jsonl'),'utf8'),'PRIVATE-EXACT-REPLAY\n');
  const lines=fs.readFileSync(path.join(output,'received/guard.jsonl'),'utf8').trim().split('\n').map(JSON.parse);
  assert.deepEqual(lines.at(-1),{stage:'WORKER_SETUP',outcome:'FAILED'});
  assert.equal(fs.existsSync(path.join(output,'received/report.json')),false);
});
