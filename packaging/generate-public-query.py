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

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
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

    def typename(node: dict) -> str:
        if '$ref' in node:
            key = node['$ref'].split('/')[-1]
            value = definitions[key]
            if key in objects or key in enums or key in unions:
                return name(key) + ('?' if nullable(value) else '')
            return typename(value)
        if tagged_type(node, 'array'):
            item = typename(node['items'])
            if item.endswith('?'):
                raise ValueError('Nullable collection items are not supported by this contract')
            return f'BoundedProtocolList<{item}>' + ('?' if nullable(node) else '')
        if tagged_type(node, 'string') and 'enum' not in node:
            return 'ProtocolText' + ('?' if nullable(node) else '')
        raise ValueError(f'Unsupported boundary schema: {node}')

    lines = [HEADER]
    for key, value in enums.items():
        lines.append(f'@Serializable\ninternal enum class {name(key)} {{\n')
        for item in value['enum']:
            if item is not None:
                lines.append(f'    @SerialName({json.dumps(item)})\n    {enum_entry(item)},\n')
        lines.append('}\n\n')
    for key in unions:
        lines.append(f'@Serializable\ninternal sealed interface {name(key)}\n\n')
    for key, value in objects.items():
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
            typ = name(key + 'Type') if prop == 'type' else typename(spec)
            optional = prop not in value['required']
            if optional and not typ.endswith('?'):
                raise ValueError(f'Optional controls must be nullable: {key}.{prop}')
            default = ' = null' if optional else ''
            identifier = f'`{prop}`' if prop in {'package'} else prop
            lines.append(f'    val {identifier}: {typ}{default},\n')
        lines.append(')' + suffix + '\n\n')

    defaults_header = HEADER.split('import ')[0] + (
        'import io.github.amichne.kast.kernel.Refinement\n'
        'import io.github.amichne.kast.protocol.contract.BoundedProtocolList\n'
        'import io.github.amichne.kast.protocol.contract.ProtocolText\n\n'
    )
    defaults = [defaults_header,
        '/** Defaults come from the same definitions that document the public boundary. */\n',
        'internal object PublicQueryDefaults {\n']
    for key, value in definitions.items():
        if 'default' not in value:
            continue
        default = value['default']
        field = key[0].lower() + key[1:]
        if isinstance(default, str):
            defaults.append(f'    val {field}: {name(key)} = {name(key)}.{enum_entry(default)}\n')
        elif isinstance(default, list):
            item_name = value['items']['$ref'].split('/')[-1]
            item_type = typename(value['items'])
            if default:
                contents = ''.join(
                    f'            {item_type}.{enum_entry(x)},\n' if item_name in enums
                    else f'            text({json.dumps(x)}),\n'
                    for x in default
                )
                defaults.append(
                    f'    val {field}: BoundedProtocolList<{item_type}> = bounded(\n'
                    f'        listOf(\n{contents}        ),\n    )\n'
                )
            else:
                defaults.append(f'    val {field}: BoundedProtocolList<{item_type}> = bounded(emptyList())\n')
        else:
            raise ValueError(f'Unsupported default on {key}: {default!r}')
    defaults.append('''
    private fun text(value: String): ProtocolText =
        when (val result = ProtocolText.parse(value)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Invalid schema-owned query default")
        }

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
        when (val result = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Invalid schema-owned query default")
        }
}
''')
    return {
        KOTLIN / 'PublicQueryDocuments.kt': ''.join(lines).rstrip() + '\n',
        KOTLIN / 'PublicQueryDefaults.kt': ''.join(defaults),
        RESOURCES / 'query.parameters.json': json.dumps(project(schema, strict=False), indent=2) + '\n',
        RESOURCES / 'query.openai-parameters.json': json.dumps(project(schema, strict=True), indent=2) + '\n',
    }


def project(schema: dict, *, strict: bool) -> dict:
    """Provider syntax projection, NOT the server's full admission schema.

    Drop only named authoring annotations. The strict profile additionally drops
    length/uniqueness keywords not listed in the targeted supported keyword set.
    The full admission schema and Kotlin canonical admission still enforce them.
    """
    drop = {'$schema', '$id', 'discriminator', 'default', 'examples', 'title'}
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Reject stale generated artifacts without modifying files.')
    args = parser.parse_args()
    outputs = render(json.loads(SCHEMA.read_text()))
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
