package kast.baseline.program

/** The program is authored here; JSON and dependency waves are projections, never completion flags. */
enum class GateId { POLICY, READINESS, TRUST, GRAPH, BOUNDARIES, CLI, INSTALLED, CODEX, RETIREMENT,
    CLEAN_CHECKOUT, EXACT_HEAD_CI, INDEPENDENT_REVIEW, REVALIDATION }
enum class GateEffect { PURE_CHECK, LOCAL_PROCESS, INSTALLED_SYSTEM, SOURCE_CHANGE, REVIEW }
enum class GateCost { BOUNDED, COMPILE, INSTALL }
enum class Suite(val argument: String) { POLICY("policy"), READINESS("readiness"), TRUST("trust"), GRAPH("graph") }
sealed interface GateAction {
    data class Check(val suite: Suite) : GateAction
    data object BoundaryCheck : GateAction
    data class Unimplemented(val adapter: RequiredAdapter) : GateAction
}
enum class RequiredAdapter { PRODUCTION_CLI_TEST, INSTALLED_GRADLE_TLS_JOURNEY, QUALIFIED_CODEX_JOURNEY,
    PUBLIC_SURFACE_RETIREMENT, DETACHED_CHECKOUT, EXACT_HEAD_CI, INDEPENDENT_REVIEW, REQUIREMENT_REVALIDATION }
enum class Requirement { PASSIVE_BASELINE, SINGLE_READINESS_OWNER, GENERATION_SAFETY, NETWORK_POLICY,
    RUNTIME_ISOLATION, OPTIONAL_CODEX, CLOSED_PUBLIC_SURFACE, PROVEN_DELIVERY }

data class CheckSpecification(val command: List<String>, val expectedFailure: String, val expectedProof: String)
data class TaskNode(
    val id: GateId,
    val title: String,
    val goal: String,
    val dependencies: Set<GateId>,
    val allowedReads: Set<String>,
    val allowedWrites: Set<String>,
    val inputs: Set<String>,
    val outputs: Set<String>,
    val publicInterface: String,
    val internalImplementation: String,
    val effect: GateEffect,
    val cost: GateCost,
    val forbiddenWork: Set<String>,
    val check: CheckSpecification,
    val reviewBoundary: String,
    val completionReceipt: String,
    val action: GateAction,
)

/** The physical example modules are separate from Kast's installed module policy. */
enum class ExampleModule(val path: String, val dependencies: Set<String>, val allowedEffects: Set<String>) {
    MODEL(":model", emptySet(), emptySet()),
    READ(":read", setOf(":model"), emptySet()),
    COORDINATOR(":coordinator", setOf(":model", ":read"), setOf("PREPARATION_ORCHESTRATION")),
    NETWORK(":network", setOf(":model"), setOf("TRUSTSTORE_READ", "JCA_CONSTRUCTION")),
    VERIFICATION(":verification", setOf(":model", ":read", ":coordinator", ":network"),
        setOf("FIXTURE_WRITE", "LOOPBACK_TLS", "PROCESS_EXECUTION"));
}

enum class Step { INSPECT, REPORT, START, START_OR_REUSE, INDEX, READY, READ, TRAVERSE,
    READ_GENERATION, TRAVERSAL_GENERATION, BUILD_OR_REUSE_TOPOLOGY, RESULT, REJECT, RECOVERY_REQUIRED, EXPLICIT_RECOVERY }
sealed interface ProcessEdge {
    data class Next(val from: Step, val to: Step) : ProcessEdge
    data class Choice(val from: Step, val lanes: Set<Step>) : ProcessEdge
    data class Recovery(val from: Step, val authorizedEntry: Step) : ProcessEdge
}
data class Retirement(val gate: GateId, val internalOperations: Set<String>)
data class RequirementProof(val requirement: Requirement, val gates: Set<GateId>, val implementation: Set<String>)

