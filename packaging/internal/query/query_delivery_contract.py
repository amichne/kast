"""Project the closed delivery schema's semantic and presentation headers into protocol owners."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCHEMA = ROOT / 'cli/src/main/resources/query-delivery.schema.json'
SEMANTIC_KOTLIN = ROOT / 'protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QuerySemanticHeaderDocument.kt'
PRESENTATION_SCHEMA = ROOT / 'protocol/contract/src/main/resources/ide-hosted/query-presentation-header.schema.json'


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
    return ('// Generated from query-delivery.schema.json by packaging/internal/query/generate-public-query.py. Do not edit.\n'
            'package io.github.amichne.kast.protocol.contract\n\n'
            'import kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n\n' + '\n'.join(declarations))
