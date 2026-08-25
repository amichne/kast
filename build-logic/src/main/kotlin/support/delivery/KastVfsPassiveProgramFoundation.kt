package support.delivery

internal fun deliveryRequirements(): List<Requirement> = listOf(
            Requirement(RequirementId("KVP-REQ-001"), "Current amichne/kast head is the sole tooling-state authority and the program rejects a stale target head."),
            Requirement(RequirementId("KVP-REQ-002"), "The delivery definition is a typed Kotlin graph executed and verified through Gradle, not a prose backlog."),
            Requirement(RequirementId("KVP-REQ-003"), "The task graph, module graph, process graph, gate graph, schemas, and traceability projections are deterministic."),
            Requirement(RequirementId("KVP-REQ-004"), "Progress and completion derive only from proof-carrying receipts bound to the exact head and program fingerprint."),
            Requirement(RequirementId("KVP-REQ-005"), "The default semantic runtime executes inside the existing IntelliJ process and existing open Project."),
            Requirement(RequirementId("KVP-REQ-006"), "The default path starts no kast-indexer process, opens no second Project, imports no Gradle model, and causes no second indexing cycle."),
            Requirement(RequirementId("KVP-REQ-007"), "The default install contains no private IDEA home, JBR, bundled IntelliJ platform, or semantic-runtime archive."),
            Requirement(RequirementId("KVP-REQ-008"), "The CLI admits one compatible exact-root IDE endpoint and never falls back to runtime acquisition or process launch."),
            Requirement(RequirementId("KVP-REQ-009"), "The MVP endpoint supports exactly workspace.inspect, symbol.discover, symbol.resolve, and symbol.describe."),
            Requirement(RequirementId("KVP-REQ-010"), "Discovery candidates refine into exact selectors and selectors round-trip to the same declaration."),
            Requirement(RequirementId("KVP-REQ-011"), "The normal hosted read path is VFS-passive and invokes no VFS refresh API or Gradle refresh/import API."),
            Requirement(RequirementId("KVP-REQ-012"), "Hosted semantic reads use cancellable write-priority smart reads and reject dumb mode, disposal, cancellation, or movement."),
            Requirement(RequirementId("KVP-REQ-013"), "Each Project admits at most one active Kast semantic read and no unbounded queue."),
            Requirement(RequirementId("KVP-REQ-014"), "No hosted read action performs Files.walk, source-tree hashing, network access, blocking waits, or repository traversal."),
            Requirement(RequirementId("KVP-REQ-015"), "Every accepted result is detached and revalidated against an IDE-visible epoch observed before and after the read."),
            Requirement(RequirementId("KVP-REQ-016"), "The read-only plugin classpath contains no project-open, Gradle-import, VFS-refresh, source-write, topology-build, JDBC, or isolated-runtime implementation."),
            Requirement(RequirementId("KVP-REQ-017"), "The endpoint binds canonical root, runtime identity, IDE build, Kotlin plugin build, plugin version, process ID, schema digests, capabilities, and Project lifecycle."),
            Requirement(RequirementId("KVP-REQ-018"), "Unsupported operations and incompatible endpoints fail closed before semantic dispatch."),
            Requirement(RequirementId("KVP-REQ-019"), "Project close, plugin unload, endpoint publication failure, and socket failure retire all endpoint artifacts without stale readiness."),
            Requirement(RequirementId("KVP-REQ-020"), "Installed acceptance directly proves PID reuse, Project reuse, zero import, zero refresh, zero indexer process, zero runtime archive reads, selector exactness, and endpoint retirement."),
            Requirement(RequirementId("KVP-REQ-021"), "The combined default control and plugin download is at most 80 MiB."),
            Requirement(RequirementId("KVP-REQ-022"), "A detached clean checkout can reproduce the program projection and all required proof gates."),
            Requirement(RequirementId("KVP-REQ-023"), "Exact-head CI rejects receipts, artifacts, commands, or projections produced for another revision."),
            Requirement(RequirementId("KVP-REQ-024"), "An independent final review records every finding, resolves every valid finding, and reruns affected gates."),
            Requirement(RequirementId("KVP-REQ-025"), "The final gate revalidates every original requirement point by point and only PASS is successful."),
            Requirement(RequirementId("KVP-REQ-026"), "No task or machine-readable projection contains a writable completion flag or manually editable status."),
            Requirement(RequirementId("KVP-REQ-027"), "VFS event storms create no per-event semantic jobs, no semantic work on the EDT, and no stale accepted result."),
)
internal fun deliveryModules(): List<ModuleBoundary> = listOf(
            ModuleBoundary(
                ModuleId(":acceptance:ide-hosted"), "ADDED", "ACCEPTANCE",
                listOf("Installed-system instrumentation and proof"), setOf(ModuleId(":distribution:release")), setOf(AuthorityId("INSTALLED_ACCEPTANCE")), setOf(EffectId("INSTALLED_SYSTEM_EXECUTION"), EffectId("TEST_PROCESS_CONTROL")),
            ),
            ModuleBoundary(
                ModuleId(":build-logic"), "EXISTING", "BUILD_POLICY",
                listOf("Delivery program DSL", "Graph validation", "Receipt admission", "Projection generation", "Derived completion"), emptySet(), setOf(AuthorityId("DELIVERY_PROGRAM"), AuthorityId("PROOF_RECEIPT")), setOf(EffectId("BUILD_POLICY_WRITE")),
            ),
            ModuleBoundary(
                ModuleId(":cli"), "EXISTING", "CLI",
                listOf("Command projection", "Exact-root endpoint admission", "UDS client"), setOf(ModuleId(":protocol:contract"), ModuleId(":protocol:registry"), ModuleId(":protocol:wire")), setOf(AuthorityId("CLI_ENDPOINT_ADMISSION")), setOf(EffectId("FILESYSTEM_READ"), EffectId("UDS_CONNECT")),
            ),
            ModuleBoundary(
                ModuleId(":distribution:contract"), "EXISTING", "CONTRACT",
                listOf("Installed product identity"), setOf(ModuleId(":kernel")), setOf(AuthorityId("DISTRIBUTION_IDENTITY")), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":distribution:managed"), "REMOVED_FROM_DEFAULT", "LEGACY_DISTRIBUTION",
                listOf("Legacy semantic-runtime acquisition until retirement"), setOf(ModuleId(":distribution:contract")), emptySet(), setOf(EffectId("NETWORK_READ"), EffectId("RUNTIME_ARCHIVE_READ")),
            ),
            ModuleBoundary(
                ModuleId(":distribution:release"), "ADDED", "DISTRIBUTION",
                listOf("Control plus plugin release manifest"), setOf(ModuleId(":distribution:contract"), ModuleId(":cli"), ModuleId(":ide-plugin")), setOf(AuthorityId("DEFAULT_DISTRIBUTION")), setOf(EffectId("PACKAGE_WRITE")),
            ),
            ModuleBoundary(
                ModuleId(":ide-plugin"), "ADDED", "INTELLIJ_PLUGIN_HOST",
                listOf("Project service", "UDS endpoint", "Descriptor lifecycle"), setOf(ModuleId(":protocol:wire"), ModuleId(":runtime:ide-read")), setOf(AuthorityId("IDE_ENDPOINT")), setOf(EffectId("UDS_BIND"), EffectId("ENDPOINT_DESCRIPTOR_WRITE")),
            ),
            ModuleBoundary(
                ModuleId(":indexer"), "REMOVED_FROM_DEFAULT", "LEGACY_INDEXER_HOST",
                listOf("Explicit legacy fixture only until retirement"), setOf(ModuleId(":runtime:composition")), emptySet(), setOf(EffectId("PROCESS_START"), EffectId("GRADLE_IMPORT"), EffectId("VFS_REFRESH")),
            ),
            ModuleBoundary(
                ModuleId(":kernel"), "EXISTING", "CONTRACT",
                listOf("Named values", "Closed outcomes", "Budgets", "Evidence identity"), emptySet(), emptySet(), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":protocol:contract"), "EXISTING", "CONTRACT",
                listOf("Canonical operation identity", "Outcome contract"), setOf(ModuleId(":kernel")), setOf(AuthorityId("OPERATION_REGISTRY")), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":protocol:registry"), "EXISTING", "REGISTRY",
                listOf("Exact four-operation MVP capability set"), setOf(ModuleId(":protocol:contract"), ModuleId(":workspace:contract"), ModuleId(":symbol:contract")), setOf(AuthorityId("OPERATION_REGISTRY")), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":protocol:wire"), "EXISTING", "TRANSPORT_CONTRACT",
                listOf("Wire envelope", "Generated codecs", "Schema digest"), setOf(ModuleId(":protocol:contract")), setOf(AuthorityId("WIRE_SCHEMA")), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":runtime:composition"), "EXISTING_EXCLUDED", "FULL_COMPOSITION",
                listOf("Existing full isolated graph, excluded from IDE plugin"), emptySet(), emptySet(), setOf(EffectId("JDBC"), EffectId("SOURCE_WRITE"), EffectId("TOPOLOGY_BUILD"), EffectId("GRADLE_IMPORT")),
            ),
            ModuleBoundary(
                ModuleId(":runtime:ide-read"), "ADDED", "INTELLIJ_READ_COMPOSITION",
                listOf("Four-operation read graph", "Single-flight admission", "Epoch revalidation"), setOf(ModuleId(":kernel"), ModuleId(":protocol:contract"), ModuleId(":protocol:registry"), ModuleId(":protocol:wire"), ModuleId(":workspace:contract"), ModuleId(":workspace:service"), ModuleId(":workspace:intellij-read"), ModuleId(":symbol:contract"), ModuleId(":symbol:service"), ModuleId(":symbol:intellij"), ModuleId(":runtime:server")), setOf(AuthorityId("READ_RUNTIME")), setOf(EffectId("IDE_PROJECT_READ"), EffectId("NATIVE_INDEX_READ"), EffectId("SEMANTIC_READ")),
            ),
            ModuleBoundary(
                ModuleId(":runtime:server"), "EXISTING", "TRANSPORT",
                listOf("Typed dispatch"), setOf(ModuleId(":protocol:contract"), ModuleId(":protocol:wire")), emptySet(), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":symbol:contract"), "EXISTING", "CONTRACT",
                listOf("Discovery candidate", "Exact selector", "Detached description"), setOf(ModuleId(":kernel"), ModuleId(":workspace:contract")), setOf(AuthorityId("SYMBOL_IDENTITY")), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":symbol:intellij"), "EXISTING", "INTELLIJ_READ_ADAPTER",
                listOf("Native index query", "K2 exact resolution", "Detached projection"), setOf(ModuleId(":symbol:contract"), ModuleId(":workspace:contract")), setOf(AuthorityId("SYMBOL_IDENTITY")), setOf(EffectId("NATIVE_INDEX_READ"), EffectId("SEMANTIC_READ")),
            ),
            ModuleBoundary(
                ModuleId(":symbol:service"), "EXISTING", "SERVICE",
                listOf("Bounded discovery and exact read workflow"), setOf(ModuleId(":symbol:contract"), ModuleId(":workspace:contract")), emptySet(), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":workspace:contract"), "EXISTING", "CONTRACT",
                listOf("Canonical root", "Project read epoch", "Detached workspace model", "Read lease"), setOf(ModuleId(":kernel")), setOf(AuthorityId("CANONICAL_ROOT"), AuthorityId("READ_EPOCH")), emptySet(),
            ),
            ModuleBoundary(
                ModuleId(":workspace:intellij-read"), "ADDED", "INTELLIJ_READ_ADAPTER",
                listOf("Existing Project admission", "Detached model capture", "Epoch observation"), setOf(ModuleId(":kernel"), ModuleId(":workspace:contract")), setOf(AuthorityId("OPEN_PROJECT"), AuthorityId("READ_EPOCH")), setOf(EffectId("IDE_PROJECT_READ")),
            ),
            ModuleBoundary(
                ModuleId(":workspace:service"), "EXISTING", "SERVICE",
                listOf("Read-only workspace inspection semantics"), setOf(ModuleId(":workspace:contract")), emptySet(), emptySet(),
            ),
)

