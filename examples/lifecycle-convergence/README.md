# Converged lifecycle and enterprise trust example

This example is stacked on `feature/compiler-topology-declaration-binding` and makes the proposed
1.0 lifecycle concrete without pretending the installed-system proof already exists.

## Target public surface

The public lifecycle becomes:

```text
kast                     # passive product/workspace inspection; never starts anything
kast start               # explicit eager readiness when a human wants it
kast stop                # explicit ownership release
kast <semantic command>  # starts/reuses the exact-root runtime automatically
```

The following commands are retired from the public surface:

```text
kast product inspect
kast status
kast index sync
kast topology build
kast broker serve
```

`index.sync` and `topology.build` remain internal typed operations/capabilities. Removing their CLI
projection must not remove their authority boundaries.

## Runtime convergence

A semantic CLI request already carries `PreparedCliRequest.hostedDemand`. Instead of passively
requiring an already-running sidecar, `KastCli.executeSemantic` should:

1. discover the canonical root;
2. call `demandRuntimeBoundary(root, request.hostedDemand, RuntimeStartupRequest.Default)`;
3. execute the request against the returned exact-root endpoint.

That is the Codex lifecycle change too. The broker does not own another Kast lifecycle. Every Kast
tool invocation reaches the same semantic CLI path, which starts or reuses the exact-root runtime.
`broker serve` therefore becomes an integration-host implementation detail rather than a user
command.

`kast start` remains useful as an eager/prewarm command, but semantic correctness no longer depends
on the caller remembering it.

## Index synchronization

`index.sync` should become an internal readiness transition, not another public workflow step.
The invariant is: a semantic operation receives a current `PublishedWorkspace`, or it rejects.

The preferred production shape is an `EnsureWorkspaceReady` capability in `:workspace:service`
that owns:

```text
observed source identity
  -> unchanged: reuse PublishedWorkspace
  -> changed: refresh admitted roots -> await smart mode -> publish successor generation
```

A semantic request may ask that coordinator for readiness. It must never reconstruct readiness from
`Project`, a database connection, or a Boolean. Existing exact selectors remain generation-bound;
readiness may publish a successor but may not silently rebind stale selectors.

## Topology build trigger

Do **not** build topology at startup and do not create a background topology worker.

Topology is a derived, comparatively expensive capability required only by traversal/impact reads.
Build it synchronously and lazily at the first traversal for a workspace generation:

```text
traversal request
  -> current PublishedWorkspace
  -> TopologyBuildOperations.build()
       -> Reused(snapshot)    => continue
       -> Published(snapshot) => continue
       -> WorkspaceMoved      => stale-generation rejection
       -> Rejected            => required-evidence-unavailable rejection
  -> TopologyBackedTraversalOperations.run(plan)
```

`TopologyBuildService` already serializes builds with a `Mutex` and checks durable eligibility
before extraction. Concurrent first traversals therefore converge on one publication path without
creating another lifecycle concept.

`TopologyPreparingTraversalOperations.kt` in this example is the production-oriented adapter for
that composition.

## Enterprise trust propagation

The failure mode is caused by JVM/process separation:

* Kast intentionally launches the sidecar with the IDEA JBR and strips ambient JVM-option
  injection.
* Gradle Tooling API client networking happens in the sidecar JVM.
* Gradle dependency resolution happens in the selected Gradle daemon JVM.
* IntelliJ also has a user-specific certificate store under its configuration directory.

The fix must preserve isolation while carrying **trust authority**, not arbitrary JVM flags.

### Sources admitted in order

1. Explicit `javax.net.ssl.trustStore*` properties from the caller/configured Gradle environment.
2. The selected donor JVM's `lib/security/jssecacerts` or `cacerts`.
3. IntelliJ's accepted-certificate store (`<IDE config>/ssl/cacerts`) when present.
4. No extra material: retain the target JBR/JDK defaults.

Do not ingest `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, agents, arbitrary `-D`
properties, or arbitrary installed JDKs. Parse only the closed TLS/proxy property set.

### Materialization

Kast should materialize a **private derived truststore** under the exact sidecar cache rather than
modify the read-only IDEA/JBR installation. The artifact is content-addressed by certificate
fingerprints and source identities. It contains trusted certificate entries only, never private
keys.

The materializer must deduplicate by certificate fingerprint, use atomic publication, mode `0600`,
and retain provenance without certificate payloads or passwords in logs/receipts.

The resulting `ResolvedNetworkPolicy` is applied to both consumers:

* sidecar/Tooling API client: set the narrow JSSE/proxy system properties before IntelliJ Gradle
  integration initializes its network client;
* Gradle daemon: inject the same resolved truststore through the Gradle operation/environment
  boundary, unless the repository/user Gradle configuration already declares a stronger explicit
  truststore policy.

Gradle Tooling API's `withSystemProperties` has higher precedence than JVM arguments, so production
code must not blindly call it with inherited trust properties and overwrite repository-owned
configuration. Resolve one consumer-specific policy first, then apply exactly that policy.

### Why a derived store instead of copying a store

The enterprise certificate may live in the user's existing JVM store, IntelliJ's accepted store, or
both. The sidecar JBR may be read-only and may have a different default store. A Kast-owned derived
store makes the exact trust set deterministic and reusable without mutating either installation.

This is intentionally **not** "trust all certificates" and does not disable hostname verification.

## Public operation projection

`CanonicalOperation` may continue to include `INDEX_SYNC` and `TOPOLOGY_BUILD`; they are useful
internal identities. Their `HostedExposure` should become `INTERNAL_ONLY`. The CLI command graph and
installed server projection should be derived only from public exposure, rather than requiring one
CLI leaf for every canonical internal operation.

That establishes the missing invariant:

> operation identity is not the same thing as user-visible lifecycle choreography.

## Acceptance required before command retirement

This branch is an implementation example until all of these pass on one exact head:

1. Bare `kast` is passive outside/inside a repository and with a stopped runtime.
2. A semantic CLI command on a stopped workspace starts once and succeeds.
3. Concurrent semantic commands converge on the same exact-root runtime.
4. External source changes cause one successor workspace publication without public `index sync`.
5. First traversal builds topology; second traversal reuses it; ordinary symbol/source reads never
   build topology.
6. A source edit invalidates the prior topology and next traversal publishes/reuses only the new
   generation.
7. Private-CA Gradle distribution download succeeds from an empty cache.
8. Private-CA dependency resolution succeeds in the selected Gradle daemon.
9. The same two journeys fail closed when the donor certificate is absent.
10. Hostile JVM injection remains stripped.
11. Codex works from a cold Kast state without `kast start` or `kast broker serve`.
12. CLI/schema/help/docs contain no `product inspect`, `status`, `index sync`, `topology build`, or
    `broker serve` public command.
