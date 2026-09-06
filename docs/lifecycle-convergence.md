# Semantic readiness and enterprise configuration

A semantic request owns its readiness path. From an installed Kast executable,
run the command in the Kotlin Gradle repository you want to inspect:

```shell
cd /path/to/repository
kast
kast symbol discover --query Checkout --match exact-name --limit 10
```

The query name is an example; use a declaration in your repository. Bare `kast`
is passive inspection. The semantic request starts or joins the exact-root
runtime, acquires current workspace evidence, and then executes its operation.
`kast start` is optional prewarming. `kast stop` is explicit process release.

When `kast start --idea-home` selects an installation outside the standard search
paths, later semantic requests retain that selection from exact-root cache
evidence. Each request revalidates the installation against current runtime
support and payload identity. An ambiguous or invalid cache record rejects
admission.

The public lifecycle is bare `kast`, optional `kast start`, and explicit
`kast stop`. Dedicated status, product-inspection, synchronization, topology-build,
and broker-serve commands are no longer public. `INDEX_SYNC` and `TOPOLOGY_BUILD`
retain internal canonical identities; their exposure is `INTERNAL_ONLY`. CLI and
Codex projections derive from that canonical exposure.

## Reuse and source changes

Concurrent cold requests join one exact-root bootstrap attempt. Later requests
reuse its ready endpoint. Readiness reconciles current source and imported model
evidence; a caller does not need to run a synchronization command first.

When the evidence is unchanged, the workspace keeps its generation. Source
movement produces a successor publication. Candidate and exact selectors retain
the generation that justified them, so a later request rejects stale selectors.
Rediscover and inspect the declaration to acquire an exact selector for the new
generation. A successful runtime connection alone does not establish selector
validity or current semantic evidence.

Verified mutation uses the same workspace transition owner. It retains admission
through the physical write, refresh, successor publication, compiler verification,
and receipt. Recovery rollback also joins that owner. A background refresh cannot
publish a competing generation between application and verification.

The `kast.change.verification` span records a finite terminal outcome. Publication,
compiler-observation, and semantic-proof failures remain distinguishable without
recording source text, selectors, or plan contents. Installed mutation acceptance
requires a `verified` span from the same application interval as its receipt.

Source content hashes are observations. They do not prove that IntelliJ's VFS has
refreshed, that Gradle has imported a model, or that workspace synchronization has
completed. The implementation keeps these transitions separate.

The imported model guard observes conventional build inputs: Gradle build and
settings scripts, Gradle properties, wrapper and daemon JVM properties, version
catalogs under `gradle`, and `buildSrc` or `build-logic` sources. Changes invalidate
the imported model until a new import establishes it.

The guard checks these inputs before and after semantic capture; a changed or unavailable input rejects
the captured result. This proves agreement at those two observations.

Gradle can execute build logic with other inputs; the guard does not claim to discover all
such inputs. Live model observations include roots, modules, SDK and classpath
URLs. Classpath order remains part of the identity because resolution depends on
precedence. These observations do not hash every library binary.

[InstalledGradleModelInputs](../workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleModelInputs.kt)
and [InstalledGradleSemanticIdentity](../workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledGradleSemanticIdentity.kt)
define this observation boundary.

## Traversal acquires topology when needed

`kast traversal run` acquires an eligible topology snapshot before traversal.
Concurrent first traversals share acquisition, extraction, and publication for the
same current evidence. Subsequent traversals reuse the eligible snapshot.
Source movement requires a snapshot for the successor generation.

Symbol discovery and inspection, source reads, diagnostics, and one-hop relation
reads do not eagerly build topology. A topology acquisition attempt is also not
an extraction: an attempt may find and reuse a published snapshot. Acceptance
therefore checks extraction and publication spans alongside durable SQLite rows,
rather than treating every acquisition span as a rebuild.

[TopologyPreparingTraversalOperations](../runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/graph/TopologyPreparingTraversalOperations.kt)
is the operation boundary. The [lifecycle driver](../integration-tests/lifecycle_convergence_acceptance.py)
checks cold concurrency, runtime reuse, no eager topology, lazy concurrent
traversal, snapshot reuse, stale selectors, and successor topology.

