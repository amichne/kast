---
name: kast
description: Use the installed Kast CLI for Kotlin declaration discovery, relationships, source reads, and compiler diagnostics in one exact workspace.
---

# Kast

Use `kast` in the exact Kotlin workspace that the user trusts.
The first semantic query trusts its build code and prepares its private IntelliJ IDEA Host.
Later calls reuse that Host. Use ordinary source tools for other languages.

## Find a declaration

Put each known constraint on the first query. Use `--in` for the requested directory and `--source-set` for each requested source set.
The default includes both `main` and `test`. Use `--kind class` for interfaces.

```sh
kast query --name OrderService --in services/orders --source-set main --kind class --fields name,signature,location
kast query --ref "$exact_ref" --relation callers
```

Select an overload by owner, signature, receiver, and location before a one-hop relation.
If matches remain ambiguous, stop. Copy each issued reference unchanged.
A call site's directory does not establish the target's directory.
A cursor at a call selects the enclosing declaration. Examine source before an interface claim.
Keep the requested scope on source reads. Location ranges use UTF-16 offsets.

## Discover syntax

For an unknown flag name, use `kast query --help`. For accepted values, read the flag entry.
For a relation across several hops, use a `WALK` step. Select installed examples by step type.
Use the matching example as the request contract. Adapt its reference, relation, depth, output, and budget.

```sh
kast describe query --jq '.content.flags[] | select(.name == "--kind")'
kast describe query --section examples --jq '[.content[].value | select(.request.steps[0].type == "WALK")]'
```

If no example matches, use the [discovery guide](public-cli.md#discover-only-what-you-need).

## Use the evidence

Examine the outer outcome, scope, coverage, and limitations before a conclusion.
An empty incomplete result cannot prove absence. A complete relation proves only its declared semantics.
For a complete source-use answer, resolve imports, companion qualifiers, and unmatched sites.

For more output, use the issued result reference, cursor, and original output kind.
Use resume only with an issued output continuation. Never repeat semantic work to recover output.
After a source change or freshness rejection, discard stale source evidence.

A client timeout or cancellation leaves Host settlement uncertain. Never retry uncertain work automatically.
Keep the failure or receipt and its recovery instructions.
If admission rejects the workspace or Host, state the reason and qualify source reads.

See the guide for [optional MCP tools](public-cli.md#optional-mcp-tools) and [fresh sessions after updates](public-cli.md#fresh-sessions).
