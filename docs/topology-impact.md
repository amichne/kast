# Find consumers of changed Kast modules

Use `topologyImpact` to identify modules that depend on selected Kast modules.
The report includes direct and transitive consumers, with one shortest dependency path for each consumer.
This task reads the configured build's declared production project dependencies.
Its inventory contains Java-plugin projects in Kast's canonical architecture policy.

## Run the report

From the repository root, select one or more exact Gradle project paths:

```sh
mise exec -- ./gradlew topologyImpact \
  -PkastTopologyModules=:topology:contract,:evidence:topology-sqlite
```

Read `build/reports/topology/topology-impact.json` after the command succeeds.
The task writes only this report beneath the build directory.
It does not compile source, resolve external dependencies, or schedule `verifyKastArchitecture`.
Gradle still configures the repository, as it does for other tasks.

The report's `status` is `COMPUTED`, `REJECTED`, or `DISABLED`.
A rejected invocation writes a rejection report and fails the Gradle task.
Unknown modules, an empty selection, duplicate selections, and malformed graph inputs fail before traversal.

The `COMPUTED` report contains:

- `schemaVersion`: `1`.
- `evidence`: `DECLARED_PRODUCTION_PROJECT_DEPENDENCIES`.
- `selectedModules`: the requested modules in sorted order.
- `affectedModules`: other reachable consumers in sorted order.
- `dependencyPath`: a path from the consumer to a selected module, in dependency direction.
- `graph`: the sorted module inventory and declared dependency edges used for this result.
- `graphIdentity`: `sha256:` followed by the digest of the compact UTF-8 JSON encoding of `graph`.

The graph digest excludes the selection.
Identical graphs produce the same digest, regardless of input order.
Each path has the fewest edges to a selected module.
Sorted traversal resolves equal-length alternatives deterministically.
Selected modules remain separate from their consumers.
A valid module without consumers produces an empty `affectedModules` list.

## Disable the report

The task runs only when explicitly selected.
Ordinary build and live-query paths do not schedule it.
To disable an explicit invocation, use:

```sh
mise exec -- ./gradlew topologyImpact -PkastTopologyEnabled=false
```

You can also set `kastTopologyEnabled=false` in your local Gradle properties.
Only the exact values `true` and `false` are accepted.
A disabled invocation replaces the previous report with `DISABLED` and does not traverse the graph.

## Interpret the evidence

Use the paths to choose modules for source review and focused tests.
The report does not prove source usage, compiler resolution, task invalidation, or complete test impact.
The existing collector excludes configurations whose names contain `test` or `fixture`, regardless of case.
The report also excludes external libraries, composite-build substitution, and runtime discovery.
An unlisted module has no path in this declared graph. Other relationships can still affect it.

The graph describes this Gradle configuration.
It does not claim an immutable source revision or an accepted architecture scan.
Keep the report as an explicit record of the graph and selection used for a decision.
Historical compiler capture still rejects `EXTERNAL_INPUT_STABILITY_UNPROVEN`.
See the [historical evidence report](reviews/persistent-topology-snapshots.md#native-input-consistency-gate-october-15-2026) for that separate limit.

The [architecture convention](../build-logic/src/main/kotlin/kast.architecture.gradle.kts) owns dependency collection.
[ModuleImpact](../build-logic/src/main/kotlin/support/architecture/ModuleImpact.kt) owns selection and traversal.
The [task adapter](../build-logic/src/main/kotlin/support/architecture/gradle/TopologyImpactTask.kt) owns the report file and command failures.
