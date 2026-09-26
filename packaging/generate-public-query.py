#!/usr/bin/env python3
"""Generate current public-tool DTOs and provider projections from one schema.

This deliberately supports only this contract's closed objects, local refs,
nullable primitives/collections and tagged unions. Unsupported forms fail closed.
It does not generate semantic admission or compiler authority.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'app-server/src/main/resources/io/github/amichne/kast/appserver/query'
KOTLIN = ROOT / 'app-server/src/main/kotlin/io/github/amichne/kast/appserver/query'
HEADER = '''// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

'''


def tagged_type(node: dict, kind: str) -> bool:
    value = node.get('type')
    return value == kind or isinstance(value, list) and kind in value


def nullable(node: dict) -> bool:
    return isinstance(node.get('type'), list) and 'null' in node['type']


def enum_entry(value: str) -> str:
    return value.replace('-', '_').upper()


def project(schema: dict, *, strict: bool) -> dict:
    """Provider syntax projection, NOT the server's full admission schema.

    Drop only named authoring annotations. The strict profile additionally drops
    length/uniqueness keywords not listed in the targeted supported keyword set.
    The full admission schema and Kotlin canonical admission still enforce them.
    """
    drop = {'$schema', '$id', 'discriminator', 'default', 'examples', 'title', 'x-kotlin-type', 'x-kotlin-variants'}
    if strict:
        drop |= {'uniqueItems', 'minLength', 'maxLength'}
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
    """Generate this closed facade slice, including nullable untagged scope syntax."""
    import copy
    definitions = copy.deepcopy(authority['$defs'])
    roots = {''.join(part.title() for part in tool['name'].split('_')): tool['schema'] for tool in authority['tools']}
    objects = {**{key: value for key, value in definitions.items() if 'x-kotlin-type' not in value}, **roots}
    enums = {}
    unions = {'Scope': ['DirectoryScope', 'PackageScope'],
              'Source': ['SearchSource', 'AllSource', 'ReferenceSource', 'ResultSource'],
              'Step': ['FilterVisibility', 'ExpandRelation', 'DistinctSymbols', 'AppendSymbolRefs', 'FilterJq'],
              'Action': ['RunAction', 'ResumeAction', 'ReadResultAction']}
    parents = {child: parent for parent, children in unions.items() for child in children}
    def typ(spec, prop):
        if '$ref' in spec:
            key = spec['$ref'].split('/')[-1]
            external = definitions.get(key, {})
            if 'x-kotlin-type' in external:
                return external['x-kotlin-type'] + ('?' if nullable(external) else '')
            return 'PublicTool' + key
        if 'anyOf' in spec:
            branches = [s['$ref'].split('/')[-1] for s in spec['anyOf'] if '$ref' in s]
            union = next(key for key, values in unions.items() if values == branches)
            return 'PublicTool' + union + ('?' if any(s.get('type') == 'null' for s in spec['anyOf']) else '')
        nullable_suffix = '?' if nullable(spec) else ''
        if 'enum' in spec:
            key = ''.join(part.title() for part in prop.split('_'))
            values = [item for item in spec['enum'] if item is not None]
            if key in enums and enums[key] != values:
                raise ValueError(f'Conflicting facade enum {key}')
            enums[key] = values
            return 'PublicTool' + key + nullable_suffix
        if tagged_type(spec, 'array'):
            return 'BoundedProtocolList<' + typ(spec['items'], prop) + '>' + nullable_suffix
        for kind, kotlin in [('string', 'ProtocolText'), ('boolean', 'Boolean'), ('integer', 'Int')]:
            if tagged_type(spec, kind): return kotlin + nullable_suffix
        raise ValueError(f'Unsupported facade schema {spec}')
    lines = [HEADER.replace('@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)',
                      '@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)\n@file:Suppress("ConstructorParameterNaming")'),
             'import io.github.amichne.kast.protocol.registry.PublicToolIdentity\n',
             'import kotlinx.serialization.json.*\n\n',
             'internal sealed interface PublicToolDocument\n\n']
    body = []
    for key, spec in objects.items():
        parent = parents.get(key)
        discriminator = 'action' if parent == 'Action' else 'type'
        props = [(p,s) for p,s in spec['properties'].items() if p != discriminator]
        suffix = ' : PublicTool' + parent if parent else ' : PublicToolDocument'
        annotation = '@Serializable\n'
        if parent and parent != 'Scope':
            annotation += '@SerialName(' + json.dumps(spec['properties'][discriminator]['enum'][0]) + ')\n'
        if not props:
            body.append(annotation + f'internal data object PublicTool{key}{suffix}\n\n')
        else:
            body.append(annotation + f'internal data class PublicTool{key}(\n')
            for prop, value in props:
                resolved = definitions[value['$ref'].split('/')[-1]] if '$ref' in value else value
                default = ''
                if prop not in spec.get('required', []):
                    if typ(value, prop).endswith('?'):
                        default = ' = null'
                    elif isinstance(resolved.get('default'), bool):
                        default = ' = ' + str(resolved['default']).lower()
                    else:
                        raise ValueError(f'Optional facade field lacks a supported default: {key}.{prop}')
                parameter = prop
                if 'x-kotlin-type' in resolved and '_' in prop:
                    parameter = prop.split('_')[0] + ''.join(part.title() for part in prop.split('_')[1:])
                    body.append(f'    @SerialName({json.dumps(prop)})\n    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)\n')
                body.append(f'    val {parameter}: {typ(value, prop)}{default},\n')
            body.append(')' + suffix + '\n\n')
    for key, values in enums.items():
        lines.append(f'@Serializable\ninternal enum class PublicTool{key} {{\n')
        for value in values:
            lines.append(f'    @SerialName({json.dumps(value)}) {enum_entry(value)},\n')
        lines.append('}\n\n')
    for union in unions:
        annotation = '@Serializable(with = PublicToolScopeSerializer::class)' if union == 'Scope' else '@Serializable'
        if union == 'Action':
            annotation += '\n@kotlinx.serialization.json.JsonClassDiscriminator("action")'
        lines.append(f'{annotation}\ninternal sealed interface PublicTool{union}\n\n')
    lines += body
    lines.append('''internal object PublicToolScopeSerializer : JsonContentPolymorphicSerializer<PublicToolScope>(PublicToolScope::class) {
    override fun selectDeserializer(element: JsonElement): kotlinx.serialization.DeserializationStrategy<PublicToolScope> = when {
        "relative_directory_path" in element.jsonObject -> PublicToolDirectoryScope.serializer()
        "package_name" in element.jsonObject -> PublicToolPackageScope.serializer()
        else -> throw SerializationException("scope requires a directory or package target")
    }
}

''')
    # Defaults are data in the authority and are compiled here, never copied into a provider.
    defaults = authority['defaults']
    def text_value(value): return 'toolDefault(ProtocolText.parse(' + json.dumps(value) + '))'
    def bounded(values): return 'toolDefault(BoundedProtocolList.create(listOf(' + ', '.join(values) + ')))'
    lines.append('internal object PublicToolDefaults {\n')
    lines.append('    val nameMatch = PublicToolNameMatch.' + enum_entry(defaults['name_match']) + '\n')
    lines.append('    val sourceSets = ' + bounded([text_value(s) for s in defaults['source_set_names']]) + '\n')
    lines.append('    val declarationKinds = ' + bounded(['PublicToolDeclarationKinds.' + enum_entry(s) for s in defaults['declaration_kinds']]) + '\n')
    lines.append('    val returnFields = ' + bounded(['PublicToolReturnFields.' + enum_entry(s) for s in defaults['return_fields']]) + '\n')
    lines.append('    val steps: BoundedProtocolList<PublicToolStep> = toolDefault(BoundedProtocolList.create(emptyList()))\n')
    if defaults['steps'] != []: raise ValueError('Only empty default transformations are supported')
    lines.append(f'    const val maxDiagnostics = {defaults["max_diagnostics"]}\n')
    scope = defaults['scope']
    if scope['source_set_names'] != defaults['source_set_names']: raise ValueError('Default scope source sets disagree')
    lines.append('    val scope: PublicToolScope = PublicToolDirectoryScope(' + text_value(scope['relative_directory_path']) + ', ' + str(scope['include_subdirectories']).lower() + ', sourceSets)\n}\n\n')
    lines.append('internal fun decodePublicTool(identity: PublicToolIdentity, raw: JsonElement, json: Json): PublicToolDocument = when (identity) {\n')
    for tool, key in zip(authority['tools'], roots):
        lines.append(f'    PublicToolIdentity.{enum_entry(tool["name"])} -> json.decodeFromJsonElement(PublicTool{key}.serializer(), raw)\n')
    lines.append('}\n\ninternal fun encodePublicTool(value: PublicToolDocument, json: Json): JsonElement = when (value) {\n')
    for key in roots:
        lines.append(f'    is PublicTool{key} -> json.encodeToJsonElement(PublicTool{key}.serializer(), value)\n')
    lines.append('}\n\nprivate fun <T> toolDefault(value: Refinement<T, *>): T = when (value) {\n    is Refinement.Refined -> value.value\n    is Refinement.Rejected -> error("Invalid authored public tool default")\n}\n')
    outputs = {KOTLIN / 'PublicToolDocuments.kt': ''.join(lines)}
    identity_lines = ['// Generated from tools.schema.json by packaging/generate-public-query.py. Do not edit.\n',
                      'package io.github.amichne.kast.protocol.registry\n\n',
                      'import io.github.amichne.kast.protocol.contract.CanonicalOperation\n\n',
                      '/** Closed presentation identities; canonical operations retain effect and budget ownership. */\n',
                      'enum class PublicToolIdentity(\n'
                      '    val toolName: String,\n'
                      '    val operation: CanonicalOperation,\n'
                      '    val description: String,\n'
                      '    val loading: HostedToolLoading,\n'
                      ') {\n']
    operations = {'query.run': 'QUERY_RUN', 'diagnostic.check': 'DIAGNOSTIC_CHECK'}
    for tool in authority['tools']:
        description = ' +\n            '.join(json.dumps(tool['description'][offset:offset + 90])
                                             for offset in range(0, len(tool['description']), 90))
        identity_lines.append(
            f'    {enum_entry(tool["name"])}({json.dumps(tool["name"])}, CanonicalOperation.{operations[tool["operation"]]},\n'
            f'        {description},\n'
            f'        HostedToolLoading.{"DEFERRED" if tool["deferLoading"] else "EAGER"},\n'
            '    ),\n'
        )
    identity_lines.append('}\n')
    outputs[ROOT / 'protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt'] = ''.join(identity_lines)
    registrations = []
    responses = []
    for tool in authority['tools']:
        schema = referenced_schema(tool['schema'], definitions)
        full = project(schema, strict=False)
        strict = project(schema, strict=True)
        outputs[RESOURCES / (tool['name'] + '.parameters.json')] = json.dumps(full, indent=2) + '\n'
        outputs[RESOURCES / (tool['name'] + '.openai-parameters.json')] = json.dumps(strict, indent=2) + '\n'
        registrations.append(dict(type='function', name=tool['name'], description=tool['description'], inputSchema=full, deferLoading=tool['deferLoading']))
        responses.append(dict(type='function', name='kast_' + tool['name'], description=tool['description'], parameters=strict, strict=True))
    outputs[RESOURCES / 'tools.app-server.json'] = json.dumps(dict(type='namespace', name='kast', tools=registrations), indent=2) + '\n'
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
