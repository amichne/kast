// Private evaluator data. Never import this module into the model-visible
// prefix or copy it into a shell filesystem.
const REF_STRING='exact:v5:AAAAAAAAAAAAAAAAAAAAAA';
const REF_LONG='exact:v5:AAAAAAAAAAAAAAAAAAAAAQ';
const REF_RETRIES='exact:v5:AAAAAAAAAAAAAAAAAAAAAg';
const RESULT='result:v1:00000000-0000-0000-0000-000000000004';
const OTHER_RESULT='result:v1:00000000-0000-0000-0000-000000000005';
const ORACLES=Object.freeze({
  C1:Object.freeze({action:'RUN',target:REF_LONG,relation:'CALLERS'}),
  C2:Object.freeze({action:'READ_SOURCE',target:REF_RETRIES,region:'CALLABLE_BODY',text:'COMPLETE'}),
  C3:Object.freeze({action:'RUN',name:'OrderService',directory:'services/orders',sourceSets:['main']}),
  C4:Object.freeze({action:'READ_RESULT',target:RESULT,cursor:1}),
  C5:Object.freeze({action:'finish',directory:'services/orders',sourceSets:['main'],exhaustive:false,limitations:['byte-limit-reached'],conclusion:'insufficient-evidence'}),
  C6:Object.freeze({action:'RUN',name:'OrderService',directory:'services/orders',sourceSets:['main'],fields:['NAME','SIGNATURE']}),
});
const arrayEqual=(a,b)=>Array.isArray(a)&&a.length===b.length&&a.every((value,index)=>value===b[index]);
const setEqual=(a,b)=>Array.isArray(a)&&a.length===new Set(a).size&&a.length===b.length&&a.every(value=>b.includes(value));
const plain=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const dimension=result=>result?'PASS':'FAIL';
const reasonFor=Object.freeze({arguments:'ARGUMENT_REJECTED',action:'WRONG_ACTION',target:'WRONG_TARGET',scope:'WRONG_SCOPE',reference:'WRONG_REFERENCE',coverage:'WRONG_COVERAGE'});

export function gradeReplayDecision(caseId,decision,admission) {
  const oracle=ORACLES[caseId];
  if(!oracle)throw Error('UNKNOWN_REPLAY_CASE');
  const dimensions={arguments:'NOT_APPLICABLE',action:'NOT_APPLICABLE',target:'NOT_APPLICABLE',scope:'NOT_APPLICABLE',reference:'NOT_APPLICABLE',coverage:'NOT_APPLICABLE'};
  const malformed=!plain(decision)||!['invoke','finish','clarify'].includes(decision.kind);
  if(malformed)return Object.freeze({type:'GRADED_INCORRECT',correct:false,dimensions:Object.freeze({...dimensions,action:'FAIL'}),reasons:Object.freeze(['MALFORMED_DECISION'])});
  if(decision.kind==='invoke') {
    const admitted=admission?.type==='admitted'||admission?.type==='ADMITTED';
    dimensions.arguments=dimension(admitted);
    const request=admission?.type==='ADMITTED'?admission.arguments?.request:admission?.request;
    dimensions.action=dimension(admitted&&plain(request)&&request.type===oracle.action);
    if(plain(request)) {
      switch(caseId) {
        case 'C1':
          dimensions.target=dimension(request.source?.type==='SYMBOL_REFS'&&arrayEqual(request.source.symbolRefs,[oracle.target]));
          dimensions.reference=dimension(request.source?.type==='SYMBOL_REFS'&&arrayEqual(request.source.symbolRefs,[oracle.target]));
          dimensions.action=dimension(request.type==='RUN'&&request.terminalStep?.type==='EMIT'&&Array.isArray(request.steps)&&request.steps.length===1&&request.steps[0].type==='EXPAND_RELATION'&&request.steps[0].relation===oracle.relation);
          break;
        case 'C2':
          dimensions.target=dimension(request.candidateRef===oracle.target);
          dimensions.reference=dimension(request.candidateRef===oracle.target);
          dimensions.action=dimension(request.type==='READ_SOURCE'&&request.region===oracle.region&&request.text?.type===oracle.text);
          break;
        case 'C3':
        case 'C6':
          dimensions.target=dimension(request.source?.type==='SEARCH_DECLARATIONS'&&request.source.declarationName===oracle.name&&(request.source.nameMatch===undefined||request.source.nameMatch===null||request.source.nameMatch==='EXACT'));
          dimensions.scope=dimension(request.source?.scope?.type==='DIRECTORY'&&request.source.scope.relativeDirectoryPath===oracle.directory&&setEqual(request.source.scope.sourceSetNames,oracle.sourceSets));
          dimensions.action=dimension(request.type==='RUN'&&request.terminalStep?.type==='EMIT'&&(!request.steps||request.steps.length===0)&&(request.terminalStep.output===undefined||request.terminalStep.output===null||request.terminalStep.output.type==='SYMBOLS'));
          if(caseId==='C6')dimensions.action=dimension(dimensions.action==='PASS'&&request.terminalStep.output?.type==='SYMBOLS'&&setEqual(request.terminalStep.output.fields,oracle.fields));
          break;
        case 'C4':
          dimensions.target=dimension(request.result===oracle.target);
          dimensions.reference=dimension(request.result===oracle.target&&request.cursor===oracle.cursor&&(request.evidence_cursor===undefined||request.evidence_cursor===null||request.evidence_cursor===0));
          break;
        case 'C5':dimensions.action='FAIL';break;
      }
    }
  } else if(caseId==='C5'&&decision.kind==='finish') {
    dimensions.action='PASS';
    dimensions.scope=dimension(decision.scope?.directory===oracle.directory&&setEqual(decision.scope?.sourceSets,oracle.sourceSets));
    dimensions.coverage=dimension(decision.conclusion===oracle.conclusion&&decision.exhaustive===oracle.exhaustive&&setEqual(decision.limitations,oracle.limitations));
  } else dimensions.action='FAIL';
  const reasons=Object.entries(dimensions).filter(([,value])=>value==='FAIL').map(([name])=>reasonFor[name]);
  return Object.freeze({type:reasons.length===0?'GRADED_CORRECT':'GRADED_INCORRECT',correct:reasons.length===0,dimensions:Object.freeze(dimensions),reasons:Object.freeze(reasons),...(decision.kind==='invoke'&&typeof admission?.cause==='string'?{productionCause:admission.cause}:{})});
}

