import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {projectCall,projectResult,createReferenceExposureAudit,projectProviderTiming} from './agent_efficiency_audit.mjs';

const hash = s=>createHash('sha256').update(s).digest('hex');
const event = (tool,extra)=>({toolName:`mcp__kast__${tool}`,toolCallId:'parent/1',parentToolCallId:'parent',...extra});
const query = {request:{type:'RUN',source:{type:'SEARCH_DECLARATIONS',declarationName:'calculate',
  declarationKinds:['FUNCTION'],scope:{type:'DIRECTORY',relativeDirectoryPath:'src/main',sourceSetNames:['main']}},
  output:{type:'SYMBOLS',fields:['NAME','LOCATION','SIGNATURE']}}};
const result = ()=>({operation:'query.run',status:'complete',coverage:{exhaustive:true},
  invocation:{stop:{type:'COMPLETED'}},evidence_window:{type:'FINAL'},
  live:{root:'/subject',host:'host-1',epoch:1,contentView:'SAVED_PSI_COMMITTED',version:1},
  items:[{type:'exact-symbol',ref:'issued-reference',kind:'function',name:'calculate',
    location:{file:'/subject/src/main/Math.kt',range:{startInclusive:10,endExclusive:42}},
    signature:{type:'function',qualifiedIdentity:'example.calculate',receiver:{type:'absent'},
      contextReceivers:[],valueParameters:['kotlin/Int'],typeParameterCount:0},source:'PRIVATE_SOURCE'}]});

test('actual nested request shape keeps scope, identity, and correlation',()=>{
  const p=projectCall(event('query_symbols',{input:query}));
  assert.equal(p.variant,'supported');
  assert.equal(p.toolCallId,'parent/1');
  assert.equal(p.parentToolCallId,'parent');
  assert.deepEqual(p.request,query.request);
  assert.equal(p.presentation,'COMPACT_DEFAULT');
  assert.equal(projectCall(event('query_symbols',{input:{...query,verbose:true}})).presentation,'CANONICAL');
});
test('canonical result signature stays qualified without source content',()=>{
  const p=projectResult(event('query_symbols',{isError:false,structuredContent:result()}));
  assert.equal(p.variant,'supported');
  assert.equal(p.result.items[0].signature.qualifiedIdentity,'example.calculate');
  assert.equal(p.result.items[0].refHash,hash('issued-reference'));
  assert.equal(p.result.coverage.exhaustive,true);
  assert.equal(JSON.stringify(p).includes('PRIVATE_SOURCE'),false);
  assert.equal(JSON.stringify(p).includes('issued-reference'),false);
});
test('Pi native MCP preserves a CallToolResult wrapper around the Kast document',()=>{
  const wrapper={content:[{type:'text',text:'PRIVATE_SOURCE'}],isError:false,structuredContent:result()};
  const p=projectResult(event('query_symbols',{isError:false,structuredContent:wrapper}));
  assert.equal(p.variant,'supported');
  assert.equal(p.result.items[0].signature.qualifiedIdentity,'example.calculate');
  assert.equal(JSON.stringify(p).includes('PRIVATE_SOURCE'),false);
});
test('mutation target hashes correlate with issued result refs without body text',()=>{
  const p=projectCall(event('replace_body',{input:{exactTarget:'issued-reference',body:'PRIVATE_BODY'}}));
  assert.equal(p.variant,'supported');
  assert.equal(p.request.exactTargetHash,hash('issued-reference'));
  assert.equal(p.request.bodySha256,hash('PRIVATE_BODY'));
  assert.equal(JSON.stringify(p).includes('PRIVATE_BODY'),false);
});
test('closed mutation rejection keeps the planning failure without source diffs',()=>{
  const p=projectResult(event('replace_body',{isError:true,structuredContent:{
    status:'rejected',error:{code:'PLANNING_REJECTED',plan:{operation:'change.plan',status:'rejected',reason:'unsupported-intent',diff:'PRIVATE_SOURCE'}}}}));
  assert.equal(p.variant,'supported');
  assert.equal(p.result.error.code,'PLANNING_REJECTED');
  assert.equal(p.result.error.plan.reason,'unsupported-intent');
  assert.equal(JSON.stringify(p).includes('PRIVATE_SOURCE'),false);
});
test('missing metadata and unknown row shapes do not establish a supported projection',()=>{
  const missing=projectResult(event('query_symbols',{isError:false,structuredContent:{operation:'query.run',status:'complete'}}));
  assert.notEqual(missing.variant,'supported');
  const value=result();value.items=[{type:'unrecognized',source:'PRIVATE_SOURCE'}];
  assert.notEqual(projectResult(event('query_symbols',{isError:false,structuredContent:value})).variant,'supported');
  assert.equal(projectCall(event('query_symbols',{input:{request:{type:'INVENTED'}}})).variant,'unsupported');
  assert.equal(projectCall({toolName:'read'}),null);
});
test('original rejection code survives without arbitrary error text',()=>{
  const p=projectResult(event('query_symbols',{isError:false,structuredContent:{operation:'query.run',status:'rejected',failure:'DECLARATION_NOT_FOUND',message:'PRIVATE_MESSAGE'}}));
  assert.equal(p.result.failure,'DECLARATION_NOT_FOUND');
  assert.equal(JSON.stringify(p).includes('PRIVATE_MESSAGE'),false);
});
test('bounded projections report omissions, and do not parse text as structured evidence',()=>{
  const value=result();value.items=Array.from({length:40},()=>result().items[0]);
  const p=projectResult(event('query_symbols',{isError:false,structuredContent:value}));
  assert.equal(p.variant,'truncated');assert.equal(p.result.items.length,32);
  assert.equal(p.omittedRows,8);
  const absent=projectResult(event('query_symbols',{isError:false,content:[{type:'text',text:JSON.stringify(result())}]}));
  assert.equal(absent.variant,'unsupported');
});

