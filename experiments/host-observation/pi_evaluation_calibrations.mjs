import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { verifyInputCalibration, isVerifiedInputCalibration } from './pi_evaluation_payload.mjs';

const maximumReceiptBytes=4*1024*1024;
const maximumReferences=64;
const referenceType='OFFLINE_FULL_PAYLOAD_CALIBRATION';
const digestPattern=/^[a-f0-9]{64}$/;
const failure=(operation,reason)=>Object.freeze({type:'failure',
  failure:Object.freeze({stage:'INPUT_CALIBRATION',operation,reason})});
function observe(operation,run) {
  try {return {type:'observed',value:run()};}
  catch(error) {return failure(operation,error.code==='ENOENT'?'MISSING_FILE':'IO_FAILED');}
}
const failed=value=>value.type==='failure';
const sameFile=(left,right)=>left.dev===right.dev && left.ino===right.ino && left.size===right.size
  && left.mtimeNs===right.mtimeNs && left.ctimeNs===right.ctimeNs && left.mode===right.mode
  && left.uid===right.uid && left.nlink===right.nlink;
function validReference(value) {
  try {
    return value!==null && typeof value==='object' && !Array.isArray(value)
      && Object.getPrototypeOf(value)===Object.prototype && Object.getOwnPropertySymbols(value).length===0
      && Object.keys(value).length===3 && ['type','path','sha256'].every(key=>Object.hasOwn(value,key))
      && Object.values(Object.getOwnPropertyDescriptors(value)).every(descriptor=>Object.hasOwn(descriptor,'value'))
      && value.type===referenceType && typeof value.path==='string' && value.path.length<=4096 && path.isAbsolute(value.path)
      && typeof value.sha256==='string' && digestPattern.test(value.sha256);
  } catch {return false;}
}

// The bytes are an explicit private caller boundary and must never be logged.
export function readPrivateCalibrationFile(reference) {
  if(!validReference(reference)) return failure('REFERENCES','REFERENCE_REJECTED');
  const parent=observe('PARENT_INSPECT',()=>fs.lstatSync(path.dirname(reference.path)));
  if(failed(parent)) return parent;
  if(!parent.value.isDirectory() || parent.value.isSymbolicLink() || parent.value.uid!==process.getuid()
      || (parent.value.mode&0o7777)!==0o700) return failure('PARENT_INSPECT','PRIVATE_PATH_REQUIRED');
  const before=observe('FILE_INSPECT',()=>fs.lstatSync(reference.path,{bigint:true}));
  if(failed(before)) return before;
  // BigInt stat identity/time fields retain exact nanoseconds through the read.
  const stat=before.value;
  if(!stat.isFile() || stat.isSymbolicLink() || stat.nlink!==1n || stat.uid!==BigInt(process.getuid()))
    return failure('FILE_INSPECT','UNSAFE_FILE');
  if(![0o400n,0o600n].includes(stat.mode&0o7777n)) return failure('FILE_INSPECT','PRIVATE_MODE_REQUIRED');
  if(stat.size<1n || stat.size>BigInt(maximumReceiptBytes)) return failure('FILE_INSPECT','SIZE_REJECTED');
  const opened=observe('FILE_OPEN',()=>fs.openSync(reference.path,fs.constants.O_RDONLY|fs.constants.O_NOFOLLOW));
  if(failed(opened)) return opened;
  const fd=opened.value;
  let result;
  try {
    const admitted=observe('OPEN_IDENTITY',()=>fs.fstatSync(fd,{bigint:true}));
    if(failed(admitted)) result=admitted;
    else if(!sameFile(stat,admitted.value)) result=failure('OPEN_IDENTITY','FILE_CHANGED');
    else {
      const bytes=Buffer.alloc(Number(stat.size));
      let offset=0;
      while(offset<bytes.length) {
        const read=observe('FILE_READ',()=>fs.readSync(fd,bytes,offset,bytes.length-offset,null));
        if(failed(read)) {result=read;break;}
        if(read.value===0) {result=failure('FILE_READ','FILE_CHANGED');break;}
        offset+=read.value;
      }
      if(result===undefined) {
        const probe=observe('READ_CEILING',()=>fs.readSync(fd,Buffer.alloc(1),0,1,null));
        if(failed(probe)) result=probe;
        else if(probe.value!==0) result=failure('READ_CEILING','FILE_CHANGED');
        else {
          const after=observe('FINAL_IDENTITY',()=>fs.fstatSync(fd,{bigint:true}));
          const named=observe('FINAL_PATH',()=>fs.lstatSync(reference.path,{bigint:true}));
          const finalParent=observe('PARENT_FINAL',()=>fs.lstatSync(path.dirname(reference.path)));
          if(failed(after)) result=after;
          else if(failed(named)) result=named;
          else if(failed(finalParent)) result=finalParent;
          else if(parent.value.dev!==finalParent.value.dev || parent.value.ino!==finalParent.value.ino
              || parent.value.mode!==finalParent.value.mode || parent.value.uid!==finalParent.value.uid)
            result=failure('PARENT_FINAL','FILE_CHANGED');
          else if(!sameFile(stat,after.value) || !sameFile(stat,named.value))
            result=failure('FINAL_IDENTITY','FILE_CHANGED');
          else {
            const sourceSha256=createHash('sha256').update(bytes).digest('hex');
            result=sourceSha256===reference.sha256 ? {type:'completed',bytes,sourceSha256}
              : failure('VERIFY_DIGEST','DIGEST_MISMATCH');
          }
        }
      }
    }
  } finally {
    const closed=observe('FILE_CLOSE',()=>fs.closeSync(fd));
    if((result===undefined || !failed(result)) && failed(closed)) result=closed;
  }
  return result;
}

