# Semantic readiness and enterprise configuration

A semantic request owns its readiness path. From an installed Kast executable,
run the command in the Kotlin Gradle repository you want to inspect:

```shell
cd /path/to/repository
kast
kast query run < request.json
```

Bare `kast` is passive inspection. A semantic request starts or joins the
exact-root runtime, acquires current workspace evidence, and executes its
operation. `kast start` is optional prewarming. `kast stop` explicitly releases
the process.

When `kast start --idea-home` selects an installation outside the standard search
paths, later semantic requests retain that selection from exact-root cache
evidence. Every request revalidates the installation against current runtime
support and payload identity. Ambiguous or invalid evidence rejects admission.

The public lifecycle is bare `kast`, optional `kast start`, and explicit
`kast stop`. Synchronization and topology construction remain internal
capabilities rather than public lifecycle operations.

## Reuse and source changes

Concurrent cold requests join one exact-root bootstrap attempt. Later requests
reuse its ready endpoint. Readiness reconciles current source and imported model
evidence; callers do not need to synchronize explicitly.

When evidence is unchanged, the workspace keeps its generation. Source movement
produces a successor publication. Candidate and exact selectors retain the
generation that justified them, so later requests reject stale selectors.
Rediscover and inspect the declaration to acquire evidence for the successor
generation.

Verified mutation uses the same workspace transition owner. It retains admission
through physical write, refresh, successor publication, compiler verification,
and receipt. Recovery rollback joins that owner rather than publishing a
competing generation.

Source content hashes are observations. They do not prove IntelliJ VFS refresh,
Gradle model import, or semantic synchronization. The implementation keeps those
transitions separate.

The imported-model guard observes conventional build inputs: Gradle build and
settings scripts, Gradle properties, wrapper and daemon JVM properties, version
catalogs, and `buildSrc` or `build-logic` sources. Live model evidence includes
roots, modules, SDK, and ordered classpath URLs.

[InstalledGradleModelInputs](../workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleModelInputs.kt)
and [InstalledGradleSemanticIdentity](../workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleSemanticIdentity.kt)
define this observation boundary.

## Traversal acquires topology when needed

`kast traversal run` acquires an eligible topology snapshot before traversal.
Concurrent first traversals share acquisition, extraction, and publication for
the same current evidence. Later traversals reuse the eligible snapshot. Source
movement requires a snapshot for the successor generation.

Symbol discovery and inspection, source reads, diagnostics, and one-hop relation
reads do not eagerly build topology.

[TopologyPreparingTraversalOperations](../runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/graph/TopologyPreparingTraversalOperations.kt)
is the operation boundary. Its focused tests own topology acquisition, reuse,
and stale-generation behavior; there is no standing installed lifecycle driver.

## Configure enterprise trust and proxies

Kast admits a finite set of network settings. It does not forward arbitrary JVM
options from the calling shell to the sidecar. The sidecar environment strips
`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and `JDK_JAVA_OPTIONS`.

| Setting | Purpose |
| --- | --- |
| `KAST_NETWORK_CONFIG` | Absolute path to a Java properties file containing admitted JSSE and proxy settings. |
| `KAST_TRUST_DONOR_JAVA_HOME` | Selected donor JVM home. |
| `KAST_IDE_CONFIG_HOME` | IntelliJ configuration directory whose `ssl/cacerts` contains accepted certificates. |
| `GRADLE_USER_HOME` | Gradle user directory, including consumer-owned `gradle.properties`. |

When no explicit donor JVM is configured, the launch boundary can select the
caller's `JAVA_HOME` as the donor. The sidecar still executes with the admitted
IDEA JBR. A donor supplies trust material; it does not select the sidecar or
Gradle executable.

`KAST_NETWORK_CONFIG` admits bounded trust and proxy properties such as:

```properties
javax.net.ssl.trustStore=/absolute/path/to/enterprise-truststore.p12
javax.net.ssl.trustStoreType=PKCS12
https.proxyHost=proxy.example.com
https.proxyPort=8443
http.nonProxyHosts=localhost|127.*|*.example.internal
```

Consumer-owned configuration has precedence:

1. Explicit Gradle trust and proxy configuration.
2. Explicit admitted Kast configuration.
3. Discovered donor certificates and target JVM trust.

A configured `GRADLE_USER_HOME` automatically participates in the admitted
import environment and cache identity. Existing `org.gradle.jvmargs` and
`systemProp.*` remain Gradle authority. Kast does not overwrite consumer-owned
trust through Tooling API system properties.

The [configuration contract](../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/network/NetworkConfiguration.kt)
and [Gradle configuration reader](../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/network/GradleNetworkConfiguration.kt)
are the key and precedence authorities.

## Private trust artifacts

Kast materializes admitted certificates into a private, content-addressed JKS
truststore. It does not copy private keys, modify donor stores, or alter the
installed JBR store. Certificates are deduplicated by encoded certificate
digest. Publication is serialized and atomic.

Sidecar artifacts live in the exact cache's `network` directory. Daemon
artifacts live in `network-daemon`. On POSIX filesystems these directories use
mode `0700` and truststore files use mode `0600`. Each consumer has a bounded
`policy.properties` receipt.

[InstalledNetworkBootstrap](../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/network/InstalledNetworkBootstrap.kt),
[DerivedTrustStoreMaterializer](../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/network/DerivedTrustStoreMaterializer.kt), and
[KastGradleNetworkPolicyExtension](../workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/network/KastGradleNetworkPolicyExtension.kt)
own these effects.

## Verification ownership

Bare `kast` reports product/runtime identity, workspace cache and trace
locations, and configured network sources or blockers without starting a
runtime. Network policy, workspace transition, topology acquisition, and broker
behavior are verified by their owning Kotlin tests and module checks.

There is intentionally no repository-level lifecycle, TLS, performance, or
enterprise acceptance driver. Reintroducing one requires a demonstrated
invariant that cannot be proven at a narrower boundary.
