import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { readPreparedReplayInput } from './pi_evaluation_replay_worker.mjs';

const maximumFileBytes=4194304;
const sha=text=>createHash('sha256').update(text).digest('hex');
const expected=()=>({canonicalSha256:'a'.repeat(64),artifactSha256:'b'.repeat(64),format:'JSON'});
const text='stdout:\n{"integer":1234567890123456789012345678901234567890,"unicode":"雪😀é","quoted":"a,b: \\\"x\\\"","null":null}\n\nstderr:\nexit_status: 0\n';

function withPrepared(run,{presentation=text}={}) {
  const root=fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()),'kast-prepared-input-'));
  const directory=path.join(root,'preflight');
  fs.mkdirSync(directory,{mode:0o700});
  const identity=expected();
  const data={presentation};
  const proof={type:'projected',complete:true,encodingEquivalent:true,...identity,presentationSha256:sha(presentation)};
  const inputPath=path.join(directory,'input.json'),proofPath=path.join(directory,'projection.json');
  const write=()=>{
    fs.writeFileSync(inputPath,JSON.stringify(data)+'\n',{mode:0o600});
    fs.writeFileSync(proofPath,JSON.stringify(proof)+'\n',{mode:0o600});
  };
  write();
  try{return run({root,directory,identity,data,proof,inputPath,proofPath,write});}
  finally{fs.rmSync(root,{recursive:true});}
}

test('prepared replay input preserves exact strings Unicode integer digits and newline bytes',()=>{
  withPrepared(({directory,identity})=>{
    const result=readPreparedReplayInput(directory,identity);
    assert.deepEqual(result,{type:'prepared',presentation:text});
    assert.deepEqual(Buffer.from(result.presentation,'utf8'),Buffer.from(text,'utf8'));
    assert.ok(result.presentation.includes('1234567890123456789012345678901234567890'));
    assert.ok(result.presentation.includes('雪😀é'));
    assert.ok(result.presentation.endsWith('\n'));
    assert.equal(Object.isFrozen(result),true);
    assert.throws(()=>{result.presentation='CHANGED';},TypeError);
    assert.equal(readPreparedReplayInput(directory,identity).presentation,text);
  });
});

test('each missing prepared file rejects with an unavailable measurement',()=>{
  for(const name of ['inputPath','proofPath'])withPrepared(state=>{
    fs.unlinkSync(state[name]);
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_UNAVAILABLE'});
  });
  withPrepared(({root,identity})=>{
    assert.equal(readPreparedReplayInput(path.join(root,'missing-directory'),identity).type,'unavailable');
  });
});

test('malformed JSON invalid UTF-8 and non-string presentation reject without repair',()=>{
  for(const name of ['inputPath','proofPath'])for(const bytes of [Buffer.from('{'),Buffer.from([0xff,0xfe]),Buffer.from('null')])withPrepared(state=>{
    fs.writeFileSync(state[name],bytes);
    assert.equal(readPreparedReplayInput(state.directory,state.identity).type,'unavailable');
  });
  for(const presentation of [null,42,[],{}])withPrepared(state=>{
    state.data.presentation=presentation;state.write();
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_REJECTED'});
  });
});

test('symlink and directory substitutes cannot supply either prepared file',()=>{
  for(const name of ['inputPath','proofPath'])withPrepared(state=>{
    const file=state[name],target=path.join(state.root,`owned-${path.basename(file)}`);
    fs.renameSync(file,target);fs.symlinkSync(target,file);
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_UNAVAILABLE'});
  });
  for(const name of ['inputPath','proofPath'])withPrepared(state=>{
    fs.unlinkSync(state[name]);fs.mkdirSync(state[name]);
    assert.equal(readPreparedReplayInput(state.directory,state.identity).type,'unavailable');
  });
});

test('file byte ceilings accept the exact limit and reject one excess byte',()=>{
  for(const name of ['inputPath','proofPath'])withPrepared(state=>{
    const file=state[name],bytes=fs.readFileSync(file);
    fs.appendFileSync(file,' '.repeat(maximumFileBytes-bytes.length));
    assert.equal(fs.statSync(file).size,maximumFileBytes);
    assert.equal(readPreparedReplayInput(state.directory,state.identity).type,'prepared');
    fs.appendFileSync(file,' ');
    assert.equal(fs.statSync(file).size,maximumFileBytes+1);
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_UNAVAILABLE'});
  });
});

test('canonical artifact and format identities must match the expected trial',()=>{
  for(const [key,value] of [['canonicalSha256','c'.repeat(64)],['artifactSha256','d'.repeat(64)],['format','TOON']])withPrepared(state=>{
    state.proof[key]=value;state.write();
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_REJECTED'});
  });
  for(const key of ['canonicalSha256','artifactSha256','format'])withPrepared(state=>{
    delete state.proof[key];state.write();
    assert.equal(readPreparedReplayInput(state.directory,state.identity).type,'unavailable');
  });
});

test('presentation tampering and a mismatched digest reject instead of re-rendering',()=>{
  withPrepared(state=>{
    state.data.presentation+=' ';state.write();
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_REJECTED'});
  });
  withPrepared(state=>{
    state.proof.presentationSha256=sha(text.trimEnd());state.write();
    assert.equal(readPreparedReplayInput(state.directory,state.identity).type,'unavailable');
  });
});

test('incomplete unequal and unprojected proof states cannot authorize a prepared input',()=>{
  for(const [key,value] of [['type','invalid'],['complete',false],['encodingEquivalent',false],['complete','true'],['encodingEquivalent',1]])withPrepared(state=>{
    state.proof[key]=value;state.write();
    assert.deepEqual(readPreparedReplayInput(state.directory,state.identity),{type:'unavailable',reason:'PREFLIGHT_RENDERING_REJECTED'});
  });
  for(const key of ['complete','encodingEquivalent'])withPrepared(state=>{
    delete state.proof[key];state.write();
    assert.equal(readPreparedReplayInput(state.directory,state.identity).type,'unavailable');
  });
});
