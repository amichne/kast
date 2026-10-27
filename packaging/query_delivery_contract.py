"""Project the closed outer delivery schema into dependency-free JavaScript."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'cli/src/main/resources/query-delivery.schema.json'
MODULE = ROOT / 'cli/src/main/js/query-delivery-contract.mjs'
SEMANTIC_KOTLIN = ROOT / 'protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QuerySemanticHeaderDocument.kt'
PRESENTATION_SCHEMA = ROOT / 'protocol/contract/src/main/resources/ide-hosted/query-presentation-header.schema.json'
START = '// Generated outer delivery contract; owned by cli/src/main/resources/query-delivery.schema.json.'
END = '// End generated outer delivery contract.'


def render_presentation_schema(schema: dict, public_read_schema: dict) -> str:
    import copy
    native = copy.deepcopy(schema['$defs']['QueryPresentationHeader'])
    native['required'].append('nextQuery')
    native['properties']['nextQuery'] = {'oneOf': [
        {key: value for key, value in public_read_schema.items() if key != '$defs'}, {'type': 'null'}]}
    native['required'].append('detail')
    detail_offset = public_read_schema['$defs']['DetailSelector']['properties']['offset']
    native['properties']['detail'] = {'oneOf': [
        {'type': 'object', 'additionalProperties': False, 'required': ['selector', 'end', 'total', 'text'],
         'properties': {'selector': {'$ref': '#/$defs/DetailSelector'}, 'end': detail_offset, 'total': detail_offset,
                        'text': {'type': 'string', 'maxLength': 32768}}}, {'type': 'null'}]}
    return json.dumps({'$schema': schema['$schema'], **native,
                       '$defs': {'SemanticHeader': schema['$defs']['SemanticHeader'], **public_read_schema.get('$defs', {})}}, indent=2) + '\n'


def render_semantic_kotlin(schema: dict) -> str:
    """Keep the MCP carrier and the portable header under the same closed schema."""
    declarations = []

    def name(value):
        return ''.join(part[:1].upper() + part[1:] for part in value.split('_'))

    def carrier(node, owner):
        if 'oneOf' in node:
            variants = []
            for branch in node['oneOf']:
                tag = branch['properties']['type']['const']
                variant = owner + name(tag.lower())
                variants.append(carrier(branch, variant))
                declarations[-1] = declarations[-1].replace('@Serializable\n', '@Serializable\n@SerialName(' + json.dumps(tag) + ')\n')
                rendered = declarations[-1].rstrip()
                last = rendered.splitlines()[-1]
                inheritance = ' : ' + owner
                declarations[-1] = rendered + (' :\n    ' + owner if last.startswith('data class ') and len(last + inheritance) > 120 else inheritance) + '\n'
            declarations.append('@Serializable sealed interface ' + owner + '\n')
            return owner
        if node.get('type') == 'object':
            fields = []
            for field, prop in node['properties'].items():
                if field == 'type' and 'const' in prop:
                    continue
                property_name = field.split('_')[0] + ''.join(name(part) for part in field.split('_')[1:])
                fields.append('    @SerialName(' + json.dumps(field) + ') val ' + property_name + ': ' + carrier(prop, owner + name(field)) + ',')
            if len(fields) == 1:
                parameter = fields[0].strip().removesuffix(',')
                compact = 'data class ' + owner + '(' + parameter + ')'
                declaration = compact if len(compact) <= 120 else 'data class ' + owner + '(\n    ' + parameter + '\n)'
            elif fields:
                declaration = 'data class ' + owner + '(\n' + '\n'.join(fields) + '\n)'
            else:
                declaration = 'data object ' + owner
            declarations.append('@Serializable\n' + declaration + '\n')
            return owner
        if 'enum' in node:
            declarations.append('@Serializable\nenum class ' + owner + ' {\n' + '\n'.join(
                '    @SerialName(' + json.dumps(value) + ') ' + value.upper() + (',' if len(node['enum']) > 1 else '') for value in node['enum']
            ) + '\n}\n')
            return owner
        types = node.get('type')
        nullable = isinstance(types, list) and 'null' in types
        kind = next((value for value in types if value != 'null'), None) if isinstance(types, list) else types
        if kind == 'array':
            return 'List<' + carrier(node['items'], owner + 'Item') + '>'
        scalar = {'integer': 'Long', 'string': 'ProtocolText', 'boolean': 'Boolean'}[kind]
        if owner.endswith('WorkspaceDigest'):
            scalar = 'QueryWorkspaceDigest'
        if owner.endswith('AuthorityRetainedResultResult') or owner.endswith('RecoveryReadResultResult'):
            scalar = 'QueryResultReference'
        return scalar + ('?' if nullable else '')

    carrier(schema['$defs']['SemanticHeader'], 'QuerySemanticHeaderDocument')
    return ('// Generated from query-delivery.schema.json by packaging/generate-public-query.py. Do not edit.\n'
            'package io.github.amichne.kast.protocol.contract\n\n'
            'import kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n\n' + '\n'.join(declarations))


def render_contract(schema: dict) -> str:
    if schema.get('x-page-count-within-rpc-count') is not True:
        raise ValueError('Delivery must retain physical RPC accounting')
    supported = {'$schema', '$id', 'title', 'description', 'x-page-count-within-rpc-count', '$defs',
                 'oneOf', '$ref', 'type', 'required', 'properties', 'additionalProperties', 'enum',
                 'const', 'minimum', 'maximum', 'maxItems', 'items', 'maxLength', 'pattern'}

    def check(node):
        if not isinstance(node, dict):
            raise ValueError('Delivery projection requires object schema nodes')
        if 'additionalProperties' in node and not isinstance(node['additionalProperties'], bool):
            raise ValueError('Delivery additionalProperties must remain boolean')
        if isinstance(node.get('type'), list) and any(kind in ('object', 'array') for kind in node['type']):
            raise ValueError('Delivery object and array constraints require direct types')
        if set(node) & {'required', 'properties', 'additionalProperties'} and node.get('type') != 'object':
            raise ValueError('Delivery object assertions require a direct object type')
        if set(node) & {'items', 'maxItems'} and node.get('type') != 'array':
            raise ValueError('Delivery array assertions require a direct array type')
        assertions = {'$ref', 'oneOf', 'type', 'required', 'properties', 'additionalProperties',
                      'enum', 'const', 'minimum', 'maximum', 'maxItems', 'items', 'maxLength', 'pattern'}
        for exclusive in ('$ref', 'oneOf'):
            if exclusive in node and (set(node) & assertions) - {exclusive}:
                raise ValueError(f'Delivery {exclusive} cannot retain unenforced assertion siblings')
        if set(node) - supported:
            raise ValueError(f'Unsupported delivery schema keywords: {set(node) - supported}')
        if '$ref' in node and (not node['$ref'].startswith('#/$defs/') or
                              node['$ref'][len('#/$defs/'):] not in schema.get('$defs', {})):
            raise ValueError('Delivery references must resolve to a local definition')
        for key in ('$defs', 'properties'):
            for child in node.get(key, {}).values():
                check(child)
        for child in node.get('oneOf', []):
            check(child)
        if 'items' in node:
            check(node['items'])

    check(schema)
    return START + '\nconst QUERY_DELIVERY_SCHEMA = ' + json.dumps(schema, separators=(',', ':')) + ';\n' + r'''
const queryDeliveryInvalid = reason => ({ type: 'invalid', reason });
function queryDeliveryMatches(value, node) {
  if (node.$ref) return queryDeliveryMatches(value, QUERY_DELIVERY_SCHEMA.$defs[node.$ref.slice('#/$defs/'.length)]);
  if (node.oneOf) return node.oneOf.filter(branch => queryDeliveryMatches(value, branch)).length === 1;
  if ('const' in node && value !== node.const || node.enum && !node.enum.includes(value)) return false;
  const types = Array.isArray(node.type) ? node.type : node.type ? [node.type] : [];
  const matchesType = type => type === 'null' ? value === null
    : type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value)
    : type === 'array' ? Array.isArray(value)
    : type === 'integer' ? Number.isSafeInteger(value) : typeof value === type;
  if (types.length && !types.some(matchesType)) return false;
  if (node.minimum != null && value < node.minimum || node.maximum != null && value > node.maximum) return false;
  if (node.maxLength != null && typeof value === 'string' && Array.from(value).length > node.maxLength) return false;
  if (node.pattern != null && typeof value === 'string' && !new RegExp(node.pattern).test(value)) return false;
  if (node.type === 'object') {
    if (node.required.some(key => !Object.hasOwn(value, key))) return false;
    if (node.additionalProperties === false && Object.keys(value).some(key => !Object.hasOwn(node.properties, key))) return false;
    for (const [key, child] of Object.entries(node.properties)) {
      if (Object.hasOwn(value, key) && !queryDeliveryMatches(value[key], child)) return false;
    }
  }
  if (node.type === 'array' && (value.length > node.maxItems || Array.from(value).some(item => !queryDeliveryMatches(item, node.items)))) return false;
  return true;
}

/** Outer admission preserves opaque canonical replies; their authority stays with the RPC owner. */
function admitQueryDeliveryEnvelope(value) {
  if (!queryDeliveryMatches(value, QUERY_DELIVERY_SCHEMA)) return queryDeliveryInvalid('INVALID_OUTER_SHAPE');
  if (value.pages.length + 1 > value.delivery.rpc_count) return queryDeliveryInvalid('INVALID_RPC_ACCOUNTING');
  if(value.semantic.original_outcome!==(value.initial?.type??value.delivery.original_outcome)) return queryDeliveryInvalid('SEMANTIC_OUTCOME_MISMATCH');
  return value;
}

/** Serialization owner for already admitted canonical replies; no inner document is projected or rewritten. */
function createQueryDeliveryEnvelope(initial, pages, delivery, semantic) {
  return admitQueryDeliveryEnvelope({ type: 'query_delivery', initial, pages, delivery,
    semantic: semantic ?? querySemanticHeader(initial, pages, delivery) });
}

// The RPC owner admits the input. This projection copies finite facts, never a question or witness tree.
function querySemanticHeader(initial, pages, delivery) {
  if (!initial) return null;
  const document=initial.document ?? {};
  const rejection=document.rejection;
  const detail=rejection?.detail;
  const evidence=detail?.evidence?.type==='RETAINED'?detail.evidence:null;
  const question=document.question??evidence?.question;
  const interpretation=document.interpretation;
  const originalCoverage=detail?.originalCoverage??interpretation?.originalCoverage;
  const coverage=originalCoverage??document.coverage;
  const progress=coverage?.progress??document.qualification?.progress;
  const replies=[initial,...pages];
  const documents=replies.map(reply=>reply.document).filter(Boolean);
  const families=['failures','omissions','walk_observations','reference_observations','discovery_observations','relation_observations'];
  const entries=field=>documents.flatMap(value=>Array.isArray(value[field])?value[field]:[]);
  const code=value=>typeof value==='string'?value:value?.type??null;
  const codes=values=>[...new Set(values.map(code).filter(value=>value!==null))];
  const failures=entries('failures'),omissions=entries('omissions');
  const original=document.original_evidence_summary??detail?.original_evidence_summary??detail?.evidence?.original_evidence_summary_state?.summary;
  const originalFailureCodes=original?Object.entries(original).filter(([key])=>key!=='omission_reasons'&&(key.endsWith('_reasons')||key==='source_limitations')).flatMap(([,value])=>value):[];
  const result=delivery.result??evidence?.result??(document.retention?.kind==='retained'?document.retention.reference:null);
  const authorityRejected=documents.some(value=>value.qualification?.progress?.type==='retention_unavailable'||
    value.rejection?.type==='execution-rejected'&&['result-unavailable','result-stale-basis'].includes(value.rejection.reason));
  const window=documents.map(value=>value.evidence_window).filter(Boolean).at(-1);
  const rows=documents.reduce((total,value)=>total+(value.items?.length??0),0);
  const deferred=documents.reduce((total,value)=>total+(value.items??[]).filter(item=>item.type==='DEFERRED_DETAIL').length+(value.deferred_evidence?.length??0),0);
  const evidencePresented=families.reduce((total,field)=>total+entries(field).length,0)+
    documents.reduce((total,value)=>total+(value.relation_graph?.reference_observations?.length??0),0);
  const nextCursor=documents.map(value=>value.next_cursor).filter(value=>value!=null).at(-1)??rows;
  const continuation=documents.map(value=>value.continuation??value.qualification?.progress?.checkpoint?.token).filter(Boolean).at(-1);
  const live=documents.map(value=>value.live).find(Boolean);
  return {
    original_outcome:initial.type,
    completion:{policy:question?.completion?.type??'UNAVAILABLE',model:detail?.model??interpretation?.model??question?.completion?.model??'UNAVAILABLE',
      interpretation:interpretation?.type??null,cause:code(detail?.cause??interpretation?.cause)},
    authority:result?{type:'RETAINED_RESULT',result}:live?{type:'LIVE',root:live.root,host:live.host,epoch:live.epoch,contentView:live.contentView,version:live.version}:{type:'UNAVAILABLE'},
    coverage:{type:coverage?.type??(coverage?.exhaustive===true?'COMPLETE':coverage?.exhaustive===false?'QUALIFIED':'UNKNOWN'),
      exhaustive:coverage?.exhaustive??null,known_minimum:coverage?.knownMinimum??document.qualification?.knownMinimum??null,limitations:codes(coverage?.limitations??document.qualification?.limitations??[]),
      progress:{type:progress?.type??null,reason:progress?.reason??null,next_action:progress?.next_action??null}},
    blockers:{failure:initial.failure??null,rejection:rejection?.type??null,cause:code(detail?.cause??interpretation?.cause),
      stop:code(detail?.stop??document.invocation?.stop),qualification:document.qualification?.progress?.type??null,
      codes:codes([...failures.map(value=>value.reason??value.type),...originalFailureCodes]),
      failure_count:original?.failure_count??null,kinds:codes(original?.failure_kinds??[])},
    omissions:{count:original?.omission_count??null,codes:codes(original?.omission_reasons??omissions.map(value=>value.reason??value.evidence?.reason??value.type))},
    presentation:{rows,accumulated_rows:document.invocation?.accumulated_row_count??document.row_window?.total??null,evidence_presented:evidencePresented,
      evidence_total:original?.evidence_count??window?.total??null,detail:authorityRejected?'UNAVAILABLE':delivery.stop!=='DELIVERED'||deferred||documents.some(value=>value.detail!=null)?'DEFERRED_DETAIL':'INLINE',deferred_count:deferred,
      detail_read:documents.some(value=>value.detail!=null)?(documents.filter(value=>value.detail!=null).at(-1).detail.end===documents.filter(value=>value.detail!=null).at(-1).detail.total?'FINAL_FRAGMENT':'FRAGMENT'):'NONE'},
    recovery:authorityRejected?{type:'UNAVAILABLE',reason:'AUTHORITY_REJECTED'}:result?{type:'READ_RESULT',result,cursor:nextCursor,evidence_cursor:window?.end??0}:
      continuation?{type:'RESUME',continuation}:{type:'UNAVAILABLE',reason:'NO_RETAINED_AUTHORITY'},
  };
}

function encodeQueryDeliveryEnvelope(value) {
  const admitted = admitQueryDeliveryEnvelope(value);
  return admitted.type === 'invalid' ? admitted : { type: 'encoded', json: JSON.stringify(admitted) };
}
''' + END + '\n'


def embed_contract(source: str, contract: str) -> str:
    import re
    if START not in source:
        return contract + source
    result, count = re.subn(re.escape(START) + '.*?' + re.escape(END) + '\n',
                            lambda _: contract, source, flags=re.DOTALL)
    if count != 1:
        raise ValueError('Query delivery needs exactly one generated outer contract')
    return result
