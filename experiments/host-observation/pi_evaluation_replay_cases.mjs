import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const freeze=value=>{
  if(value&&typeof value==='object') {for(const child of Object.values(value))freeze(child);Object.freeze(value);}
  return value;
};
const sha=value=>createHash('sha256').update(value).digest('hex');
const fixturePath=id=>new URL(`./interface-replay/fixtures/${id}.json`,import.meta.url);
const commandGrammar=[
  'kast query --name NAME [--in DIRECTORY] [--source-set main] [--kind class|function|property|type-alias] [--fields name,location,signature,source]',
  'kast query --ref EXACT_REFERENCE [--relation references|callers|callees|implementations|inheritors|overrides|type-uses]',
  'kast source read --ref EXACT_REFERENCE --region callable-body|file|anchor --text complete|none',
  'kast result read --ref RESULT_REFERENCE --cursor INTEGER',
  'kast result resume --continuation OUTPUT_CONTINUATION',
];
const finishJson='{"kind":"finish","conclusion":"insufficient-evidence"|"absent"|"present","scope":{"directory":"...","sourceSets":["..."]},"exhaustive":true|false,"limitations":["..."]}';
const clarifyJson='{"kind":"clarify","question":"..."}';
const contract=[
  'Use the bash tool for one next Kast command. The replay permits one shell call and one attempted Kast invocation.',
  ...commandGrammar,
  'The --request - form takes the public JSON request envelope on stdin instead of semantic flags.',
  `A finish response uses JSON: ${finishJson}.`,
  'Copy only the issued qualification.limitations codes verbatim into finish limitations. Do not replace codes with prose or add other evidence fields.',
  `A clarification uses JSON: ${clarifyJson}. Only the task determines whether a final answer or clarification is justified.`,
  'Choose the next decision for the original task. The last bash result belongs to the preceding call. References are synthetic and fixture-local.',
].join('\n');
const conciseContract=[
  'Use bash for at most one shell call and one attempted Kast invocation.',
  ...commandGrammar.map(line=>line.replaceAll('EXACT_REFERENCE','REF').replaceAll('RESULT_REFERENCE','RESULT').replaceAll('OUTPUT_CONTINUATION','TOKEN').replaceAll('DIRECTORY','DIR').replaceAll('INTEGER','N')),
  '--request -: public JSON request envelope on stdin instead of semantic flags.',
  `Finish JSON: ${finishJson}.`,
  'Finish limitations: copy only issued qualification.limitations codes verbatim. No prose or extra evidence fields.',
  `Clarify JSON: ${clarifyJson}. Finish or clarify only if the original task supports it.`,
  'Copy issued references and tokens exactly. References are synthetic and fixture-local. The bash result belongs to the preceding call.',
].join('\n');
const instructionContracts=Object.freeze({full:contract,concise:conciseContract});
const metadata=[
  {id:'C1',name:'overload',task:'Find the callers of submit(id: Long) in services/orders/src/main/kotlin/Orders.kt. The declaration search already returned the overloads. Select that declaration with its issued reference.',command:'kast query --name submit --in services/orders --source-set main --kind function',permittedDecisions:['invoke']},
  {id:'C2',name:'source',task:'Read the complete callable body of submit(id: String, retries: Int). The declaration was selected in the previous result. Use its issued reference.',command:'kast query --ref exact:v5:AAAAAAAAAAAAAAAAAAAAAg',permittedDecisions:['invoke']},
  {id:'C3',name:'scope',task:'Find declarations named OrderService only under services/orders and only in the main source set. The previous broad discovery returned a test declaration. Preserve both requested constraints in the next discovery.',command:'kast query --name OrderService --kind class',permittedDecisions:['invoke']},
  {id:'C4',name:'retained',task:'Read the next output page of the retained result from the previous call. Use the issued result reference and next_cursor. Do not execute a new semantic query.',command:'kast result read --ref result:v1:00000000-0000-0000-0000-000000000004 --cursor 0 --max-results 1',permittedDecisions:['invoke']},
  {id:'C5',name:'coverage',task:'Does the supplied result prove that MissingOrder is absent from services/orders in the main source set? Answer from the supplied evidence, without another command. Preserve the scope and limitations.',command:'kast query --name MissingOrder --in services/orders --source-set main --kind class',permittedDecisions:['finish']},
  {id:'C6',name:'repair',task:'Find declarations named OrderService only under services/orders in the main source set, and return name and signature. Repair the failed command without changing the intended query.',command:"kast query --name OrderService --in services/orders --source-set main --fields 'name, signature'",permittedDecisions:['invoke'],stderr:'kast: INVALID_FIELDS. Use unique, comma-separated --fields values: name,location,signature,source.\n',exitStatus:2},
];