enum class GraphFailure { DUPLICATE_NODE, MISSING_DEPENDENCY, CYCLE }
sealed interface GraphAdmission {
    data class Admitted(val graph: TaskGraph) : GraphAdmission
    data class Rejected(val reason: GraphFailure) : GraphAdmission
}
class TaskGraph private constructor(val nodes: List<TaskNode>, val waves: List<List<GateId>>) {
    companion object {
        fun parse(nodes: List<TaskNode>): GraphAdmission {
            val ids = nodes.map { it.id }
            if (ids.distinct().size != ids.size) return GraphAdmission.Rejected(GraphFailure.DUPLICATE_NODE)
            if (nodes.any { !ids.containsAll(it.dependencies) })
                return GraphAdmission.Rejected(GraphFailure.MISSING_DEPENDENCY)
            val remaining = nodes.associateBy { it.id }.toMutableMap()
            val completed = mutableSetOf<GateId>()
            val waves = mutableListOf<List<GateId>>()
            while (remaining.isNotEmpty()) {
                val wave = remaining.values.filter { completed.containsAll(it.dependencies) }.map { it.id }.sortedBy { it.name }
                if (wave.isEmpty()) return GraphAdmission.Rejected(GraphFailure.CYCLE)
                waves += wave
                completed += wave
                wave.forEach(remaining::remove)
            }
            return GraphAdmission.Admitted(TaskGraph(nodes.sortedBy { it.id.name }, waves))
        }
    }
}

