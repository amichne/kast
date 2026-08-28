package support.hostedwriter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@JvmInline
value class ProofGateId(val value: String)

@Serializable
@JvmInline
value class ProofInput(val value: String)

@Serializable
@JvmInline
value class ProofOutput(val value: String)

@Serializable
@JvmInline
value class ProofCommand(val value: String)

@Serializable
@JvmInline
value class ObservedProof(val value: String)

@Serializable
@JvmInline
value class ForbiddenWork(val value: String)

@Serializable
enum class ReviewBoundary {
    BUILD_LOGIC,
    CANONICAL_PROTOCOL,
    MODULE_ARCHITECTURE,
    INTELLIJ_ADAPTERS,
    SQLITE_STATE,
    HOSTED_TOPOLOGY,
    HOSTED_MUTATION,
    ENDPOINT_PUBLICATION,
    INSTALLED_PRODUCT,
}

@Serializable
data class HostedWriterTask(
    val id: ProofGateId,
    val title: String,
    val goal: String,
    val dependencies: Set<ProofGateId>,
    val allowedReads: Set<ProofInput>,
    val allowedWrites: Set<ProofOutput>,
    val command: ProofCommand,
    val expectedProof: Set<ObservedProof>,
    val forbiddenWork: Set<ForbiddenWork>,
    val reviewBoundary: ReviewBoundary,
)

@Serializable
data class HostedWriterProgramDocument(
    val schemaVersion: Int,
    val tasks: List<HostedWriterTask>,
) {
    fun task(id: ProofGateId): HostedWriterTask = tasks.single { it.id == id }
}

sealed interface HostedWriterProgramReplay {
    data class Admitted(
        val document: HostedWriterProgramDocument,
    ) : HostedWriterProgramReplay

    data object Malformed : HostedWriterProgramReplay
    data object NotCanonical : HostedWriterProgramReplay
    data object WrongProgram : HostedWriterProgramReplay
}

/** Fixed hosted-writer proof graph. Gradle remains the only executable scheduler. */
object HostedWriterProgram {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    val document: HostedWriterProgramDocument = HostedWriterProgramDocument(
        schemaVersion = 1,
        tasks = listOf(
            task(
                id = "PROGRAM",
                title = "Fixed hosted-writer program",
                goal = "Project the fixed Kotlin gate graph, schemas, and exact-head receipt protocol.",
                dependencies = emptySet(),
                reads = setOf("build-logic", "gradle/hosted-writer"),
                writes = setOf("build/reports/hosted-writer/program.json"),
                command = "./gradlew generateHostedWriterProgram",
                proofs = setOf("deterministic program JSON passes its checked-in schema and exact replay"),
                forbidden = setOf("generic scheduler", "manually editable delivery status"),
                boundary = ReviewBoundary.BUILD_LOGIC,
            ),
            task(
                id = "SURFACE",
                title = "Canonical hosted surface",
                goal = "Derive hosted exposure, variants, routes, descriptor, CLI checks, and acceptance expectations from canonical operations.",
                dependencies = setOf("PROGRAM"),
                reads = setOf(":protocol:registry", ":protocol:contract", ":protocol:wire", ":cli"),
                writes = setOf("canonical hosted operation projection"),
                command = "./gradlew :protocol:registry:test --tests '*HostedOperationProjectionTest'",
                proofs = setOf("all hosted consumers share one exact generated PUBLIC operation set"),
                forbidden = setOf("parallel operation tables", "advertised unsupported mutation intents"),
                boundary = ReviewBoundary.CANONICAL_PROTOCOL,
            ),
            task(
                id = "HOST-BOUNDARY",
                title = "IDE host module boundary",
                goal = "Add the thin hosted composition and exact IDE_HOST dependency and effect policy.",
                dependencies = setOf("SURFACE"),
                reads = setOf("settings.gradle.kts", "build-logic architecture policy", ":runtime:ide-read"),
                writes = setOf(":runtime:ide-host", "IDE_HOST role policy"),
                command = "./gradlew :build-logic:test --tests '*IdeHostModuleBoundaryTest' verifyKastArchitecture",
                proofs = setOf("hosted graph and effect ownership pass mechanically"),
                forbidden = setOf("changing :runtime:ide-read", "depending on :runtime:composition"),
                boundary = ReviewBoundary.MODULE_ARCHITECTURE,
            ),
            task(
                id = "PROJECT-PORTS",
                title = "Direct-project hosted ports",
                goal = "Admit the already-open exact-root Project into capability-specific topology and mutation ports.",
                dependencies = setOf("HOST-BOUNDARY"),
                reads = setOf(":topology:intellij", ":relation:intellij", ":diagnostic:intellij", ":change:intellij"),
                writes = setOf("hosted physical adapter factories"),
                command = "./gradlew :ide-plugin:test --tests '*HostedProjectEffectAdmissionTest'",
                proofs = setOf("exact-root admissions and host classpath smoke tests pass", "forbidden Project escape and lookup scans pass"),
                forbidden = setOf("ProjectManager.openProjects", "public Project accessor", "generic withProject callback", "service locator", "root-to-Project lookup"),
                boundary = ReviewBoundary.INTELLIJ_ADAPTERS,
            ),
            task(
                id = "DURABLE-STATE",
                title = "Exact-root durable state",
                goal = "Locate durable workspace state and persist public change authority and recovery in SQLite.",
                dependencies = setOf("HOST-BOUNDARY"),
                reads = setOf(":workspace:contract", ":change:contract", ":evidence:contract", ":evidence:sqlite"),
                writes = setOf("topology.sqlite", "mutation.sqlite"),
                command = "./gradlew :evidence:sqlite:test --tests '*HostedChangeAuthorityRestartTest'",
                proofs = setOf("plan application recovery corruption and root isolation survive reopen"),
                forbidden = setOf("endpoint /tmp state", "nullable lookup", "raw Path in core"),
                boundary = ReviewBoundary.SQLITE_STATE,
            ),
            task(
                id = "TOPOLOGY",
                title = "Hosted topology lifecycle",
                goal = "Bind topology.build and traversal.run to durable exact-root topology state.",
                dependencies = setOf("PROJECT-PORTS", "DURABLE-STATE"),
                reads = setOf("hosted topology ports", "topology.sqlite"),
                writes = setOf("hosted topology bindings"),
                command = "./gradlew :runtime:ide-host:test --tests '*HostedTopologyLifecycleTest'",
                proofs = setOf("build reuse restart-read invalidation and stale-selector cases pass"),
                forbidden = setOf("hidden automatic build", "unbounded graph method"),
                boundary = ReviewBoundary.HOSTED_TOPOLOGY,
            ),
            task(
                id = "MUTATION",
                title = "Hosted add-declaration lifecycle",
                goal = "Bind only add-declaration plan apply verify and recover with recovery-only degraded state.",
                dependencies = setOf("PROJECT-PORTS", "DURABLE-STATE", "TOPOLOGY"),
                reads = setOf("hosted relation diagnostic mutation ports", "mutation.sqlite", "topology.sqlite"),
                writes = setOf("hosted add-declaration bindings"),
                command = "./gradlew :runtime:ide-host:test --tests '*HostedAddDeclarationLifecycleTest'",
                proofs = setOf("plan restart apply restart verify and failure recovery cases pass"),
                forbidden = setOf("other mutation intents", "K2 or SQLite inside IntelliJ write action", "second write implementation"),
                boundary = ReviewBoundary.HOSTED_MUTATION,
            ),
            task(
                id = "ENDPOINT",
                title = "Generated endpoint publication",
                goal = "Publish the exact generated PUBLIC capability set only after every binding is admitted.",
                dependencies = setOf("TOPOLOGY", "MUTATION"),
                reads = setOf("hosted runtime bindings", "canonical hosted projection"),
                writes = setOf("endpoint descriptor", "hosted compatibility report"),
                command = "./gradlew :ide-plugin:test :ide-plugin:buildPlugin",
                proofs = setOf("exact generated capabilities and all-or-nothing startup pass"),
                forbidden = setOf("hand-maintained capability list", "partial runtime publication"),
                boundary = ReviewBoundary.ENDPOINT_PUBLICATION,
            ),
            task(
                id = "INSTALLED-PROOF",
                title = "Installed hosted-writer proof",
                goal = "Exercise packaged CLI and plugin through positive restart and required negative exact-root journeys.",
                dependencies = setOf("ENDPOINT"),
                reads = setOf("packaged CLI", "packaged plugin", "exact-root fixtures"),
                writes = setOf("build/reports/hosted-writer/installed-acceptance.json", "build/reports/hosted-writer/receipts/INSTALLED-PROOF.json"),
                command = "./gradlew hostedWriterProof",
                proofs = setOf("installed journey restart journey negative fixtures clean checkout and exact-head CI pass"),
                forbidden = setOf("test-only transport", "isolated indexer fallback"),
                boundary = ReviewBoundary.INSTALLED_PRODUCT,
            ),
        ),
    ).also(::requireExactGraph)