// Canonical bytes cross this owner untouched. In particular, no JSON numeric
// parse occurs before the production Kotlin renderer receives the document.
export function replayCases(profile='full') {
  if(typeof profile!=='string'||!Object.hasOwn(instructionContracts,profile))throw Error('REPLAY_INSTRUCTION_PROFILE_REJECTED');
  return freeze(metadata.map(item=>{
    const canonicalResponse=readFileSync(fixturePath(item.id),'utf8');
    return {...item,version:'interface-replay-v2',instructionProfile:profile,canonicalResponse,canonicalSha256:sha(canonicalResponse),canonicalPath:fixturePath(item.id).pathname,producer:{type:'SYNTHETIC_PUBLIC_CONTRACT_FIXTURE',source:'protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryResultRowCliDocuments.kt',sourceRevision:'0decbfa874a21fc70592992bc12eaa6939980af2'},visibleContract:instructionContracts[profile],prefixCall:{toolName:'bash',toolCallId:`replay-prefix-${item.id}`,input:{command:item.command}},stderr:item.stderr??'',exitStatus:item.exitStatus??(item.id==='C3'||item.id==='C5'?10:0),limits:{providerRequests:1,shellCalls:1,kastInvocations:1,nativeCalls:0,wallSeconds:60}};
  }));
}
export function replayCase(id,profile='full') {
  const item=replayCases(profile).find(value=>value.id===id);
  if(!item)throw Error('UNKNOWN_REPLAY_CASE');
  return item;
}

// The transition table supplies only declared fixture effects. Public admission
// remains authoritative for syntax. The independent grader never reads these
// predicates or their returned documents.
export function replayTransition(caseId,admission) {
  const request=admission?.type==='ADMITTED'?admission.arguments?.request:admission?.type==='admitted'?admission.request:undefined;
  const list=(actual,expected)=>Array.isArray(actual)&&actual.length===expected.length&&actual.every((value,index)=>value===expected[index]);
  const search=request?.type==='RUN'&&request.source?.type==='SEARCH_DECLARATIONS'&&request.source.declarationName==='OrderService'
    &&[undefined,null,'EXACT'].includes(request.source.nameMatch)&&request.source.scope?.type==='DIRECTORY'
    &&request.source.scope.relativeDirectoryPath==='services/orders'&&list(request.source.scope.sourceSetNames,['main'])
    &&[undefined,null,true].includes(request.source.scope.includeSubdirectories)
    &&(!request.steps||request.steps.length===0)&&request.terminalStep?.type==='EMIT';
  const declarationKinds=request?.source?.declarationKinds;
  const defaultFamilies=declarationKinds===undefined||declarationKinds===null;
  const supported={
    C1:request?.type==='RUN'&&request.source?.type==='SYMBOL_REFS'&&list(request.source.symbolRefs,['exact:v5:AAAAAAAAAAAAAAAAAAAAAQ'])
      &&request.steps?.length===1&&request.steps[0].type==='EXPAND_RELATION'&&request.steps[0].relation==='CALLERS'&&request.terminalStep?.type==='EMIT',
    C2:request?.type==='READ_SOURCE'&&request.candidateRef==='exact:v5:AAAAAAAAAAAAAAAAAAAAAg'&&request.region==='CALLABLE_BODY'&&request.text?.type==='COMPLETE',
    C3:search&&(defaultFamilies||Array.isArray(declarationKinds)&&declarationKinds.includes('CLASS')),
    C4:request?.type==='READ_RESULT'&&request.result==='result:v1:00000000-0000-0000-0000-000000000004'&&request.cursor===1
      &&[undefined,null,0].includes(request.evidence_cursor),
    C5:false,
    C6:search&&(defaultFamilies||Array.isArray(declarationKinds)&&declarationKinds.length===4&&['CLASS','FUNCTION','PROPERTY','TYPE_ALIAS'].every(kind=>declarationKinds.includes(kind)))&&request.terminalStep.output?.type==='SYMBOLS'&&Array.isArray(request.terminalStep.output.fields)
      &&request.terminalStep.output.fields.length===2&&request.terminalStep.output.fields.includes('NAME')&&request.terminalStep.output.fields.includes('SIGNATURE'),
  };
  if(!Object.hasOwn(supported,caseId))throw Error('UNKNOWN_REPLAY_CASE');
  if(!supported[caseId])return freeze({type:'unsupported',stdout:'',stderr:'kast replay: FIXTURE_TRANSITION_UNAVAILABLE\n',exitStatus:2});
  const canonicalPath=new URL(`./interface-replay/fixtures/${caseId}-transition.json`,import.meta.url).pathname;
  const canonicalResponse=readFileSync(canonicalPath,'utf8');
  return freeze({type:'supported',canonicalPath,canonicalResponse,stderr:'',exitStatus:0,fixtureSha256:sha(canonicalResponse)});
}

// The first screen cannot select these cases. Their inputs and purposes are
// declared before inference, without importing their answer annotations.
export function heldOutManifest() {
  const tasks=[
    'Find callers of cancel(id: String, retries: Int) in services/orders/src/main/kotlin/Orders.kt. Select its issued reference from the supplied overloads.',
    'Find callers of submit in services/orders/src/main/kotlin/Order23.kt. Use the issued reference from the larger declaration table.',
    'Does this nested qualified result prove that submit is absent from services/orders in main? Answer from this evidence without another command.',
    'Find callers of renderReceipt in services/orders/src/main/kotlin/Orders.kt. Preserve its issued reference despite the escaped source text.',
  ];
  return freeze(['different-overloads','larger-table','nested-qualified','source-escaping'].map((factor,index)=>{
    const id=`H${index+1}`,canonicalResponse=readFileSync(fixturePath(id),'utf8');
    return {id,factor,task:tasks[index],version:'interface-heldout-v1',canonicalPath:fixturePath(id).pathname,canonicalSha256:sha(canonicalResponse),status:'HELD_OUT_NOT_RUN'};
  }));
}