## Configure enterprise trust and proxies

Kast admits a finite set of network settings. It does not forward arbitrary JVM
options from the calling shell to the sidecar. The sidecar environment strips
`JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and `JDK_JAVA_OPTIONS`.

| Setting | Purpose |
| --- | --- |
| `KAST_NETWORK_CONFIG` | Absolute path to a Java properties file containing admitted JSSE and proxy settings. |
| `KAST_TRUST_DONOR_JAVA_HOME` | Selected donor JVM home. Kast reads `lib/security/jssecacerts` when present, otherwise `lib/security/cacerts`. |
| `KAST_IDE_CONFIG_HOME` | IntelliJ configuration directory whose `ssl/cacerts` contains accepted certificates. |
| `GRADLE_USER_HOME` | Gradle user directory, including its consumer-owned `gradle.properties`; automatically admitted into import and cache identity. |

When no explicit donor JVM is configured, the launch boundary can select the
caller's `JAVA_HOME` as the donor. The sidecar still executes with the admitted
IDEA JBR. A donor JVM supplies trust material; it does not replace Gradle JVM
selection or select the sidecar's executable.

The `KAST_NETWORK_CONFIG` file accepts only these property names:

```properties
javax.net.ssl.trustStore=/absolute/path/to/enterprise-truststore.p12
javax.net.ssl.trustStoreType=PKCS12
https.proxyHost=proxy.example.com
https.proxyPort=8443
http.nonProxyHosts=localhost|127.*|*.example.internal
```

This is a configuration example, not a tested proxy deployment. Set only the
properties your environment requires. The remaining admitted keys are
`javax.net.ssl.trustStoreProvider`, `javax.net.ssl.trustStorePassword`,
`http.proxyHost`, and `http.proxyPort`. Types are `JKS` or `PKCS12`; omitting the
type selects `JKS`. A truststore path is required when another trust setting is
present. A proxy port requires its corresponding host.

A truststore password is optional. Store it only in the protected configuration
file when required by that store. An explicitly empty password and an omitted
password are distinct inputs. Passwords do not appear in policy receipts or
configuration rendering. An omitted password does not establish password-based
store integrity. The materializer rejects a store that yields no certificates.

Consumer-owned configuration has precedence:

1. Explicit Gradle trust and proxy configuration.
2. Explicit admitted Kast configuration.
3. Discovered donor certificates and target JVM trust.

A configured `GRADLE_USER_HOME` automatically participates in the admitted import
environment and its cache identity. It does not need a separate entry in
`KAST_GRADLE_IMPORT_VARIABLES`. The
[process environment boundary](../cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/MacOsRuntimeProcessEnvironment.kt)
retains that selection for the sidecar and its Gradle consumer.

Gradle user properties override project properties; supported distribution
properties are considered at the daemon boundary. Existing `org.gradle.jvmargs`
and `systemProp.*` remain Gradle authority. A consumer-owned trust or proxy group
suppresses the corresponding Kast fallback group. Kast does not use inherited
trust to overwrite the consumer through Tooling API `withSystemProperties`.

The [configuration contract](../distribution/contract/src/main/kotlin/io/github/amichne/kast/distribution/contract/network/NetworkConfiguration.kt)
and [Gradle configuration reader](../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/network/GradleNetworkConfiguration.kt)
are the key and precedence authorities.

## Separate consumers and private artifacts

The sidecar Tooling API client can need HTTPS before a Gradle daemon exists,
including when downloading a distribution. Its trust policy is prepared before
IntelliJ initializes networking. The selected Gradle daemon JVM receives its own
policy before import. Dependency resolution in the daemon is a separate consumer
from the sidecar's distribution download.

Kast materializes certificate entries from admitted donors into a private,
content-addressed JKS truststore. It does not copy private or secret key entries,
modify donor stores, or alter the installed JBR store. Certificates are
deduplicated by encoded certificate digest. Publication is serialized and atomic;
reuse verifies the existing artifact's certificate contents.

Sidecar artifacts live in the exact cache's `network` directory. Daemon artifacts
live in `network-daemon`. On POSIX filesystems these directories have mode `0700`
and truststore files have mode `0600`. Each consumer has a bounded
`policy.properties` receipt with digest and provenance. Derived JKS artifacts use
an empty password and contain certificates only; donor passwords are not passed
as derived JVM arguments.

[InstalledNetworkBootstrap](../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/network/InstalledNetworkBootstrap.kt)
resolves the consumers, [DerivedTrustStoreMaterializer](../distribution/managed/src/main/kotlin/io/github/amichne/kast/distribution/managed/network/DerivedTrustStoreMaterializer.kt)
publishes certificate artifacts, and [KastGradleNetworkPolicyExtension](../workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/network/KastGradleNetworkPolicyExtension.kt)
applies daemon fallback at IntelliJ's execution boundary.

## Inspect and prove the installed journey

Bare `kast` reports available product and runtime identity, workspace cache and
trace locations, and configured network source or blocking reasons. Where a
consumer has a recorded policy, inspection reads its bounded receipt and artifact
presence. It does not materialize trust, initialize JSSE, start a process, or
synchronize the workspace. A recorded receipt is not a live TLS handshake or a
fresh verification of all donor contents.

Run the lifecycle driver against a matched installed product and a new evidence
directory:

```shell
python3 integration-tests/lifecycle_convergence_acceptance.py \
  --kast /path/to/installation/bin/kast \
  --fixture fixtures/topology-identity-workspace \
  --evidence /path/to/new/lifecycle-evidence
