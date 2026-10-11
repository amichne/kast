#!/usr/bin/env python3
"""Generate current public-tool DTOs and provider projections from one schema.

This deliberately supports only this contract's closed objects, local refs,
nullable primitives/collections and tagged unions. Unsupported forms fail closed.
It does not generate semantic admission or compiler authority.
"""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path
from query_delivery_contract import MODULE as DELIVERY_MODULE, SCHEMA as DELIVERY_SCHEMA, embed_contract, render_contract

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query'
KOTLIN = ROOT / 'app-server/src/main/kotlin/io/github/amichne/kast/appserver/query'
HEADER = '''// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

'''


def tagged_type(node: dict, kind: str) -> bool:
    value = node.get('type')
    return value == kind or isinstance(value, list) and kind in value


def nullable(node: dict) -> bool:
    return (isinstance(node.get('type'), list) and 'null' in node['type']) or any(
        branch.get('type') == 'null' for branch in node.get('anyOf', [])
    )


def enum_entry(value: str) -> str:
    return value.replace('-', '_').upper()


def kotlin_description_literals(value: str) -> str:
    """Bound generated source lines by escaped width while preserving every character."""
    maximum_encoded_width = 100
    literals = []
    start = 0
    encoded_width = 2
    for offset, character in enumerate(value):
        character_width = len(json.dumps(character)) - 2
        if encoded_width + character_width > maximum_encoded_width:
            literals.append(json.dumps(value[start:offset]))
            start = offset
            encoded_width = 2
        encoded_width += character_width
    literals.append(json.dumps(value[start:]))
    return ' +\n            '.join(literals)


def validate_authority(authority: dict) -> None:
    """Keep one discriminator and enum convention in the authored contract."""
    if not isinstance(authority.get('contractVersion'), int) or authority['contractVersion'] < 1:
        raise ValueError('Public tool contract requires a positive version')
    definitions = authority['$defs']
    support = authority.get('supportTools', [])
    names = [tool['name'] for tool in [*authority['tools'], *support]]
    if len(names) != len(set(names)):
        raise ValueError('Duplicate public tool identity')
    for tool in authority['tools']:
        if tool['operation'] not in {'query.run', 'diagnostic.check', 'change'}:
            raise ValueError(f'Unbound advertised tool: {tool["name"]}')
    support_bindings = {
        'workspace_lifecycle': ('WorkspaceLifecycleToolInput', ['APP_SERVER']),
        'health_check': ('McpHealthRequest', ['MCP', 'RPC']),
    }
    if {tool['name'] for tool in support} != set(support_bindings):
        raise ValueError('Support tools must have complete, known bindings')
    for tool in support:
        if (tool.get('binding'), tool.get('hosts')) != support_bindings[tool['name']]:
            raise ValueError(f'Unbound support tool: {tool["name"]}')
        if not tool.get('description'):
            raise ValueError(f'Support tool lacks a description: {tool["name"]}')

    def visit(node: object, path: str) -> None:
        if isinstance(node, list):
            for index, child in enumerate(node):
                visit(child, f'{path}[{index}]')
            return
        if not isinstance(node, dict):
            return
        if '$ref' in node:
            reference = node['$ref']
            if not reference.startswith('#/$defs/') or reference[8:] not in definitions:
                raise ValueError(f'{path}: unresolved public schema reference {reference}')
        if 'enum' in node:
            values = node['enum']
            if not values or any(value is not None and
                                 (not isinstance(value, str) or not re.fullmatch(r'[A-Z][A-Z0-9_]*', value))
                                 for value in values):
                raise ValueError(f'{path}: enum values must be CAPS_CASE')
            if len(values) == 1 and ('default' not in node or node['default'] != values[0]):
                raise ValueError(f'{path}: singleton enum requires its value as default')
        if tagged_type(node, 'object') and 'properties' in node:
            properties = node['properties']
            if any(nullable(properties[name]) for name in node.get('required', [])):
                raise ValueError(f'{path}: required nullable field conflicts with default normalization')
            if 'action' in properties:
                raise ValueError(f'{path}: discriminator must be named type')
            if any(len(prop.get('enum', [])) == 1 for prop in properties.values()):
                tag = properties.get('type')
                if tag is None or len(tag.get('enum', [])) != 1 or 'type' not in node.get('required', []):
                    raise ValueError(f'{path}: tagged object requires a required type discriminator')
            for name, property_schema in properties.items():
                control = name.startswith(('max', 'maximum', 'timeout')) or name in {
                    'steps', 'limit', 'beforeLines', 'afterLines'
                }
                if not control:
                    continue
                if '$ref' in property_schema:
                    reference = property_schema['$ref']
                    if not reference.startswith('#/$defs/') or reference[8:] not in definitions:
                        raise ValueError(f'{path}.{name}: unresolved public schema reference {reference}')
                    resolved = definitions[reference[8:]]
                else:
                    resolved = property_schema
                if name in node.get('required', []):
                    raise ValueError(f'{path}.{name}: defaultable control must permit omission')
                if 'default' not in resolved and node.get('x-default-owner') != 'ExecutionBudgetDocument.requested':
                    raise ValueError(f'{path}.{name}: missing public control default')
                if 'default' in resolved:
                    value = resolved['default']
                    if isinstance(value, bool) or isinstance(value, int):
                        if isinstance(value, int) and (value < resolved.get('minimum', value) or value > resolved.get('maximum', value)):
                            raise ValueError(f'{path}.{name}: invalid numeric default')
                    elif value != [] and not (value is None and nullable(resolved)):
                        raise ValueError(f'{path}.{name}: invalid control default')
        for key, child in node.items():
            visit(child, f'{path}.{key}')
    visit(authority['$defs'], '$defs')
    visit(authority['tools'], 'tools')