// NO_INFERENCE: receipts are read locally; only the verified capability crosses
// into the real policy. Shareable results contain finite metadata, never paths,
// complete provider requests, model text, credentials or filesystem error text.
export function loadInputCalibrations(references,policy) {
  const provenance=[];
  const reject=result=>Object.freeze({...result,registeredCount:provenance.length,
    provenance:Object.freeze(provenance.slice())});
  if(references===undefined) references=[];
  if(!Array.isArray(references) || references.length>maximumReferences)
    return reject(failure('REFERENCES','REFERENCES_REJECTED'));
  const paths=new Set(),digests=new Set();
  for(const reference of references) {
    if(!validReference(reference)) return reject(failure('REFERENCES','REFERENCE_REJECTED'));
    const canonicalPath=path.normalize(reference.path);
    if(paths.has(canonicalPath) || digests.has(reference.sha256))
      return reject(failure('REFERENCES','DUPLICATE_REFERENCE'));
    paths.add(canonicalPath);digests.add(reference.sha256);
  }
  for(const reference of references) {
    const receipt=readPrivateCalibrationFile(reference);
    if(failed(receipt)) return reject(receipt);
    const verified=verifyInputCalibration(receipt.bytes,reference.sha256);
    if(!isVerifiedInputCalibration(verified)) return reject(failure('VERIFY_RECEIPT','RECEIPT_REJECTED'));
    let registration;
    try {registration=policy.registerInputCalibration(verified);}
    catch {return reject(failure('POLICY_REGISTER','POLICY_EFFECT_FAILED'));}
    if(registration?.allow!==true) return reject(failure('POLICY_REGISTER','POLICY_DENIED'));
    provenance.push(Object.freeze({sourceSha256:verified.sourceSha256,payloadSha256:verified.payloadSha256,
      method:verified.method,qualification:verified.qualification,calibratedInputCeiling:verified.calibratedInputCeiling,
      measuredInput:verified.measuredInput}));
  }
  return Object.freeze({type:'loaded',mode:'NO_INFERENCE',count:provenance.length,
    provenance:Object.freeze(provenance.slice())});
}
