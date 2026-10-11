// Pure, bounded admission of the installed SDK's text/function Responses projection.
// JSON Schema parameters and encrypted reasoning are explicit opaque boundaries.
import { createHash } from 'node:crypto';

export const PayloadFailure = Object.freeze({INVALID:'HARNESS_OBSERVATION_INVALID',UNSUPPORTED:'HARNESS_PAYLOAD_UNSUPPORTED',BUDGET:'HARNESS_BUDGET_LIMIT',UNPROVEN:'HARNESS_INPUT_BOUND_UNAVAILABLE'});
const integer = value => Number.isSafeInteger(value) && value >= 0;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype;
const keys = (value, allowed, required=[]) => object(value) && Object.keys(value).every(key=>allowed.includes(key)) && required.every(key=>Object.hasOwn(value,key));
const optionalString = (value,key) => value[key] === undefined || typeof value[key] === 'string';
const jsonSafe = (value,state={nodes:0},depth=0) => {
  if(++state.nodes>32768 || depth>64) return false;
  if(value===null || typeof value==='string' || typeof value==='boolean') return true;
  if(typeof value==='number') return Number.isFinite(value);
  if(Array.isArray(value)) return Object.keys(value).length===value.length && Object.keys(value).every((key,index)=>key===String(index)) && Object.values(Object.getOwnPropertyDescriptors(value)).every(descriptor=>Object.hasOwn(descriptor,'value')) && value.every(item=>jsonSafe(item,state,depth+1));
  if(!object(value)||Object.getOwnPropertySymbols(value).length) return false;
  return Object.values(Object.getOwnPropertyDescriptors(value)).every(descriptor=>Object.hasOwn(descriptor,'value') && (descriptor.value===undefined || jsonSafe(descriptor.value,state,depth+1)));
};
const canonical = value => Array.isArray(value) ? value.map(canonical) : object(value) ? Object.fromEntries(Object.keys(value).filter(key=>value[key]!==undefined).sort().map(key=>[key,canonical(value[key])])) : value;
const bytes = value => Buffer.byteLength(JSON.stringify(value));
const validTool = value => keys(value,['type','name','description','parameters','strict'],['type','name','parameters']) && value.type==='function' && typeof value.name==='string' && value.name.length>0 && optionalString(value,'description') && object(value.parameters) && (value.strict===undefined || value.strict===null || typeof value.strict==='boolean');
const validTools = value => Array.isArray(value) && value.length<=64 && value.every(validTool);
const textBlocks = (value,kind) => Array.isArray(value) && value.length<=1024 && value.every(block=>keys(block,['type','text',...(kind==='output_text'?['annotations']:[])],['type','text']) && block.type===kind && typeof block.text==='string' && (block.annotations===undefined || (Array.isArray(block.annotations)&&block.annotations.length===0)));
function validItem(item) {
  if(!object(item)) return false;
  switch(item.type) {
    case 'additional_tools': return keys(item,['type','role','tools'],['type','role','tools']) && item.role==='developer' && validTools(item.tools);
    case 'function_call': return keys(item,['type','id','call_id','name','arguments','namespace'],['type','call_id','name','arguments']) && ['call_id','name','arguments'].every(key=>typeof item[key]==='string') && optionalString(item,'id') && optionalString(item,'namespace');
    case 'function_call_output': return keys(item,['type','call_id','output'],['type','call_id','output']) && typeof item.call_id==='string' && (typeof item.output==='string'||textBlocks(item.output,'input_text'));
    case 'reasoning': return keys(item,['type','id','summary','encrypted_content','status'],['type','id','summary']) && typeof item.id==='string' && Array.isArray(item.summary) && item.summary.every(block=>keys(block,['type','text'],['type','text']) && block.type==='summary_text' && typeof block.text==='string') && optionalString(item,'encrypted_content') && (item.status===undefined || ['completed','in_progress','incomplete'].includes(item.status));
    case 'message':
    case undefined: return keys(item,['type','role','content','id','status','phase'],['role','content']) && ['user','assistant','developer','system'].includes(item.role) && (typeof item.content==='string'||textBlocks(item.content,item.role==='assistant'?'output_text':'input_text')) && optionalString(item,'id') && (item.status===undefined||['completed','in_progress','incomplete'].includes(item.status)) && (item.phase===undefined||['analysis','final_answer'].includes(item.phase));
    default: return false;
  }
}
const facts = new WeakSet();
export const isAdmittedPayload = value => facts.has(value);
export function inspectProviderPayload(payload,maximumPayloadBytes) {
  if(!integer(maximumPayloadBytes)||maximumPayloadBytes<1) return {type:'rejected',reason:PayloadFailure.INVALID};
  if(!jsonSafe(payload)) return {type:'rejected',reason:PayloadFailure.INVALID};
  if(!keys(payload,['model','store','stream','instructions','input','text','include','prompt_cache_key','tool_choice','parallel_tool_calls','temperature','service_tier','tools','reasoning','max_output_tokens'],['model','instructions','input']) || typeof payload.model!=='string' || typeof payload.instructions!=='string' || !Array.isArray(payload.input) || payload.input.length>1024 || !payload.input.every(validItem) || (payload.tools!==undefined&&!validTools(payload.tools)) || (payload.text!==undefined&&(!keys(payload.text,['verbosity'],['verbosity'])||!['low','medium','high'].includes(payload.text.verbosity))) || (payload.reasoning!==undefined&&(!keys(payload.reasoning,['effort','summary'],['effort'])||!['low','medium','high','xhigh','max'].includes(payload.reasoning.effort)||!['auto','concise','detailed',undefined].includes(payload.reasoning.summary))) || (payload.include!==undefined&&(!Array.isArray(payload.include)||payload.include.some(value=>value!=='reasoning.encrypted_content'))) || (payload.tool_choice!==undefined&&payload.tool_choice!=='auto') || !['store','stream','parallel_tool_calls'].every(key=>payload[key]===undefined||typeof payload[key]==='boolean') || !optionalString(payload,'prompt_cache_key') || (payload.temperature!==undefined&&typeof payload.temperature!=='number') || (payload.service_tier!==undefined&&!['auto','default','flex','priority'].includes(payload.service_tier)) || (payload.max_output_tokens!==undefined&&(!integer(payload.max_output_tokens)||payload.max_output_tokens<1))) return {type:'rejected',reason:PayloadFailure.UNSUPPORTED};
  const payloadBytes=bytes(payload);
  if(payloadBytes>maximumPayloadBytes) return {type:'rejected',reason:PayloadFailure.BUDGET};
  // These four fields affect transport/output/cache identity, not the visible input.
  const projection=Object.fromEntries(Object.entries(payload).filter(([key])=>!['store','stream','prompt_cache_key','max_output_tokens'].includes(key)));
  const declarations=payload.input.filter(item=>item.type==='additional_tools');
  const result=Object.freeze({type:'admitted-payload',payloadSha256:createHash('sha256').update(JSON.stringify(canonical(projection))).digest('hex'),payloadBytes,inputBytes:bytes(payload.input),contextBytes:bytes(payload.input.filter(item=>item.type!=='additional_tools')),toolsBytes:bytes(payload.tools??[]),declarationInputBytes:declarations.reduce((sum,item)=>sum+bytes(item),0),instructionsBytes:Buffer.byteLength(payload.instructions),inputItems:payload.input.length,topLevelTools:(payload.tools??[]).length,restoredDeclarations:declarations.length,payloadModel:payload.model,effort:payload.reasoning?.effort,outputTokenCap:payload.max_output_tokens});
  facts.add(result);
  return result;
}