internal fun deliveryAuthorities(): List<AuthorityOwnership> = listOf(
            AuthorityOwnership(AuthorityId("DELIVERY_PROGRAM"), ModuleId(":build-logic"), "Canonical task, module, process, gate, and requirement graph."),
            AuthorityOwnership(AuthorityId("PROOF_RECEIPT"), ModuleId(":build-logic"), "Receipt admission and derived progression."),
            AuthorityOwnership(AuthorityId("OPERATION_REGISTRY"), ModuleId(":protocol:registry"), "Exact operation and capability set."),
            AuthorityOwnership(AuthorityId("WIRE_SCHEMA"), ModuleId(":protocol:wire"), "Canonical request, response, and endpoint schema digests."),
            AuthorityOwnership(AuthorityId("CANONICAL_ROOT"), ModuleId(":workspace:contract"), "Canonical exact repository root."),
            AuthorityOwnership(AuthorityId("OPEN_PROJECT"), ModuleId(":workspace:intellij-read"), "The one already-open IntelliJ Project admitted for the exact root."),
            AuthorityOwnership(AuthorityId("READ_EPOCH"), ModuleId(":workspace:intellij-read"), "IDE-visible model, PSI, and VFS epoch used for before/after revalidation."),
            AuthorityOwnership(AuthorityId("SYMBOL_IDENTITY"), ModuleId(":symbol:intellij"), "Compiler-grounded exact declaration identity."),
            AuthorityOwnership(AuthorityId("READ_RUNTIME"), ModuleId(":runtime:ide-read"), "Capability-scoped read-only operation graph."),
            AuthorityOwnership(AuthorityId("IDE_ENDPOINT"), ModuleId(":ide-plugin"), "Project-scoped endpoint and lifecycle."),
            AuthorityOwnership(AuthorityId("CLI_ENDPOINT_ADMISSION"), ModuleId(":cli"), "Compatible exact-root endpoint selection."),
            AuthorityOwnership(AuthorityId("DEFAULT_DISTRIBUTION"), ModuleId(":distribution:release"), "Control plus plugin default payload."),
            AuthorityOwnership(AuthorityId("INSTALLED_ACCEPTANCE"), ModuleId(":acceptance:ide-hosted"), "Installed-system observations."),
)