```

It copies the fixture into its private workspace and retains command output,
OTLP spans, SQLite projections, and a JSON summary. `--reuse` repeats the journey
in that driver's workspace and explicitly omits the cold-start claim. The driver
leaves its sidecar running.

The enterprise driver creates a private CA and a local HTTPS endpoint:

```shell
python3 integration-tests/enterprise_tls_acceptance.py \
  --kast /path/to/installation/bin/kast \
  --gradle /path/to/gradle-9.4.1 \
  --jbr '/path/to/IntelliJ IDEA.app/Contents/jbr/Contents/Home' \
  --evidence /path/to/new/enterprise-evidence
```

Use the unpacked Gradle distribution and admitted JBR available on your host.
The driver proves trusted distribution download and dependency resolution, then
runs separate missing-donor failures for the distribution and repository paths.
It uses fresh Gradle user directories, checks ambient JVM-option isolation, and
checks that donor and target stores remain unchanged. Repository failure requires
the private dependency URL and TLS evidence in Gradle import output. Cleanup
releases each case's runtime and removes generated private keys, including after
a failure.

The [enterprise driver](../integration-tests/enterprise_tls_acceptance.py),
[its regression tests](../integration-tests/test_enterprise_tls_acceptance.py), and
[trust materialization tests](../distribution/managed/src/test/kotlin/io/github/amichne/kast/distribution/managed/network/EnterpriseNetworkPolicyTest.kt)
retain the executable acceptance criteria. This local fixture does not prove an
organization's actual proxy configuration.

## Codex ownership and acceptance

The control distribution includes `kast-codex`. It owns a distinct integration
server and launches the installed Codex client through supported `--remote`
transport. Its state defaults to `~/.codex/kast-integration`; it does not replace
the legacy broker's control-socket owner. A provider tool invokes the semantic CLI
directly with the combined readiness and operation budget, without a preceding
`start` command.

Cold `symbol_lookup`, exact `symbol_inspect`, and repeated `impact_analyze`
requests have completed through the real Codex TUI without model-directed
lifecycle calls. Installed lifecycle and enterprise acceptance also cover their
respective boundaries. The broker's current approval policy admits read-only tools
and excludes mutation tools. This integration does not yet expose working Codex
change tools. See [broker provenance](broker-provenance.md) for the protocol and
presentation boundaries.
