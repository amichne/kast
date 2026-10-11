import schema from './pi-fixtures/query-question.schema.json' with {type:'json'};
import qualificationSchema from './pi-fixtures/query-qualification.schema.json' with {type:'json'};
import {isDeepStrictEqual} from 'node:util';

// Pure admission against the owning canonical serializer's generated schema.
// The JVM parity test rejects drift; unsupported schema keywords fail closed.
const keywords=new Set(['type','properties','required','additionalProperties','items','anyOf','allOf','discriminator','enum',
  'minItems','maxItems','uniqueItems','minLength','maxLength','minimum','maximum','pattern']);
function matches(value,node,depth=0) {
  if(depth>64||Object.keys(node).some(key=>!keywords.has(key)))return false;
  if(node.anyOf&&!node.anyOf.some(child=>matches(value,child,depth+1)))return false;
  if(node.allOf&&!node.allOf.every(child=>matches(value,child,depth+1)))return false;
  if(node.enum&&!node.enum.some(item=>isDeepStrictEqual(item,value)))return false;
  if(node.type) {
    const type=node.type;
    if(!(type==='null'?value===null:type==='object'?value!==null&&typeof value==='object'&&!Array.isArray(value):
      type==='array'?Array.isArray(value):type==='integer'?Number.isSafeInteger(value):type==='number'?Number.isFinite(value):typeof value===type))return false;
  }
  if(node.type==='object') {
    if(node.required?.some(key=>!Object.hasOwn(value,key)))return false;
    if(node.additionalProperties===false&&Object.keys(value).some(key=>!Object.hasOwn(node.properties,key)))return false;
    if(Object.entries(node.properties??{}).some(([key,child])=>Object.hasOwn(value,key)&&!matches(value[key],child,depth+1)))return false;
  }
  if(node.type==='array') {
    if(node.minItems!==undefined&&value.length<node.minItems||node.maxItems!==undefined&&value.length>node.maxItems)return false;
    if(node.uniqueItems&&value.some((item,index)=>value.slice(0,index).some(previous=>isDeepStrictEqual(item,previous))))return false;
    if(value.some(item=>!matches(item,node.items,depth+1)))return false;
  }
  if(node.pattern&&(typeof value!=='string'||!new RegExp(node.pattern).test(value)))return false;
  if(node.minLength!==undefined&&[...value].length<node.minLength||node.maxLength!==undefined&&[...value].length>node.maxLength)return false;
  if(node.minimum!==undefined&&value<node.minimum||node.maximum!==undefined&&value>node.maximum)return false;
  return true;
}
export function admittedQuestion(value) {
  try {return matches(value,schema);}catch{return false;}
}
export function admittedProgress(value) {
  try {return matches(value,qualificationSchema.properties.progress);}catch{return false;}
}
export const canonicalQueryLimitations=Object.freeze(qualificationSchema.properties.limitations.items.enum.map(value=>value.toUpperCase().replaceAll('-','_')));