internal fun deliveryEffects(): List<EffectOwnership> = listOf(
            EffectOwnership(EffectId("BUILD_POLICY_WRITE"), setOf(ModuleId(":build-logic")), "Write generated policy, projection, reports, and receipts."),
            EffectOwnership(EffectId("IDE_PROJECT_READ"), setOf(ModuleId(":workspace:intellij-read"), ModuleId(":runtime:ide-read")), "Read current Project model and lifecycle."),
            EffectOwnership(EffectId("NATIVE_INDEX_READ"), setOf(ModuleId(":symbol:intellij"), ModuleId(":runtime:ide-read")), "Query productized IntelliJ indexes."),
            EffectOwnership(EffectId("SEMANTIC_READ"), setOf(ModuleId(":symbol:intellij"), ModuleId(":runtime:ide-read")), "Resolve exact K2 semantics."),
            EffectOwnership(EffectId("UDS_BIND"), setOf(ModuleId(":ide-plugin")), "Bind one project-scoped Unix socket."),
            EffectOwnership(EffectId("ENDPOINT_DESCRIPTOR_WRITE"), setOf(ModuleId(":ide-plugin")), "Atomically publish and retire endpoint metadata."),
            EffectOwnership(EffectId("UDS_CONNECT"), setOf(ModuleId(":cli")), "Connect to one admitted endpoint."),
            EffectOwnership(EffectId("FILESYSTEM_READ"), setOf(ModuleId(":cli"), ModuleId(":acceptance:ide-hosted")), "Read descriptor and installed artifacts."),
            EffectOwnership(EffectId("PACKAGE_WRITE"), setOf(ModuleId(":distribution:release")), "Build deterministic control and plugin release assets."),
            EffectOwnership(EffectId("INSTALLED_SYSTEM_EXECUTION"), setOf(ModuleId(":acceptance:ide-hosted")), "Run the installed journey."),
            EffectOwnership(EffectId("TEST_PROCESS_CONTROL"), setOf(ModuleId(":acceptance:ide-hosted")), "Observe and fault-inject test processes."),
            EffectOwnership(EffectId("PROCESS_START"), emptySet(), "Forbidden for the default semantic read path."),
            EffectOwnership(EffectId("GRADLE_IMPORT"), emptySet(), "Forbidden for the IDE-hosted read product."),
            EffectOwnership(EffectId("VFS_REFRESH"), emptySet(), "Forbidden for the VFS-passive read product."),
            EffectOwnership(EffectId("SOURCE_WRITE"), emptySet(), "Forbidden from the read-only plugin graph."),
            EffectOwnership(EffectId("JDBC"), emptySet(), "Forbidden from the read-only plugin graph."),
            EffectOwnership(EffectId("TOPOLOGY_BUILD"), emptySet(), "Forbidden from the read-only plugin graph."),
            EffectOwnership(EffectId("NETWORK_READ"), emptySet(), "Forbidden from installed semantic demand."),
            EffectOwnership(EffectId("RUNTIME_ARCHIVE_READ"), emptySet(), "Forbidden from the default product."),
)
