#!/usr/bin/env node
// Real installed Pi conversion of a captured MCP tools/list response. No local ref expansion.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import {findPackageJSON} from 'node:module';
import {pathToFileURL} from 'node:url';

const [sdkRoot, input, destination] = process.argv.slice(2);
if (![sdkRoot,input,destination].every(value => path.isAbsolute(value ?? ''))) throw Error('Absolute SDK, capture, and output paths needed');
const source=fs.readFileSync(input);
const replies=source.toString('utf8').trim().split('\n').map(line=>JSON.parse(line));
const catalog=replies.find(reply=>Array.isArray(reply.result?.tools))?.result;
if (!catalog) throw Error('Successful complete tools/list response needed');
if (catalog.nextCursor) throw Error('Unconsumed catalog page');
const aiRoot=path.dirname(findPackageJSON('@earendil-works/pi-ai',pathToFileURL(path.join(sdkRoot,'package.json'))));
const converter=path.join(aiRoot,'dist/api/openai-responses-shared.js');
const {convertResponsesTools}=await import(pathToFileURL(converter).href);
const options={strict:false,supportsStrictMode:true,supportsOpenAIGrammarTools:false};
const declarations=convertResponsesTools(catalog.tools.map(tool=>({name:tool.name,description:tool.description,parameters:tool.inputSchema})),options);
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const measure=value=>{const text=JSON.stringify(value);return {unicodeCharacters:Array.from(text).length,utf8Bytes:Buffer.byteLength(text),sha256:sha(text)};};
fs.mkdirSync(destination,{recursive:true});
for(const [name,bytes] of [['tools-list.json',source],['provider-declarations.json',Buffer.from(JSON.stringify(declarations))]])
  fs.writeFileSync(path.join(destination,name+'.gz'),zlib.gzipSync(bytes));
const report={qualification:'ACTUAL_INSTALLED_PI_CONVERTER_ON_ACTUAL_MCP_CATALOG_NOT_TASK_REPLAY',
  pi:{version:JSON.parse(fs.readFileSync(path.join(sdkRoot,'package.json'))).version,root:sdkRoot},
  ai:{version:JSON.parse(fs.readFileSync(path.join(aiRoot,'package.json'))).version,converter,sha256:sha(fs.readFileSync(converter))},
  conversionOptions:options,serialization:'JSON.stringify, no whitespace, UTF-8',
  catalog:measure(catalog),allProviderDeclarations:measure(declarations),
  tools:catalog.tools.map((tool,index)=>({name:tool.name,inputSchema:measure(tool.inputSchema),
    outputSchema:tool.outputSchema?measure(tool.outputSchema):null,converted:measure(declarations[index])})),
  taskInputTokens:null,cacheAccounting:null,discoveryCalls:null,recoveryCalls:null,semanticInvocations:0,
  modelFacingResults:null,nativeCodemodeReplay:'NOT_RUN',expandingAdapterReplay:'BLOCKED_ADAPTER_IDENTITY_UNAVAILABLE'};
fs.writeFileSync(path.join(destination,'measurements.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({pi:report.pi.version,aggregate:report.allProviderDeclarations,query:report.tools.find(tool=>tool.name==='query_symbols')}));