object BaselineProgram {
    const val BASE_REVISION = "d9b4a16d488780386de63e0345b1163cee029a22"
    const val SLOPSENTRAL_REVISION = "4d0227ff9f1385fbddeea407cb543730338fd1f2"
    private fun task(id: GateId, title: String, goal: String, dependencies: Set<GateId>,
        implementation: String, action: GateAction, effect: GateEffect = GateEffect.PURE_CHECK,
        cost: GateCost = GateCost.BOUNDED, expectedFailure: String): TaskNode {
        val taskName = "verifyBaseline" + id.name.lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        return TaskNode(id, title, goal, dependencies,
            setOf(implementation, "program/", "README.md", "../../AGENTS.md"), setOf("build/proofs/${id.name}/"),
            setOf("exact repository head", "program fingerprint", "command digest", "dependency receipts", implementation),
            setOf("build/proofs/${id.name}/receipt.json"), taskName, implementation, effect, cost,
            setOf("modify installed IDEA/JBR", "forward arbitrary JVM arguments", "manufacture receipt", "claim installed proof from examples"),
            CheckSpecification(listOf("./gradlew", "-p", "examples/concise-baseline", taskName),
                expectedFailure, "exit 0 with a verified exact-head PASS receipt"),
            "Review only $implementation and its declared boundary; adjacent capabilities stay out of scope.",
            "build/proofs/${id.name}/receipt.json", action)
    }
    val tasks = listOf(
        task(GateId.POLICY, "Parse per-consumer network policy", "Preserve explicit trust and proxy authority without ambient injection.",
            emptySet(), "model/", GateAction.Check(Suite.POLICY), expectedFailure = "explicit-truststore-is-retained"),
        task(GateId.READINESS, "Coordinate readiness", "Keep preparation out of cheap reads and reject generation changes.",
            emptySet(), "coordinator/", GateAction.Check(Suite.READINESS), expectedFailure = "start-establishes-indexed-readiness"),
        task(GateId.TRUST, "Admit one truststore", "Prove private-certificate TLS without changing hostname verification or source stores.",
            setOf(GateId.POLICY), "network/", GateAction.Check(Suite.TRUST), GateEffect.LOCAL_PROCESS,
            expectedFailure = "default-jvm-trust-rejects-private-certificate (negative control); admitted-trust-accepts-private-certificate (positive control)"),
        task(GateId.GRAPH, "Verify typed graph and receipts", "Reject cycles, missing dependencies, stale receipts, and nondeterministic projection.",
            emptySet(), "program/", GateAction.Check(Suite.GRAPH), expectedFailure = "changed-head-rejects-receipt"),
        task(GateId.BOUNDARIES, "Enforce module boundaries", "Compile read code without preparation dependencies and enforce declared project edges.",
            setOf(GateId.POLICY, GateId.READINESS, GateId.GRAPH), "read/", GateAction.BoundaryCheck,
            GateEffect.LOCAL_PROCESS, GateCost.COMPILE, "forbidden-read-to-preparation-dependency"),
        task(GateId.CLI, "Prove the installed parser default", "Empty argv selects ProductInspect; help and incomplete commands retain their distinct outcomes.",
            emptySet(), "../../cli/", GateAction.Unimplemented(RequiredAdapter.PRODUCTION_CLI_TEST),
            GateEffect.LOCAL_PROCESS, GateCost.COMPILE, "empty-invocation-selects-product-inspection"),
        task(GateId.INSTALLED, "Prove enterprise import", "Wire policy into client and daemon; prove cold distribution and dependency downloads with separate JVMs.",
            setOf(GateId.TRUST, GateId.BOUNDARIES, GateId.CLI), "../../workspace/intellij/", GateAction.Unimplemented(RequiredAdapter.INSTALLED_GRADLE_TLS_JOURNEY),
            GateEffect.INSTALLED_SYSTEM, GateCost.INSTALL, "private CA unavailable to the stock isolated JBR"),
        task(GateId.CODEX, "Qualify optional integration", "Use the exact installed Codex executable; no manual broker service and no core CLI dependency on Codex.",
            setOf(GateId.INSTALLED), "../../cli/src/main/kotlin/io/github/amichne/kast/cli/broker/",
            GateAction.Unimplemented(RequiredAdapter.QUALIFIED_CODEX_JOURNEY), GateEffect.INSTALLED_SYSTEM, GateCost.INSTALL,
            "qualified client cannot invoke a read without manual broker startup"),
        task(GateId.RETIREMENT, "Retire prerequisite commands", "Remove public index.sync and topology.build only after replacement journeys pass.",
            setOf(GateId.INSTALLED, GateId.CODEX), "../../protocol/", GateAction.Unimplemented(RequiredAdapter.PUBLIC_SURFACE_RETIREMENT),
            GateEffect.SOURCE_CHANGE, expectedFailure = "public operation projection contains retired prerequisites"),
        task(GateId.CLEAN_CHECKOUT, "Replay from a detached checkout", "Build and exercise the exact final tree without local state.",
            setOf(GateId.RETIREMENT), "../../distribution/release/", GateAction.Unimplemented(RequiredAdapter.DETACHED_CHECKOUT),
            GateEffect.INSTALLED_SYSTEM, GateCost.INSTALL, "detached installed journey lacks required proof"),
        task(GateId.EXACT_HEAD_CI, "Verify exact-head CI", "Require CI evidence for this head rather than a predecessor or an unbound merge ref.",
            setOf(GateId.CLEAN_CHECKOUT), "../../.github/", GateAction.Unimplemented(RequiredAdapter.EXACT_HEAD_CI),
            GateEffect.INSTALLED_SYSTEM, expectedFailure = "CI receipt belongs to another revision"),
        task(GateId.INDEPENDENT_REVIEW, "Review the full final diff", "Resolve valid findings and invalidate proof after every final edit.",
            setOf(GateId.EXACT_HEAD_CI), "../../", GateAction.Unimplemented(RequiredAdapter.INDEPENDENT_REVIEW),
            GateEffect.REVIEW, expectedFailure = "review is absent, self-authored, unresolved, or for another head"),
        task(GateId.REVALIDATION, "Revalidate the agreed requirement", "All original requirements have PASS evidence at the same exact head.",
            setOf(GateId.INDEPENDENT_REVIEW), "README.md", GateAction.Unimplemented(RequiredAdapter.REQUIREMENT_REVALIDATION),
            GateEffect.REVIEW, expectedFailure = "one or more original requirements lack PASS evidence"),
    )
    val requirements = listOf(
        RequirementProof(Requirement.PASSIVE_BASELINE, setOf(GateId.CLI, GateId.INSTALLED), setOf("../../cli/")),
        RequirementProof(Requirement.SINGLE_READINESS_OWNER, setOf(GateId.READINESS, GateId.INSTALLED), setOf("coordinator/", "../../cli/")),
        RequirementProof(Requirement.GENERATION_SAFETY, setOf(GateId.READINESS, GateId.INSTALLED), setOf("read/", "coordinator/")),
        RequirementProof(Requirement.NETWORK_POLICY, setOf(GateId.POLICY, GateId.TRUST, GateId.INSTALLED), setOf("model/", "network/")),
        RequirementProof(Requirement.RUNTIME_ISOLATION, setOf(GateId.BOUNDARIES, GateId.INSTALLED), setOf("read/", "../../indexer/")),
        RequirementProof(Requirement.OPTIONAL_CODEX, setOf(GateId.CODEX), setOf("../../cli/")),
        RequirementProof(Requirement.CLOSED_PUBLIC_SURFACE, setOf(GateId.RETIREMENT), setOf("../../protocol/", "../../cli/")),
        RequirementProof(Requirement.PROVEN_DELIVERY, setOf(GateId.REVALIDATION), setOf("program/", "buildSrc/")),
    )
    val process = listOf(
        ProcessEdge.Next(Step.INSPECT, Step.REPORT),
        ProcessEdge.Next(Step.START, Step.START_OR_REUSE),
        ProcessEdge.Choice(Step.START_OR_REUSE, setOf(Step.INDEX, Step.RECOVERY_REQUIRED, Step.REJECT)),
        ProcessEdge.Choice(Step.INDEX, setOf(Step.READY, Step.REJECT)),
        ProcessEdge.Next(Step.READ, Step.READ_GENERATION),
        ProcessEdge.Choice(Step.READ_GENERATION, setOf(Step.RESULT, Step.REJECT)),
        ProcessEdge.Next(Step.TRAVERSE, Step.TRAVERSAL_GENERATION),
        ProcessEdge.Choice(Step.TRAVERSAL_GENERATION, setOf(Step.BUILD_OR_REUSE_TOPOLOGY, Step.REJECT)),
        ProcessEdge.Choice(Step.BUILD_OR_REUSE_TOPOLOGY, setOf(Step.RESULT, Step.REJECT)),
        ProcessEdge.Recovery(Step.RECOVERY_REQUIRED, Step.EXPLICIT_RECOVERY),
    )
    val retirement = Retirement(GateId.RETIREMENT, setOf("index.sync", "topology.build", "product.inspect", "status"))
    fun graph(): TaskGraph = when (val admitted = TaskGraph.parse(tasks)) {
        is GraphAdmission.Admitted -> admitted.graph
        is GraphAdmission.Rejected -> error("invalid authored graph: ${admitted.reason}")
    }
}

