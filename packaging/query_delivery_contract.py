"""Project the closed outer delivery schema into dependency-free JavaScript."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / 'cli/src/main/resources/query-delivery.schema.json'
MODULE = ROOT / 'cli/src/main/js/query-delivery-contract.mjs'
START = '// Generated outer delivery contract; owned by cli/src/main/resources/query-delivery.schema.json.'
END = '// End generated outer delivery contract.'


def render_contract(schema: dict) -> str:
    if schema.get('x-page-count-within-rpc-count') is not True:
        raise ValueError('Delivery must retain physical RPC accounting')
    supported = {'$schema', '$id', 'title', 'description', 'x-page-count-within-rpc-count', '$defs',
                 'oneOf', '$ref', 'type', 'required', 'properties', 'additionalProperties', 'enum',
                 'const', 'minimum', 'maximum', 'maxItems', 'items'}

    def check(node):
        if not isinstance(node, dict):
            raise ValueError('Delivery projection requires object schema nodes')
        if 'additionalProperties' in node and not isinstance(node['additionalProperties'], bool):
            raise ValueError('Delivery additionalProperties must remain boolean')
        if isinstance(node.get('type'), list) and any(kind in ('object', 'array') for kind in node['type']):
            raise ValueError('Delivery object and array constraints require direct types')
        assertions = {'$ref', 'oneOf', 'type', 'required', 'properties', 'additionalProperties',
                      'enum', 'const', 'minimum', 'maximum', 'maxItems', 'items'}
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
  return value;
}

/** Serialization owner for already admitted canonical replies; no inner document is projected or rewritten. */
function createQueryDeliveryEnvelope(initial, pages, delivery) {
  return admitQueryDeliveryEnvelope({ type: 'query_delivery', initial, pages, delivery });
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
