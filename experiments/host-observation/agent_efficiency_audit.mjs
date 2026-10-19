import {createHash} from 'node:crypto';

// This projector supports declaration selection. It does not adjudicate semantic
// correctness or establish complete relation evidence. Unknown shapes stay unknown.
const MAX_ROWS=32, MAX_TEXT=512;
const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const string=v=>typeof v==='string'&&v.length>0&&v.length<=MAX_TEXT;
const hash=v=>createHash('sha256').update(v).digest('hex');
const tools=new Set(['query_symbols','replace_body','add_declaration','check_diagnostics']);
const requestTypes=new Set(['RUN','READ_SOURCE','READ_RESULT','RESUME']);
const kinds=new Set(['CLASS','FUNCTION','PROPERTY','TYPE_ALIAS']);
const statuses=new Set(['complete','rejected']);
const mutationFailures=new Set(['PLANNING_REJECTED','AUTHORIZATION_UNAVAILABLE','APPLY_REJECTED','APPLY_UNVERIFIED','RECOVERY_UNAVAILABLE']);
const integer=v=>Number.isSafeInteger(v)&&v>=0;
const safeCode=v=>string(v)&&/^[A-Z][A-Z0-9_]*$/.test(v);
const cliReason=v=>safeCode(v)||(string(v)&&/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(v));
function base(event,phase){
  if(!object(event)||typeof event.toolName!=='string'||!event.toolName.startsWith('mcp__kast__'))return null;
  const tool=event.toolName.slice(11);
  if(!tools.has(tool))return null;
  const out={phase,toolName:event.toolName,auditScope:'DECLARATION_SELECTION_ONLY',variant:'unsupported'};
  if(!string(event.toolCallId)||(event.parentToolCallId!==undefined&&!string(event.parentToolCallId)))
    return {...out,variant:'invalid'};
  return {...out,toolCallId:event.toolCallId,...(event.parentToolCallId?{parentToolCallId:event.parentToolCallId}:{})};
}
function scope(value){
  if(!object(value)||!['DIRECTORY','PACKAGE'].includes(value.type))return null;
  const name=value.type==='DIRECTORY'?'relativeDirectoryPath':'packageName';
  if(!string(value[name]))return null;
  const out={type:value.type,[name]:value[name]};
  const include=value.type==='DIRECTORY'?'includeSubdirectories':'includeSubpackages';
  if(value[include]!==undefined){if(typeof value[include]!=='boolean')return null;out[include]=value[include];}
  if(value.sourceSetNames!==undefined){
    if(!Array.isArray(value.sourceSetNames)||!value.sourceSetNames.length||value.sourceSetNames.length>MAX_ROWS
      ||!value.sourceSetNames.every(string))return null;
    out.sourceSetNames=[...value.sourceSetNames];
  }
  return out;
}
export function projectCall(event){
  const out=base(event,'call');if(!out||out.variant==='invalid')return out;
  if(!object(event.input))return {...out,variant:'invalid'};
  if(['mcp__kast__replace_body','mcp__kast__add_declaration'].includes(event.toolName)){
    const target=event.input.exactTarget;
    const body=event.toolName==='mcp__kast__replace_body'&&typeof event.input.body==='string'
      ?{bodySha256:hash(event.input.body)}:{};
    return typeof target==='string'&&target.length?{...out,variant:'supported',request:{exactTargetHash:hash(target),...body}}:{...out,variant:'invalid'};
  }
  if(event.toolName!=='mcp__kast__query_symbols')return out;
  if(event.input.verbose!==undefined&&typeof event.input.verbose!=='boolean')return {...out,variant:'invalid'};
  out.presentation=event.input.verbose===true?'CANONICAL':'COMPACT_DEFAULT';
  const value=event.input.request;
  if(!object(value))return {...out,variant:'invalid'};
  if(!requestTypes.has(value.type)||value.type!=='RUN')return out;
  const source=value.source;
  if(!object(source))return {...out,variant:'invalid'};
  let projected;
  if(source.type==='SEARCH_DECLARATIONS'){
    const selected=scope(source.scope);
    if(!string(source.declarationName)||!Array.isArray(source.declarationKinds)||!source.declarationKinds.length
      ||source.declarationKinds.length>4||!source.declarationKinds.every(k=>kinds.has(k))||!selected)return {...out,variant:'invalid'};
    projected={type:source.type,declarationName:source.declarationName,declarationKinds:[...source.declarationKinds],scope:selected};
    if(source.nameMatch!==undefined){if(!['EXACT','FUZZY'].includes(source.nameMatch))return out;projected.nameMatch=source.nameMatch;}
  } else if(source.type==='SYMBOL_REFS'){
    if(!Array.isArray(source.symbolRefs)||!source.symbolRefs.length||source.symbolRefs.length>MAX_ROWS
      ||!source.symbolRefs.every(string))return {...out,variant:'invalid'};
    projected={type:source.type,symbolRefHashes:source.symbolRefs.map(hash)};
  } else return out;
  const request={type:'RUN',source:projected};
  if(value.output!==undefined){
    if(!object(value.output)||value.output.type!=='SYMBOLS'||!Array.isArray(value.output.fields)
      ||!value.output.fields.every(f=>['NAME','LOCATION','SIGNATURE','SOURCE'].includes(f)))return out;
    request.output={type:'SYMBOLS',fields:[...value.output.fields]};
  }
  if(value.steps!==undefined&&(!Array.isArray(value.steps)||value.steps.length))return {...out,request};
  return {...out,variant:'supported',request};
}
function row(value){
  if(!object(value)||value.type!=='exact-symbol'||!string(value.ref)||!string(value.name)||!string(value.kind)
    ||!object(value.location)||!string(value.location.file)||!object(value.location.range))return null;
  const span=value.location.range;
  if(!integer(span.startInclusive)||!integer(span.endExclusive)||span.endExclusive<span.startInclusive)return null;
  const result={type:'exact-symbol',refHash:hash(value.ref),kind:value.kind,name:value.name,
    location:{file:value.location.file,range:{startInclusive:span.startInclusive,endExclusive:span.endExclusive}}};
  if(value.signature!==undefined){
    if(!object(value.signature)||!string(value.signature.type)||!string(value.signature.qualifiedIdentity))return null;
    result.signature={type:value.signature.type,qualifiedIdentity:value.signature.qualifiedIdentity};
  }
  return result;
}
function phaseMetadata(value){
  if(!object(value)||!['change.plan','change.apply','change.recover'].includes(value.operation)
    ||!['complete','qualified','rejected'].includes(value.status))return null;
  const out={operation:value.operation,status:value.status};
  for(const key of ['failure','qualification','reason'])if(cliReason(value[key]))out[key]=value[key];
  if(['verified','applied_unverified','recovery_required','prior-state','rolled-back','recovery-required'].includes(value.state))out.state=value.state;
  return out;
}
export function projectResult(event){
  const out=base(event,'result');if(!out||out.variant==='invalid')return out;
  if(typeof event.isError!=='boolean')return {...out,variant:'invalid'};
  out.isError=event.isError;
  // Pi's convertMcpResult keeps the CallToolResult as structuredContent.
  // Unwrap that native envelope once. Text and details never supply evidence.
  const envelope=event.structuredContent;
  const value=object(envelope)&&Array.isArray(envelope.content)&&object(envelope.structuredContent)
    ?envelope.structuredContent:envelope;
  if(!object(value))return out;
  if(['mcp__kast__replace_body','mcp__kast__add_declaration'].includes(event.toolName)){
    if(value.status==='rejected'&&object(value.error)&&mutationFailures.has(value.error.code)){
      const error={code:value.error.code};
      for(const key of ['plan','application','recovery']){
        const phase=phaseMetadata(value.error[key]);if(phase!==null)error[key]=phase;
      }
      return {...out,variant:'supported',result:{status:'rejected',error}};
    }
    if(value.status==='complete'&&string(value.planIdentity)){
      const application=phaseMetadata(value.application);
      if(application!==null)return {...out,variant:'supported',result:{status:'complete',planIdentityHash:hash(value.planIdentity),application}};
    }
    return out;
  }
  if(event.toolName!=='mcp__kast__query_symbols'||value.operation!=='query.run'||!statuses.has(value.status))return out;
  const result={operation:value.operation,status:value.status};
  if(value.status==='rejected'){
    if(!safeCode(value.failure))return {...out,result};
    return {...out,variant:'supported',result:{...result,failure:value.failure}};
  }
  if(!object(value.coverage)||typeof value.coverage.exhaustive!=='boolean'||!object(value.invocation)
    ||!object(value.invocation.stop)||!safeCode(value.invocation.stop.type)||!object(value.evidence_window)
    ||!safeCode(value.evidence_window.type)||!Array.isArray(value.items))return {...out,result};
  result.coverage={exhaustive:value.coverage.exhaustive};
  result.invocation={stop:{type:value.invocation.stop.type}};
  result.evidence_window={type:value.evidence_window.type};
  result.items=value.items.slice(0,MAX_ROWS).map(row);
  if(result.items.some(v=>v===null))return {...out,result};
  if(object(value.live)&&['root','host','contentView'].every(k=>string(value.live[k]))
    &&integer(value.live.epoch)&&integer(value.live.version))result.live={root:value.live.root,host:value.live.host,
      epoch:value.live.epoch,contentView:value.live.contentView,version:value.live.version};
  else return {...out,result};
  const omittedRows=Math.max(0,value.items.length-MAX_ROWS);
  return {...out,variant:omittedRows?'truncated':'supported',omittedRows,result};
}