// Public Responses model maximum is conservative denial evidence here. It is not
// an attestation of the Codex OAuth route, nor a usable 20k calibration.
export const MODEL_CONTEXT_WINDOW = Object.freeze({model:'gpt-6.1-sol',inputTokens:1050000,source:'https://developers.openai.com/api/docs/models/gpt-6.1-sol'});
const calibrations = new WeakSet();
export const isVerifiedInputCalibration = value => calibrations.has(value);
export function verifyInputCalibration(receiptBytes,expectedSha256) {
  if(!Buffer.isBuffer(receiptBytes) || receiptBytes.length>4194304 || !/^[a-f0-9]{64}$/.test(expectedSha256)) return {type:'rejected',reason:PayloadFailure.INVALID};
  const sourceSha256=createHash('sha256').update(receiptBytes).digest('hex');
  if(sourceSha256!==expectedSha256) return {type:'rejected',reason:PayloadFailure.INVALID};
  let receipt;
  try {receipt=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(receiptBytes));} catch {return {type:'rejected',reason:PayloadFailure.INVALID};}
  if(!jsonSafe(receipt) || !keys(receipt,['type','provider','model','request','usage','calibratedInputCeiling','method','qualification'],['type','provider','model','request','usage','calibratedInputCeiling','method','qualification']) || receipt.type!=='FULL_PAYLOAD_CALIBRATION' || receipt.provider!=='openai-codex' || receipt.model!=='gpt-6.1-sol' || receipt.method!=='EXACT_PROJECTED_REQUEST_FINALIZED_USAGE' || receipt.qualification!=='CALIBRATION_NOT_TOKENIZER_PROOF' || !integer(receipt.calibratedInputCeiling) || receipt.calibratedInputCeiling<1) return {type:'rejected',reason:PayloadFailure.INVALID};
  const request=inspectProviderPayload(receipt.request,4194304), usage=receipt.usage;
  if(!isAdmittedPayload(request) || request.payloadModel!==receipt.model || !keys(usage,['input','cacheRead','cacheWrite','output','reasoning','totalTokens'],['input','cacheRead','cacheWrite','output','totalTokens']) || !['input','cacheRead','cacheWrite','output','totalTokens'].every(key=>integer(usage[key])) || !integer(usage.reasoning??0) || (usage.reasoning??0)>usage.output) return {type:'rejected',reason:PayloadFailure.INVALID};
  const measuredInput=usage.input+usage.cacheRead+usage.cacheWrite;
  if(!integer(measuredInput) || measuredInput+usage.output!==usage.totalTokens || measuredInput<1 || receipt.calibratedInputCeiling<measuredInput || receipt.calibratedInputCeiling>MODEL_CONTEXT_WINDOW.inputTokens) return {type:'rejected',reason:PayloadFailure.INVALID};
  const result=Object.freeze({type:'verified-input-calibration',payloadSha256:request.payloadSha256,sourceSha256,calibratedInputCeiling:receipt.calibratedInputCeiling,measuredInput,method:receipt.method,qualification:receipt.qualification});
  calibrations.add(result);
  return result;
}
