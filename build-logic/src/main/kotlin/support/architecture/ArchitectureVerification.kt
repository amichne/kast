package support.architecture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal sealed interface ArchitectureVerificationAdmission {
    data class Accepted(val evidence: AcceptedArchitectureVerification) :
        ArchitectureVerificationAdmission

    data class Rejected(val violations: Set<ArchitectureViolation>) :
        ArchitectureVerificationAdmission
}

/** Proof that the canonical policy admitted one typed observation graph and its effects. */
internal class AcceptedArchitectureVerification private constructor(
    val architecture: ValidatedArchitecturePolicy,
    private val graph: ObservedProjectGraph,
    private val report: ByteArray,
) {
    fun reportBytes(): ByteArray = report.copyOf()

    fun projectDependencies(): Set<ProjectDependencyObservation> =
        graph.projectDependencies.toSet()

    fun exportedProjectDependencies(): Set<ProjectDependencyObservation> =
        graph.exportedProjectDependencies.toSet()

    companion object {
        internal fun establish(
            architecture: ValidatedArchitecturePolicy,
            graph: ObservedProjectGraph,
            effects: Set<EffectObservation>,
        ): ArchitectureVerificationAdmission = when (
            val admission = ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    graph.modules,
                    graph.projectDependencies,
                    effects,
                    graph.exportedProjectDependencies,
                    graph.moduleRoleConventions,
                ),
            )
        ) {
            ArchitectureAdmission.Accepted -> ArchitectureVerificationAdmission.Accepted(
                AcceptedArchitectureVerification(
                    architecture,
                    graph.snapshot(),
                    encodeArchitectureReport("ACCEPTED", emptyList()),
                ),
            )
            is ArchitectureAdmission.Rejected -> ArchitectureVerificationAdmission.Rejected(
                admission.violations,
            )
        }
    }
}

private fun ObservedProjectGraph.snapshot(): ObservedProjectGraph = ObservedProjectGraph(
    modules = modules.toSet(),
    projectDependencies = projectDependencies.toSet(),
    exportedProjectDependencies = exportedProjectDependencies.toSet(),
    moduleRoleConventions = ModuleRoleConventionObservation.Collected(
        moduleRoleConventions.conventions.toMap(),
    ),
)

@Serializable
internal data class ArchitectureReportDocument(
    val schemaVersion: Int,
    val status: String,
    val findings: List<ArchitectureReportFinding>,
)

@Serializable
internal data class ArchitectureReportFinding(
    val code: String,
    val message: String,
    val attributes: Map<String, String>,
)

internal fun architectureFinding(
    code: String,
    message: String,
    vararg attributes: Pair<String, String>,
): ArchitectureReportFinding = ArchitectureReportFinding(
    code = code,
    message = message,
    attributes = mapOf(*attributes).toSortedMap(),
)

internal fun encodeArchitectureReport(
    status: String,
    findings: List<ArchitectureReportFinding>,
): ByteArray = (
    architectureReportJson.encodeToString(
        ArchitectureReportDocument.serializer(),
        ArchitectureReportDocument(
            schemaVersion = 1,
            status = status,
            findings = findings,
        ),
    ) + "\n"
).encodeToByteArray()

private val architectureReportJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}