// Independent controls are explicit annotations. No expected value is derived
// from a model decision, candidate render, parser output, or returned row order.
export function replayOracleControls(caseId) {
  const invoke={kind:'invoke'};
  const read=(ref,region='CALLABLE_BODY')=>({type:'READ_SOURCE',candidateRef:ref,region,text:{type:'COMPLETE'}});
  const search=(name='OrderService',directory='services/orders',sourceSets=['main'],fields)=>({type:'RUN',source:{type:'SEARCH_DECLARATIONS',declarationName:name,scope:{type:'DIRECTORY',relativeDirectoryPath:directory,sourceSetNames:sourceSets}},terminalStep:{type:'EMIT',...(fields?{output:{type:'SYMBOLS',fields}}:{})}});
  const callers=ref=>({type:'RUN',source:{type:'SYMBOL_REFS',symbolRefs:[ref]},steps:[{type:'EXPAND_RELATION',relation:'CALLERS'}],terminalStep:{type:'EMIT'}});
  const retained=(result=RESULT,cursor=1)=>({type:'READ_RESULT',result,cursor});
  const call=(name,request,command,argv)=>({name,decision:invoke,admission:{type:'admitted',request},command,argv});
  const misuse=(name,request,reason)=>({...call(name,request),reason});
  const rejectedCall=(name,command,argv,cause)=>({name,decision:invoke,admission:{type:'rejected',cause},command,argv,reason:'ARGUMENT_REJECTED'});
  const finish={kind:'finish',conclusion:'insufficient-evidence',scope:{directory:'services/orders',sourceSets:['main']},exhaustive:false,limitations:['byte-limit-reached']};
  const controls={
    C1:{accept:[call('long overload',callers(REF_LONG),`kast query --ref '${REF_LONG}' --relation callers`,['query','--ref',REF_LONG,'--relation','callers']),call('equivalent flag spelling',callers(REF_LONG),`kast --catalog=complete query --relation=callers --ref=${REF_LONG}`,['--catalog=complete','query','--relation=callers',`--ref=${REF_LONG}`])],reject:[misuse('valid reference for string overload',callers(REF_STRING),'WRONG_TARGET'),rejectedCall('signature substituted for reference',"kast query --ref 'fixture.orders.submit(kotlin.Long)' --relation callers",['query','--ref','fixture.orders.submit(kotlin.Long)','--relation','callers'],'INVALID_ARGUMENTS'),misuse('right target wrong relation',{...callers(REF_LONG),steps:[{type:'EXPAND_RELATION',relation:'CALLEES'}]},'WRONG_ACTION')]},
    C2:{accept:[call('complete callable body',read(REF_RETRIES),`kast source read --ref '${REF_RETRIES}' --region callable-body --text complete`,['source','read','--ref',REF_RETRIES,'--region','callable-body','--text','complete'])],reject:[misuse('valid reference for another declaration',read(REF_LONG),'WRONG_TARGET'),misuse('valid file region',read(REF_RETRIES,'FILE'),'WRONG_ACTION'),misuse('source text omitted',{...read(REF_RETRIES),text:{type:'NONE'}},'WRONG_ACTION')]},
    C3:{accept:[call('directory and main',search(),"kast query --name OrderService --in services/orders --source-set main",['query','--name','OrderService','--in','services/orders','--source-set','main']),call('equal options and exact match',{...search(),source:{...search().source,nameMatch:'EXACT'}},"kast query --source-set=main --in=services/orders --name=OrderService --name-match=exact",['query','--source-set=main','--in=services/orders','--name=OrderService','--name-match=exact'])],reject:[misuse('broader directory',search('OrderService','.'),'WRONG_SCOPE'),misuse('production and test',search('OrderService','services/orders',['main','test']),'WRONG_SCOPE'),misuse('different name',search('OtherService'),'WRONG_TARGET')]},
    C4:{accept:[call('next retained output page',retained(),`kast result read --ref '${RESULT}' --cursor 1`,['result','read','--ref',RESULT,'--cursor','1'])],reject:[misuse('fresh semantic query',callers(REF_STRING),'WRONG_ACTION'),misuse('different retained result',retained(OTHER_RESULT),'WRONG_TARGET'),misuse('wrong cursor',retained(RESULT,2),'WRONG_REFERENCE'),rejectedCall('semantic continuation as result',"kast result read --ref query:v1:00000000-0000-0000-0000-000000000004 --cursor 1",['result','read','--ref','query:v1:00000000-0000-0000-0000-000000000004','--cursor','1'],'INVALID_ARGUMENTS')]},
    C5:{accept:[{name:'qualified insufficient evidence',decision:finish}],reject:[{name:'false absence claim',decision:{...finish,conclusion:'absent'},reason:'WRONG_COVERAGE'},{name:'false exhaustive claim',decision:{...finish,exhaustive:true},reason:'WRONG_COVERAGE'},{name:'scope erased',decision:{...finish,scope:{directory:'.',sourceSets:['main','test']}},reason:'WRONG_SCOPE'},{name:'limitation erased',decision:{...finish,limitations:[]},reason:'WRONG_COVERAGE'},{name:'unnecessary invocation',decision:invoke,admission:{type:'admitted',request:search()},reason:'WRONG_ACTION'}]},
    C6:{accept:[call('field whitespace removed',search('OrderService','services/orders',['main'],['NAME','SIGNATURE']),"kast query --name OrderService --in services/orders --source-set main --fields 'name,signature'",['query','--name','OrderService','--in','services/orders','--source-set','main','--fields','name,signature']),call('field order preserves requested set',search('OrderService','services/orders',['main'],['SIGNATURE','NAME']),"kast query --fields=signature,name --source-set=main --in=services/orders --name=OrderService",['query','--fields=signature,name','--source-set=main','--in=services/orders','--name=OrderService'])],reject:[misuse('error removed by broader scope',search('OrderService','.', ['main'],['NAME','SIGNATURE']),'WRONG_SCOPE'),misuse('error removed by different target',search('OtherService','services/orders',['main'],['NAME','SIGNATURE']),'WRONG_TARGET'),misuse('requested signature omitted',search('OrderService','services/orders',['main'],['NAME']),'WRONG_ACTION'),{name:'original invalid fields',decision:invoke,admission:{type:'rejected',cause:'INVALID_FIELDS'},reason:'ARGUMENT_REJECTED'}]},
  }[caseId];
  if(!controls)throw Error('UNKNOWN_REPLAY_CASE');
  return controls;
}