def project(schema: dict, *, strict: bool) -> dict:
    """Provider syntax projection, NOT the server's full admission schema.

    Drop only named authoring annotations. The strict profile additionally drops
    length/uniqueness keywords not listed in the targeted supported keyword set.
    The full admission schema and Kotlin canonical admission still enforce them.
    """
    drop = {'$schema', '$id', 'discriminator', 'examples', 'title', 'x-kotlin-type', 'x-default-owner'}
    if strict:
        drop |= {'default', 'uniqueItems', 'minLength', 'maxLength'}
    # Traverse schema positions only; property names are data, never keywords.
    result = {key: value for key, value in schema.items() if key not in drop}
    for table in ('properties', '$defs'):
        if table in result:
            result[table] = {key: project(value, strict=strict) for key, value in result[table].items()}
    if 'items' in result:
        result['items'] = project(result['items'], strict=strict)
    for keyword in ('anyOf',):
        if keyword in result:
            result[keyword] = [project(value, strict=strict) for value in result[keyword]]
    if tagged_type(result, 'object') and 'properties' in result:
        originally_required = set(schema.get('required', []))
        for name, property_schema in list(result['properties'].items()):
            if name in originally_required or nullable(property_schema) or name == 'verbose':
                continue
            # Verbose is omission-only: every provider must still emit a true JSON boolean.
            # Optional direct controls may be omitted. Strict providers require all
            # object keys, so their null spelling must have the same admission meaning.
            if 'type' in property_schema and isinstance(property_schema['type'], str):
                property_schema['type'] = [property_schema['type'], 'null']
                if 'enum' in property_schema:
                    property_schema['enum'] = [*property_schema['enum'], None]
            else:
                result['properties'][name] = {'anyOf': [property_schema, {'type': 'null'}]}
    if strict and tagged_type(result, 'object'):
        result['required'] = list(result['properties'])
    return result