    fun encoded(): String = json.encodeToString(document) + "\n"

    fun replay(raw: String): HostedWriterProgramReplay {
        val decoded = try {
            json.decodeFromString<HostedWriterProgramDocument>(raw)
        } catch (_: IllegalArgumentException) {
            return HostedWriterProgramReplay.Malformed
        }
        return try {
            requireExactGraph(decoded)
            when {
                decoded != document -> HostedWriterProgramReplay.WrongProgram
                encoded() != raw -> HostedWriterProgramReplay.NotCanonical
                else -> HostedWriterProgramReplay.Admitted(decoded)
            }
        } catch (_: IllegalArgumentException) {
            HostedWriterProgramReplay.WrongProgram
        }
    }

    private fun task(
        id: String,
        title: String,
        goal: String,
        dependencies: Set<String>,
        reads: Set<String>,
        writes: Set<String>,
        command: String,
        proofs: Set<String>,
        forbidden: Set<String>,
        boundary: ReviewBoundary,
    ): HostedWriterTask = HostedWriterTask(
        ProofGateId(id),
        title,
        goal,
        dependencies.mapTo(linkedSetOf(), ::ProofGateId),
        reads.mapTo(linkedSetOf(), ::ProofInput),
        writes.mapTo(linkedSetOf(), ::ProofOutput),
        ProofCommand(command),
        proofs.mapTo(linkedSetOf(), ::ObservedProof),
        forbidden.mapTo(linkedSetOf(), ::ForbiddenWork),
        boundary,
    )

    private fun requireExactGraph(candidate: HostedWriterProgramDocument) {
        require(candidate.schemaVersion == 1)
        require(candidate.tasks.size == 9)
        require(candidate.tasks.map { it.id }.distinct().size == candidate.tasks.size)
        val prior = linkedSetOf<ProofGateId>()
        candidate.tasks.forEach { task ->
            require(task.id.value.isNotBlank())
            require(task.title.isNotBlank() && task.goal.isNotBlank())
            require(task.command.value.isNotBlank() && task.expectedProof.isNotEmpty())
            require(prior.containsAll(task.dependencies))
            prior += task.id
        }
    }
}
