#!/usr/bin/env python3
"""Generate the public-query boundary DTOs and provider projections from one schema.

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
SCHEMA = RESOURCES / 'query.schema.json'
PREFIX = 'PublicQuery'
HEADER = '''// Generated from query.schema.json by packaging/generate-public-query.py. Do not edit.
package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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


def name(value: str) -> str:
    return PREFIX + value


def render(schema: dict) -> dict[Path, str]:
    definitions = schema['$defs']
    objects = {'Document': schema, **{key: value for key, value in definitions.items() if tagged_type(value, 'object')}}
    unions = {key: value for key, value in definitions.items() if 'anyOf' in value}
    parents = {branch['$ref'].split('/')[-1]: key for key, value in unions.items() for branch in value['anyOf']}
    enums = {key: value for key, value in definitions.items() if 'enum' in value}
    for key, value in objects.items():
        if key not in parents:
            enums[key + 'Type'] = value['properties']['type']

    def resolve(node: dict) -> tuple[str | None, dict]:
        if '$ref' in node:
            key = node['$ref'].split('/')[-1]
            return key, definitions[key]
        return None, node

    def typename(node: dict) -> str:
        key, value = resolve(node)
        suffix = '?' if nullable(value) else ''
        if 'x-kotlin-type' in value:
            return value['x-kotlin-type'] + suffix
        if key in objects or key in enums or key in unions:
            return name(key) + suffix
        if tagged_type(value, 'array'):
            item = typename(value['items'])
            if item.endswith('?'):
                raise ValueError('Nullable collection items are not supported')
            return f'BoundedProtocolList<{item}>' + suffix
        if tagged_type(value, 'string') and 'enum' not in value:
            return 'ProtocolText' + suffix
        raise ValueError(f'Unsupported boundary schema: {node}')

    def literal(node: dict, value) -> str:
        key, spec = resolve(node)
        if 'enum' in spec:
            if value not in spec['enum']:
                raise ValueError(f'Invalid enum default: {value}')
            return f'{name(key)}.{enum_entry(value)}'
        if tagged_type(spec, 'string'):
            quoted = json.dumps(value).replace('$', '\\$')
            return f'queryValue({typename(node)}.parse({quoted}))'
        if tagged_type(spec, 'array'):
            items = ', '.join(literal(spec['items'], item) for item in value)
            return f'queryListOf({items})'
        if 'x-kotlin-variants' in spec:
            tag = value['type']
            variant_value = {'$ref': '#/$defs/' + spec['x-kotlin-variants'][tag]}
            arguments = ', '.join(
                f'{prop} = {literal(variant_value if prop == "value" else spec["properties"][prop], item)}'
                for prop, item in value.items() if prop != 'type'
            )
            return f'{name(key)}.{tag.title()}({arguments})'
        raise ValueError(f'Unsupported default: {node} = {value}')

    def property_line(prop: str, spec: dict, required: list[str], prefix: str = 'val ') -> str:
        _, resolved = resolve(spec)
        default = ''
        if prop not in required:
            if 'default' in resolved:
                default = ' = ' + literal(spec, resolved['default'])
            elif nullable(resolved):
                default = ' = null'
            else:
                raise ValueError(f'Optional control lacks a declaring default: {prop}')
        return f'    {prefix}{prop}: {typename(spec)}{default},\n'

    lines = [HEADER]
    for key, value in enums.items():
        lines.append(f'@Serializable\ninternal enum class {name(key)} {{\n')
        for item in value['enum']:
            if item != enum_entry(item):
                raise ValueError(f'Public enum is not CAPS_CASE: {key}: {item}')
            lines.append(f'    {item},\n')
        lines.append('}\n\n')
    for key in unions:
        lines.append(f'@Serializable\ninternal sealed interface {name(key)}\n\n')
    for key, value in objects.items():
        variants = value.get('x-kotlin-variants')
        if variants:
            if set(variants) != set(value['properties']['type']['enum']):
                raise ValueError(f'Scope variants differ from discriminator: {key}')
            owner = name(key)
            shared = {k: v for k, v in value['properties'].items() if k not in {'type', 'value'}}
            lines.append(f'@Serializable(with = {owner}Serializer::class)\ninternal sealed interface {owner} {{\n')
            for prop, spec in shared.items():
                lines.append(f'    val {prop}: {typename(spec)}\n')
            for tag, target in variants.items():
                lines.append(f'\n    data class {tag.title()}(\n        val value: {typename({"$ref": "#/$defs/" + target})},\n')
                for prop, spec in shared.items():
                    lines.append('    ' + property_line(prop, spec, value['required'], 'override val '))
                lines.append(f'    ) : {owner}\n')
            lines.append('}\n\n')
            lines.append(f'@Serializable\nprivate data class {owner}Envelope(\n    val type: {owner}Type,\n    val value: ProtocolText,\n')
            for prop, spec in shared.items():
                lines.append(property_line(prop, spec, value['required']))
            lines.append(')\n\n')
            lines.append(f'internal object {owner}Serializer : KSerializer<{owner}> {{\n')
            lines.append(f'    override val descriptor: SerialDescriptor = {owner}Envelope.serializer().descriptor\n\n')
            lines.append(f'    override fun deserialize(decoder: Decoder): {owner} {{\n        val input = {owner}Envelope.serializer().deserialize(decoder)\n        return when (input.type) {{\n')
            for tag, target in variants.items():
                typ = typename({'$ref': '#/$defs/' + target})
                lines.append(f'            {owner}Type.{tag} -> {owner}.{tag.title()}(\n                value = queryValue({typ}.parse(input.value.value)),\n')
                for prop in shared:
                    lines.append(f'                {prop} = input.{prop},\n')
                lines.append('            )\n')
            lines.append('        }\n    }\n\n')
            lines.append(f'    override fun serialize(encoder: Encoder, value: {owner}) {{\n        val output = when (value) {{\n')
            for tag in variants:
                lines.append(f'            is {owner}.{tag.title()} -> {owner}Envelope(\n                type = {owner}Type.{tag},\n                value = queryValue(ProtocolText.parse(value.value.value)),\n')
                for prop in shared:
                    lines.append(f'                {prop} = value.{prop},\n')
                lines.append('            )\n')
            lines.append(f'        }}\n        {owner}Envelope.serializer().serialize(encoder, output)\n    }}\n}}\n\n')
            continue
        parent = parents.get(key)
        annotation = '@Serializable\n'
        if parent:
            tag = value['properties']['type']['enum'][0]
            annotation += f'@SerialName({json.dumps(tag)})\n'
        properties = [(k, v) for k, v in value['properties'].items() if not (parent and k == 'type')]
        suffix = f' : {name(parent)}' if parent else ''
        if not properties:
            lines.append(annotation + f'internal data object {name(key)}{suffix}\n\n')
            continue
        lines.append(annotation + f'internal data class {name(key)}(\n')
        for prop, spec in properties:
            if prop == 'type':
                lines.append(f'    val type: {name(key)}Type,\n')
            else:
                lines.append(property_line(prop, spec, value['required']))
        lines.append(')' + suffix + '\n\n')
    lines.append('''private fun <T> queryListOf(vararg values: T): BoundedProtocolList<T> =
    queryValue(BoundedProtocolList.create(values.toList()))

/** Refinement failures become the serialization boundary's expected rejection protocol. */
private fun <T> queryValue(result: Refinement<T, *>): T = when (result) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> throw SerializationException("Invalid public query value: ${result.failure}")
}
''')
    return {
        KOTLIN / 'PublicQueryDocuments.kt': ''.join(lines).rstrip() + '\n',
        RESOURCES / 'query.parameters.json': json.dumps(project(schema, strict=False), indent=2) + '\n',
        RESOURCES / 'query.openai-parameters.json': json.dumps(project(schema, strict=True), indent=2) + '\n',
    }

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
    objects = {**definitions, **roots}
    enums = {}
    unions = {'Scope': ['DirectoryScope', 'PackageScope'],
              'Source': ['SearchSource', 'AllSource', 'ReferenceSource'],
              'Step': ['FilterVisibility', 'ExpandRelation', 'DistinctSymbols']}
    parents = {child: parent for parent, children in unions.items() for child in children}
    def typ(spec, prop):
        if '$ref' in spec:
            return 'PublicTool' + spec['$ref'].split('/')[-1]
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
    lines = [HEADER.replace('query.schema.json', 'tools.schema.json'),
             'import io.github.amichne.kast.protocol.registry.PublicToolIdentity\n',
             'import kotlinx.serialization.json.*\n\n',
             'internal sealed interface PublicToolDocument\n\n']
    body = []
    for key, spec in objects.items():
        props = [(p,s) for p,s in spec['properties'].items() if p != 'type']
        parent = parents.get(key)
        suffix = ' : PublicTool' + parent if parent else ' : PublicToolDocument'
        annotation = '@Serializable\n'
        if parent and parent != 'Scope':
            annotation += '@SerialName(' + json.dumps(spec['properties']['type']['enum'][0]) + ')\n'
        if not props:
            body.append(annotation + f'internal data object PublicTool{key}{suffix}\n\n')
        else:
            body.append(annotation + f'internal data class PublicTool{key}(\n')
            for prop, value in props:
                body.append(f'    val {prop}: {typ(value, prop)},\n')
            body.append(')' + suffix + '\n\n')
    for key, values in enums.items():
        lines.append(f'@Serializable\ninternal enum class PublicTool{key} {{\n')
        for value in values:
            lines.append(f'    @SerialName({json.dumps(value)}) {enum_entry(value)},\n')
        lines.append('}\n\n')
    for union in unions:
        annotation = '@Serializable(with = PublicToolScopeSerializer::class)' if union == 'Scope' else '@Serializable'
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
    lines.append('    val searchFields = ' + bounded(['PublicToolReturnFields.' + enum_entry(s) for s in defaults['search_fields']]) + '\n')
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
                      'enum class PublicToolIdentity(val toolName: String, val operation: CanonicalOperation, val description: String, val loading: HostedToolLoading) {\n']
    operations = {'query.run': 'QUERY_RUN', 'diagnostic.check': 'DIAGNOSTIC_CHECK'}
    for tool in authority['tools']:
        identity_lines.append(f'    {enum_entry(tool["name"])}({json.dumps(tool["name"])}, CanonicalOperation.{operations[tool["operation"]]},\n        {json.dumps(tool["description"])}, HostedToolLoading.{"DEFERRED" if tool["deferLoading"] else "EAGER"}),\n')
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
        registrations.append(dict(type='function', name=tool['name'], description=tool['description'], inputSchema=strict, deferLoading=tool['deferLoading']))
        responses.append(dict(type='function', name='kast_' + tool['name'], description=tool['description'], parameters=strict, strict=True))
    outputs[RESOURCES / 'tools.app-server.json'] = json.dumps(dict(type='namespace', name='kast', tools=registrations), indent=2) + '\n'
    outputs[RESOURCES / 'tools.responses.json'] = json.dumps(responses, indent=2) + '\n'
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Reject stale generated artifacts without modifying files.')
    args = parser.parse_args()
    outputs = render(json.loads(SCHEMA.read_text()))
    outputs.update(render_tools(json.loads((RESOURCES / "tools.schema.json").read_text())))
    stale = []
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_text() != content:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    if stale:
        raise SystemExit('Stale generated public query artifacts:\n' + '\n'.join(stale))
    print(f'{"Checked" if args.check else "Generated"} {len(outputs)} public query artifacts')

if __name__ == '__main__':
    main()