/** A decoded receipt is untrusted boundary data, never a completion capability. */
data class ReceiptCoordinates(val program: String, val head: String, val base: String,
    val inputs: String, val command: String, val dependencies: Map<GateId, String>)
data class ReceiptDocument(val coordinates: ReceiptCoordinates, val status: String,
    val observations: List<String>, val artifacts: Map<String, String>)
enum class ReceiptFailure { INVALID_IDENTITY, COORDINATES_CHANGED, NOT_PASS, MISSING_OBSERVATION, ARTIFACT_CHANGED }
sealed interface ReceiptAdmission {
    data class Verified(val receipt: VerifiedReceipt) : ReceiptAdmission
    data class Rejected(val reason: ReceiptFailure) : ReceiptAdmission
}
class VerifiedReceipt private constructor(private val coordinates: ReceiptCoordinates) {
    companion object {
        fun parse(raw: ReceiptDocument, expected: ReceiptCoordinates, observedArtifacts: Map<String, String>, observedProofs: List<String>): ReceiptAdmission {
            val digest = Regex("[0-9a-f]{64}")
            val revision = Regex("[0-9a-f]{40}")
            if (!revision.matches(expected.head) || !revision.matches(expected.base) ||
                listOf(expected.program, expected.inputs, expected.command).any { !digest.matches(it) } ||
                expected.dependencies.values.any { !digest.matches(it) })
                return ReceiptAdmission.Rejected(ReceiptFailure.INVALID_IDENTITY)
            if (raw.coordinates != expected) return ReceiptAdmission.Rejected(ReceiptFailure.COORDINATES_CHANGED)
            if (raw.status != "PASS") return ReceiptAdmission.Rejected(ReceiptFailure.NOT_PASS)
            if (raw.observations.isEmpty() || raw.observations != observedProofs) return ReceiptAdmission.Rejected(ReceiptFailure.MISSING_OBSERVATION)
            if (raw.artifacts.isEmpty() || raw.artifacts != observedArtifacts || raw.artifacts.values.any { !digest.matches(it) })
                return ReceiptAdmission.Rejected(ReceiptFailure.ARTIFACT_CHANGED)
            return ReceiptAdmission.Verified(VerifiedReceipt(expected))
        }
    }
}
