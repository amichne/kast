# Public CLI reads

Use the installed `kast` command for Kotlin queries without MCP registration.
The existing IntelliJ Host remains necessary for semantic work.

First inspect the installed contract:

```sh
kast describe query --section input
kast describe query --section examples
```

Use exact-name discovery with known scope:

```sh
kast query --name OrderService --in services/orders --source-set main \
  --fields name,signature,location --retain
```

Examine overloads before you select an exact reference.
Copy issued tokens unchanged.
Do not infer an exact reference from a name or path.

Use `kast query --request FILE` for the complete public argument document.
Use `kast query --request -` only when stdin contains that document.
The default catalog is `COMPLETE`.
An explicit `COMMON` selection remains restrictive.

Use `kast result read --ref REF` for retained output.
Use `kast result resume --continuation TOKEN` for an issued output continuation.
These commands emit one page without an implicit drain or semantic retry.
Never treat a visible page as full membership.

Read the outcome, qualifications, and recovery evidence before a semantic conclusion.
Retained policy rejection remains unsuccessful on every page.
An empty qualified result does not prove absence.
Omitted `--fields`, empty `--fields ''`, and explicit fields have distinct meanings.

Enable `set -o pipefail` when a shell pipeline depends on the Kast exit status.
Local cancellation does not prove native settlement.