test('reference exposure telemetry counts text exposure without recording references',()=>{
  const audit=createReferenceExposureAudit();
  audit.call(event('query_symbols',{input:query}));
  audit.result(event('query_symbols',{isError:false,structuredContent:{content:[],structuredContent:result()}}));
  const terminal=audit.modelOutput({content:[{type:'text',text:'{"files":["/subject/Use.kt"]}'}]});
  assert.deepEqual(terminal,{qualification:'TEXT_CONTENT_ONLY',observedSeedResults:1,
    observedSeedReferences:1,seedReferencesInOutput:0});
  const exposed=audit.modelOutput({content:[{type:'text',text:'ref: issued-reference'}]});
  assert.equal(exposed.seedReferencesInOutput,1);
  assert.equal(JSON.stringify(exposed).includes('issued-reference'),false);
});
test('unsupported or unfinished seed results cannot certify zero reference exposure',()=>{
  const audit=createReferenceExposureAudit();
  audit.call(event('query_symbols',{input:query}));
  assert.equal(audit.modelOutput({content:[]}).qualification,'UNKNOWN');
  audit.result(event('query_symbols',{structuredContent:{unknown:true}}));
  assert.equal(audit.modelOutput({content:[]}).qualification,'UNKNOWN');
  assert.equal(audit.modelOutput({}).seedReferencesInOutput,null);
});

test('provider timing keeps only finite explicitly supplied scalar fields',()=>{
  assert.deepEqual(projectProviderTiming({'OpenAI-Processing-Ms':'250.5',authorization:'SECRET'},
    {created_at:100,completed_at:102,output:'PRIVATE_SOURCE'}),
    {providerProcessingMillis:250.5,serverCreatedAtSeconds:100,serverCompletedAtSeconds:102});
  assert.deepEqual(projectProviderTiming({},{}),
    {providerProcessingMillis:null,serverCreatedAtSeconds:null,serverCompletedAtSeconds:null});
  assert.deepEqual(projectProviderTiming({'openai-processing-ms':'Infinity'},
    {created_at:-1,completed_at:NaN}),
    {providerProcessingMillis:null,serverCreatedAtSeconds:null,serverCompletedAtSeconds:null});
});
