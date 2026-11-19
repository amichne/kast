---
name: kast
description: Use Kast for Kotlin queries and exact Java class and method queries in an imported workspace.
---

# Kast

Use `kast` in the exact workspace that the user trusts.
The first semantic query trusts its build code and prepares its private IntelliJ IDEA Host.
Later calls reuse that Host.

## Find a declaration

Put each known constraint on the first query. Use `--in` for the requested directory and `--source-set` for each requested source set.
The default includes both `main` and `test`. Use `--kind class` for interfaces.

```sh
kast query --name OrderService --in services/orders --source-set main --kind class --fields name,signature,location
kast query --ref "$exact_ref" --relation callers
```

In mixed Java and Kotlin sources, find Java classes, interfaces, and methods by exact name. This lookup excludes constructors.
For a Java method, use its declaring directory and `--kind function`.

```sh
kast query --name relay --in ledger/src/main/java/acme/legacy --kind function --fields name,signature,location
```

Select an overload by owner, signature, receiver, and location before a one-hop relation.
Use `--relation callers` for declarations that call it. Use `--relation callees` for declarations it calls.
For Java `relay(int)`, select the row with the expected owner and the single parameter `kotlin/Int`.
Use its reference with `--relation references` or `--relation callers`.
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

Read the next page with the issued result reference, presentation cursor, and original output kind.

```sh
kast result read --ref "$result_ref" --cursor "$cursor" --output "$output_kind"
```

If the result also supplies an evidence cursor, pass it with `--evidence-cursor`.
Use resume only with an issued output continuation. Never repeat semantic work to recover output.
After a source change or freshness rejection, discard stale source evidence.

A client timeout or cancellation leaves Host settlement uncertain. Never retry uncertain work automatically.
Keep the failure or receipt and its recovery instructions.
If admission rejects the workspace or Host, state the reason and qualify source reads.

See the guide for [optional MCP tools](public-cli.md#optional-mcp-tools) and [fresh sessions after updates](public-cli.md#fresh-sessions).