def referenced_schema(root: dict, definitions: dict) -> dict:
    """Retain exactly the reachable schema definitions in each tool prompt."""
    reached = set()
    def visit(node):
        if isinstance(node, dict):
            reference = node.get('$ref')
            if reference is not None:
                prefix = '#/$defs/'
                if not reference.startswith(prefix) or reference[len(prefix):] not in definitions:
                    raise ValueError('Unsupported public tool schema reference')
                name = reference[len(prefix):]
                if name not in reached:
                    reached.add(name)
                    visit(definitions[name])
            for value in node.values():
                visit(value)
        elif isinstance(node, list):
            for value in node:
                visit(value)
    visit(root)
    return dict(root, **({'$defs': {name: schema for name, schema in definitions.items() if name in reached}} if reached else {}))


def render_tools(authority: dict) -> dict[Path, str]:
    """Generate this closed facade slice with tagged union variants."""
    validate_authority(authority)
    import copy
    definitions = copy.deepcopy(authority['$defs'])
    roots = {''.join(part.title() for part in tool['name'].split('_')): tool['schema'] for tool in authority['tools']}
    enums = {}
    unions = {}
    for name, schema in definitions.items():
        if 'x-kotlin-type' in schema:
            continue
        if 'anyOf' not in schema:
            continue
        variants = [branch['$ref'].split('/')[-1] for branch in schema['anyOf'] if '$ref' in branch]
        if not variants or any(branch != {'type': 'null'} and '$ref' not in branch
                               for branch in schema['anyOf']):
            raise ValueError(f'Unsupported public union: {name}')
        if len(variants) != len(set(variants)) or any(variant not in definitions for variant in variants):
            raise ValueError(f'Invalid public union membership: {name}')
        unions[name] = variants
    objects = {**{key: value for key, value in definitions.items()
                  if 'x-kotlin-type' not in value and key not in unions}, **roots}
    parents = {}
    for parent, children in unions.items():
        for child in children:
            parents.setdefault(child, []).append(parent)
    def typ(spec, prop):
        if '$ref' in spec:
            key = spec['$ref'].split('/')[-1]
            external = definitions.get(key, {})
            if 'x-kotlin-type' in external:
                return external['x-kotlin-type'] + ('?' if nullable(external) else '')
            return 'PublicTool' + key + ('?' if nullable(external) else '')
        if 'anyOf' in spec:
            branches = [s['$ref'].split('/')[-1] for s in spec['anyOf'] if '$ref' in s]
            if len(branches) == 1 and len(spec['anyOf']) == 2 and nullable(spec):
                return typ({'$ref': '#/$defs/' + branches[0]}, prop) + '?'
            union = next(key for key, values in unions.items() if values == branches)
            return 'PublicTool' + union + ('?' if any(s.get('type') == 'null' for s in spec['anyOf']) else '')
        nullable_suffix = '?' if nullable(spec) else ''
        if 'enum' in spec:
            key = prop[0].upper() + prop[1:]
            values = [item for item in spec['enum'] if item is not None]
            if key in enums and enums[key] != values:
                raise ValueError(f'Conflicting facade enum {key}')
            enums[key] = values
            return 'PublicTool' + key + nullable_suffix
        if tagged_type(spec, 'array'):
            return 'BoundedProtocolList<' + typ(spec['items'], prop) + '>' + nullable_suffix
        if tagged_type(spec, 'integer') and spec.get('maximum', 0) > 2147483647:
            return 'Long' + nullable_suffix
        for kind, kotlin in [('string', 'ProtocolText'), ('boolean', 'Boolean'), ('integer', 'Int')]:
            if tagged_type(spec, kind): return kotlin + nullable_suffix
        raise ValueError(f'Unsupported facade schema {spec}')
    lines = [HEADER.replace('@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)',
                      '@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)\n@file:Suppress("ConstructorParameterNaming")'),
             'import io.github.amichne.kast.protocol.registry.PublicToolIdentity\n',
             'import kotlinx.serialization.json.*\n\n',
             'internal sealed interface PublicToolDocument { val verbose: Boolean }\n\n']
    body = []
    discovery_body = []
    impact_body = []
    trace_body = []
    discovery_objects = {'DirectoryScope', 'PackageScope', 'LocationSource', 'SearchSource', 'TextSource', 'AllSource'}
    for key, spec in objects.items():
        object_body = trace_body if key == 'Trace' else impact_body if key.startswith('Impact') else discovery_body if key in discovery_objects else body
        inherited = parents.get(key, [])
        discriminator = 'type'
        props = [(p,s) for p,s in spec['properties'].items() if p != discriminator]
        if key in roots:
            props.sort(key=lambda prop: prop[0] == 'verbose')
        suffix = (' : ' + ', '.join('PublicTool' + parent for parent in inherited)) if inherited else (
            ' : PublicToolDocument' if key in roots else ''
        )
        annotation = '@Serializable\n'
        if inherited:
            annotation += '@SerialName(' + json.dumps(spec['properties'][discriminator]['enum'][0]) + ')\n'
        if not props:
            declaration = f'internal data object PublicTool{key}{suffix}'
            inline_annotation = annotation.replace('\n', ' ')
            object_annotation = (inline_annotation if key.startswith('Impact') and
                                 len(inline_annotation + declaration) <= 120 else annotation)
            object_body.append(object_annotation + declaration + '\n\n')
        else:
            object_body.append(annotation + f'internal data class PublicTool{key}(\n')
            for prop, value in props:
                resolved = definitions[value['$ref'].split('/')[-1]] if '$ref' in value else value
                default = ''
                field_type = typ(value, prop)
                if prop not in spec.get('required', []):
                    if field_type.endswith('?'):
                        default = ' = null'
                    elif isinstance(resolved.get('default'), bool):
                        if prop == 'verbose' and key in roots:
                            default = ' = ' + str(resolved['default']).lower()
                        else:
                            field_type += '?'
                            default = ' = null'
                    elif isinstance(resolved.get('default'), int):
                        if resolved.get('x-kotlin-type') == 'ProtocolCount' or field_type == 'Int':
                            field_type += '?'
                            default = ' = null'
                        else:
                            raise ValueError(f'Unsupported numeric default: {key}.{prop}')
                    else:
                        raise ValueError(f'Optional facade field lacks a supported default: {key}.{prop}')
                parameter = prop
                if 'x-kotlin-type' in resolved and '_' in prop:
                    parameter = prop.split('_')[0] + ''.join(part.title() for part in prop.split('_')[1:])
                    object_body.append(f'    @SerialName({json.dumps(prop)})\n    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)\n')
                modifier = 'override ' if prop == 'verbose' and key in roots else ''
                comma = '' if key.startswith('Impact') and len(props) == 1 else ','
                declaration = f'    {modifier}val {parameter}: {field_type}{default}{comma}\n'
                if len(declaration.rstrip()) > 120 and default == ' = null':
                    object_body.append(f'    {modifier}val {parameter}:\n        {field_type} =\n        null{comma}\n')
                else:
                    object_body.append(declaration)
            object_body.append(')' + suffix + '\n\n')
    for key, values in enums.items():
        lines.append(f'@Serializable\ninternal enum class PublicTool{key} {{\n')
        for value in values:
            lines.append(f'    @SerialName({json.dumps(value)}) {enum_entry(value)},\n')
        lines.append('}\n\n')
    for union in unions:
        lines.append(f'@Serializable\ninternal sealed interface PublicTool{union}\n\n')
    lines += body
    # Defaults are data in the authority and are compiled here, never copied into a provider.
    query_tool = next(tool for tool in authority['tools'] if tool['name'] == 'query_symbols')
    defaults = query_tool['defaults']
    def text_value(value): return 'toolDefault(ProtocolText.parse(' + json.dumps(value) + '))'
    def bounded(values): return 'toolDefault(BoundedProtocolList.create(listOf(' + ', '.join(values) + ')))'
    lines.append('internal object PublicToolDefaults {\n')
    lines.append('    val nameMatch = PublicToolNameMatch.' + enum_entry(defaults['nameMatch']) + '\n')
    lines.append('    const val includeSubdirectories = ' + str(definitions['DirectoryScope']['properties']['includeSubdirectories']['default']).lower() + '\n')
    lines.append('    const val includeSubpackages = ' + str(definitions['PackageScope']['properties']['includeSubpackages']['default']).lower() + '\n')
    lines.append('    const val maximumEdgesPerNode = ' + str(definitions['BoundedFanOutStrategy']['properties']['maximumEdgesPerNode']['default']) + '\n')
    lines.append('    val sourceSets = ' + bounded([text_value(s) for s in defaults['sourceSetNames']]) + '\n')
    lines.append('    val declarationKinds = ' + bounded(['PublicToolDeclarationKinds.' + enum_entry(s) for s in defaults['declarationKinds']]) + '\n')
    output = defaults['output']
    if output['type'] != 'SYMBOLS': raise ValueError('Only symbols default output is supported')
    lines.append('    val output: QueryOutputDocument.Symbols =\n')
    lines.append('        QueryOutputDocument.Symbols(\n')
    lines.append('            toolDefault(\n')
    lines.append('                BoundedProtocolList.create(\n')
    lines.append('                    listOf(\n')
    for field in output['fields']:
        lines.append('                        QuerySymbolFieldDocument.' + enum_entry(field) + ',\n')
    lines.append('                    )\n')
    lines.append('                )\n')
    lines.append('            )\n')
    lines.append('        )\n')
    if definitions['RunAction']['properties']['steps'].get('default') != []:
        raise ValueError('Only empty default transformations are supported')
    lines.append('    val steps: BoundedProtocolList<PublicToolStep> = toolDefault(BoundedProtocolList.create(emptyList()))\n')
    diagnostic = next(tool for tool in authority['tools'] if tool['name'] == 'check_diagnostics')
    lines.append(f'    const val maxDiagnostics = {diagnostic["schema"]["properties"]["maxDiagnostics"]["default"]}\n')
    if definitions['ExecutionBudget'].get('default') != {} or \
            definitions['ExecutionBudget'].get('x-default-owner') != 'ExecutionBudgetDocument.requested':
        raise ValueError('Execution budget must use the typed policy default owner')
    lines.append('    val executionBudget = PublicToolExecutionBudget()\n')
    scope = defaults['scope']
    if scope['type'] != 'DIRECTORY': raise ValueError('Only directory default scope is supported')
    if scope['sourceSetNames'] != defaults['sourceSetNames']: raise ValueError('Default scope source sets disagree')
    lines.append('    val scope: PublicToolScope = PublicToolDirectoryScope(' + text_value(scope['relativeDirectoryPath']) + ', ' + str(scope['includeSubdirectories']).lower() + ', sourceSets)\n}\n\n')
    lines.append('internal fun decodePublicTool(identity: PublicToolIdentity, raw: JsonElement, json: Json): PublicToolDocument = when (identity) {\n')
    for tool, key in zip(authority['tools'], roots):
        lines.append(f'    PublicToolIdentity.{enum_entry(tool["name"])} -> json.decodeFromJsonElement(PublicTool{key}.serializer(), raw)\n')
    lines.append('}\n\ninternal fun encodePublicTool(value: PublicToolDocument, json: Json): JsonElement = when (value) {\n')
    for key in roots:
        lines.append(f'    is PublicTool{key} -> json.encodeToJsonElement(PublicTool{key}.serializer(), value)\n')
    lines.append('}\n\nprivate fun <T> toolDefault(value: Refinement<T, *>): T = when (value) {\n    is Refinement.Refined -> value.value\n    is Refinement.Rejected -> error("Invalid authored public tool default")\n}\n')
    document_source = ''.join(lines)
    outputs = {
        KOTLIN / 'PublicToolDocuments.kt': document_source,
        KOTLIN / 'PublicToolTrace.kt': HEADER + ''.join(trace_body).rstrip() + '\n',
        KOTLIN / 'PublicToolDiscoveryDocuments.kt':
            '// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.\n'
            'package io.github.amichne.kast.appserver.query\n\n'
            'import io.github.amichne.kast.protocol.contract.BoundedProtocolList\n'
            'import io.github.amichne.kast.protocol.contract.ProtocolText\n'
            'import kotlinx.serialization.SerialName\n'
            'import kotlinx.serialization.Serializable\n\n' + ''.join(discovery_body).rstrip() + '\n',
        KOTLIN / 'PublicToolImpactDocuments.kt':
            '// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.\n'
            'package io.github.amichne.kast.appserver.query\n\n'
            'import io.github.amichne.kast.protocol.contract.BoundedProtocolList\n'
            'import io.github.amichne.kast.protocol.contract.ProtocolText\n'
            'import kotlinx.serialization.SerialName\n'
            'import kotlinx.serialization.Serializable\n\n' + ''.join(impact_body).rstrip() + '\n',
    }
    identity_lines = ['// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.\n',
                      'package io.github.amichne.kast.protocol.registry\n\n',
                      'import io.github.amichne.kast.protocol.contract.CanonicalOperation\n\n',
                      f'const val PUBLIC_TOOL_CONTRACT_VERSION = {authority["contractVersion"]}\n',
                      'const val PUBLIC_TOOL_NAMESPACE_DESCRIPTION = ' + json.dumps(authority['namespaceDescription']) + '\n\n',
                      '/** Closed presentation identities; canonical operations retain effect and budget ownership. */\n',
                      'enum class PublicToolIdentity(\n'
                      '    val toolName: String,\n'
                      '    val operation: CanonicalOperation,\n'
                      '    val description: String,\n'
                      '    val loading: HostedToolLoading,\n'
                      ') {\n']
    operations = {'query.run': 'QUERY_RUN', 'diagnostic.check': 'DIAGNOSTIC_CHECK', 'change': 'CHANGE'}
    for tool in authority['tools']:
        description = kotlin_description_literals(tool['description'])
        identity_lines.append(
            f'    {enum_entry(tool["name"])}({json.dumps(tool["name"])}, CanonicalOperation.{operations[tool["operation"]]},\n'
            f'        {description},\n'
            f'        HostedToolLoading.{"DEFERRED" if tool["deferLoading"] else "EAGER"},\n'
            '    ),\n'
        )
    identity_lines.append('}\n')
    outputs[ROOT / 'protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt'] = ''.join(identity_lines)
    support_lines = [
        '// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.\n',
        'package io.github.amichne.kast.protocol.registry\n\n',
        'enum class SupportToolHost {\n    APP_SERVER,\n    MCP,\n    RPC,\n}\n\n',
        'enum class SupportToolIdentity(\n'
        '    val toolName: String,\n'
        '    val description: String,\n'
        '    val binding: String,\n'
        '    val hosts: Set<SupportToolHost>,\n'
        ') {\n',
    ]
    for tool in authority['supportTools']:
        description = kotlin_description_literals(tool['description'])
        hosts = ', '.join('SupportToolHost.' + host for host in tool['hosts'])
        support_lines.append(
            f'    {enum_entry(tool["name"])}(\n'
            f'        {json.dumps(tool["name"])},\n'
            f'        {description},\n'
            f'        {json.dumps(tool["binding"])},\n'
            f'        setOf({hosts}),\n'
            '    ),\n'
        )
    support_lines.append('}\n')
    outputs[ROOT / 'protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/SupportToolIdentity.kt'] = ''.join(support_lines)
    # ToolRpcFailure is the runtime owner; project its closed values into single-file adapters.
    rpc_owner = ROOT / 'cli/src/main/kotlin/io/github/amichne/kast/cli/rpc/KastToolRpcMain.kt'
    failure_match = re.search(r'internal enum class ToolRpcFailure \{([^}]+)\}', rpc_owner.read_text())
    if failure_match is None or not re.fullmatch(r'[\sA-Z_,]+', failure_match.group(1)):
        raise ValueError('ToolRpcFailure must remain a closed enum of CAPS_CASE entries')
    failures = [entry.strip() for entry in failure_match.group(1).split(',') if entry.strip()]
    if not failures or len(failures) != len(set(failures)):
        raise ValueError('ToolRpcFailure must have unique finite entries')
    contract = render_contract(json.loads(DELIVERY_SCHEMA.read_text()))
    outputs[DELIVERY_MODULE] = contract + 'export { admitQueryDeliveryEnvelope, createQueryDeliveryEnvelope, encodeQueryDeliveryEnvelope };\n'
    delivery_owner = ROOT / 'cli/src/main/js/query-delivery.mjs'
    delivery = embed_contract(delivery_owner.read_text(), contract).rstrip()
    outputs[delivery_owner] = delivery + '\n'
    # Registration installs each adapter as one file; generate its marked constants in place.
    for relative in ('copilot/extension.mjs', 'pi/extension.ts'):
        adapter = ROOT / relative
        source, count = re.subn(
            r'(?<=const PUBLIC_TOOL_CONTRACT_VERSION = )\d+(?=;)',
            str(authority['contractVersion']), adapter.read_text(),
        )
        if count != 1:
            raise ValueError(f'{relative}: missing unique generated contract version')
        failure_block = ('// Generated from ToolRpcFailure; owned by packaging/generate-public-query.py.\n'
                         'const TOOL_RPC_FAILURES = [\n' +
                         ''.join('  ' + json.dumps(failure) + ',\n' for failure in failures) +
                         ('] as const;\n' if relative.startswith('pi/') else '];\n') +
                         '// End generated ToolRpcFailure.')
        source, count = re.subn(
            r'// Generated from ToolRpcFailure; owned by packaging/generate-public-query.py\.\n.*?// End generated ToolRpcFailure\.',
            lambda _: failure_block, source, flags=re.DOTALL,
        )
        if count != 1:
            raise ValueError(f'{relative}: missing unique generated RPC failures')
        delivery_block = ('// Generated query delivery; owned by cli/src/main/js/query-delivery.mjs.\n' +
                          delivery + '\n// End generated query delivery.')
        source, count = re.subn(
            r'// Generated query delivery; owned by cli/src/main/js/query-delivery\.mjs\.\n.*?// End generated query delivery\.',
            lambda _: delivery_block, source, flags=re.DOTALL,
        )
        if count != 1:
            raise ValueError(f'{relative}: missing unique generated query delivery')
        outputs[adapter] = source
    registrations = []
    responses = []
    for tool in authority['tools']:
        schema = referenced_schema(tool['schema'], definitions)
        full = project(schema, strict=False)
        strict = project(schema, strict=True)
        outputs[RESOURCES / (tool['name'] + '.parameters.json')] = json.dumps(full, indent=2) + '\n'
        outputs[RESOURCES / (tool['name'] + '.openai-parameters.json')] = json.dumps(strict, indent=2) + '\n'
        examples = json.dumps(
            {'examples': tool.get('examples', {}), 'invalidExamples': tool.get('invalidExamples', {})},
            indent=2,
        ) + '\n'
        outputs[RESOURCES / (tool['name'] + '.examples.json')] = examples
        if tool['name'] == 'query_symbols':
            outputs[ROOT / 'agent-tools/skills/kast/references/query-examples.json'] = examples
        registrations.append(dict(type='function', name=tool['name'], description=tool['description'], inputSchema=full, deferLoading=tool['deferLoading']))
        responses.append(dict(type='function', name='kast_' + tool['name'], description=tool['description'], parameters=strict, strict=True))
    outputs[RESOURCES / 'tools.app-server.json'] = json.dumps(
        dict(type='namespace', name='kast', description=authority['namespaceDescription'], tools=registrations),
        indent=2,
    ) + '\n'
    outputs[RESOURCES / 'tools.responses.json'] = json.dumps(responses, indent=2) + '\n'
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Reject stale generated artifacts without modifying files.')
    args = parser.parse_args()
    outputs = render_tools(json.loads((RESOURCES / "tools.schema.json").read_text()))
    stale = []
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_text() != content:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    if stale:
        raise SystemExit('Stale generated public tool artifacts:\n' + '\n'.join(stale))
    print(f'{"Checked" if args.check else "Generated"} {len(outputs)} public tool artifacts')

if __name__ == '__main__':
    main()