// Session-local telemetry counts issued seed references in model-facing text.
// It grants no semantic correctness or completeness claim.
export function createReferenceExposureAudit() {
  const pending=new Set(),refs=new Set();
  let results=0,unknown=false;
  return {
    call(event) {
      const projected=projectCall(event);
      if(projected?.variant==='supported'&&projected.request?.source?.type==='SEARCH_DECLARATIONS')
        pending.add(event.toolCallId);
    },
    result(event) {
      if(!pending.delete(event.toolCallId))return;
      results++;
      const projected=projectResult(event);
      if(projected?.variant!=='supported'){unknown=true;return;}
      if(projected.result.status==='rejected')return;
      const envelope=event.structuredContent;
      const value=object(envelope)&&Array.isArray(envelope.content)&&object(envelope.structuredContent)
        ?envelope.structuredContent:envelope;
      for(const item of value.items){
        if(refs.size===MAX_ROWS&&!refs.has(item.ref)){unknown=true;continue;}
        refs.add(item.ref);
      }
    },
    modelOutput(result) {
      const content=result?.content;
      const known=Array.isArray(content)&&content.every(block=>block.type==='text'&&typeof block.text==='string');
      const exposed=known?[...refs].filter(ref=>content.some(block=>block.text.includes(ref))).length:null;
      return {qualification:unknown||pending.size||!known?'UNKNOWN':'TEXT_CONTENT_ONLY',
        observedSeedResults:results,observedSeedReferences:refs.size,seedReferencesInOutput:exposed};
    },
  };
}

// Whitelist scalar timing metadata. Headers, source text and response bodies stay private.
export function projectProviderTiming(headers, response) {
  const scalar=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0?value:null;
  const raw=object(headers)?Object.entries(headers).find(([name])=>name.toLowerCase()==='openai-processing-ms')?.[1]:undefined;
  const processing=typeof raw==='string'&&/^[0-9]+(?:\.[0-9]+)?$/.test(raw)?scalar(Number(raw)):null;
  return {providerProcessingMillis:processing,
    serverCreatedAtSeconds:scalar(response?.created_at),
    serverCompletedAtSeconds:scalar(response?.completed_at)};
}
