package support.delivery

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import support.architecture.ArchitecturePolicyValidation
import support.architecture.IdeReadFirewall
import support.architecture.IdeReadFirewallProof
import support.architecture.IdeReadFirewallReportResult
import support.architecture.IdeReadFirewallResult
import support.architecture.KastArchitecturePolicy
import support.architecture.decodeIdeReadFirewallReport

internal enum class Kvp009GateCommand { RED, GREEN }

internal data class Kvp009ReceiptContexts(
    val boundary: Kvp001ReceiptContext,
    val predecessors: List<AdmittedProofReceipt>,
    val taskId: String,
    val redGateId: String,
    val greenGateId: String,
    val completionGateId: String,
    val redReceiptId: String,
    val greenReceiptId: String,
    val completionReceiptId: String,
    val redCommand: String,
    val greenCommand: String,
    val completionCommand: String,
    val taskInputDigest: String,
    val completionInputDigest: String,
    val proofReportPath: String,
) {
    private val predecessorDigests = predecessors.associate {
        it.receiptId.value to it.digest.value
    }

    /**
     * Proof transition: canonical architecture policy -> `IdeReadFirewallProof`.
     * Establishes the exact planned module and forbidden-authority closure. Expected policy or
     * firewall rejection is rendered as finite receipt failure at this outer Gradle boundary.
     */
    fun proof(): IdeReadFirewallProof = when (val policy = KastArchitecturePolicy.validate()) {
        is ArchitecturePolicyValidation.Invalid -> rejectReceipt(
            "KVP-009 architecture policy",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
        )
        is ArchitecturePolicyValidation.Valid -> when (val result = IdeReadFirewall.derive(
            policy.architecture,
        )) {
            is IdeReadFirewallResult.Complete -> result.proof
            is IdeReadFirewallResult.Rejected -> rejectReceipt(
                "KVP-009 firewall proof",
                ProofReceiptFailure.MALFORMED_OBSERVATION,
            )
        }
    }

    /**
     * Proof transition: configured firewall report bytes -> `IdeReadFirewallProof`.
     * Establishes closed-schema equality with an independent canonical derivation. Expected
     * report failures remain [support.architecture.IdeReadFirewallReportFailure] until rendered at
     * this outer Gradle boundary.
     */
    fun reportProof(): IdeReadFirewallProof = when (
        val result = decodeIdeReadFirewallReport(boundary.readText(proofReportPath))
    ) {
        is IdeReadFirewallReportResult.Complete -> result.proof
        is IdeReadFirewallReportResult.Rejected -> rejectReceipt(
            "KVP-009 firewall report",
            ProofReceiptFailure.MALFORMED_OBSERVATION,
            result.failure.name,
        )
    }

    fun redExpectation(proof: IdeReadFirewallProof) = boundary.expectation(
        redReceiptId,
        redGateId,
        redCommand,
        taskInputDigest,
        predecessorDigests,
        mapOf(
            "forbiddenAuthorities" to proof.authorityObservation(),
            "rejectedAuthorityCount" to proof.forbiddenAuthorities.size.toString(),
        ),
        emptyMap(),
        taskId,
    )

    fun greenExpectation(red: AdmittedProofReceipt, proof: IdeReadFirewallProof) =
        boundary.expectation(
            greenReceiptId,
            greenGateId,
            greenCommand,
            taskInputDigest,
            predecessorDigests + (red.receiptId.value to red.digest.value),
            mapOf(
                "forbiddenAuthorityCount" to proof.forbiddenAuthorities.size.toString(),
                "modulePolicies" to proof.moduleObservation(),
                "role" to "IDE_READ_ONLY",
                "schemaVersion" to "1",
            ),
            boundary.artifactDigests(listOf(proofReportPath)),
            taskId,
        )

    fun completionExpectation(red: AdmittedProofReceipt, green: AdmittedProofReceipt) =
        boundary.expectation(
            completionReceiptId,
            completionGateId,
            completionCommand,
            completionInputDigest,
            predecessorDigests + mapOf(
                red.receiptId.value to red.digest.value,
                green.receiptId.value to green.digest.value,
            ),
            mapOf(
                "admittedDependencyReceiptCount" to predecessors.size.toString(),
                "admittedGateReceiptCount" to "2",
            ),
            emptyMap(),
            taskId,
        )
}

private fun IdeReadFirewallProof.authorityObservation(): String = forbiddenAuthorities.entries
    .sortedBy { it.key.name }
    .joinToString(",") { (authority, effects) ->
        "${authority.name}=${effects.map(Enum<*>::name).sorted().joinToString("+")}"
    }

private fun IdeReadFirewallProof.moduleObservation(): String = modules
    .sortedBy { it.id.projectPath }
    .joinToString(",") { module ->
        val dependencies = module.allowedProjectDependencies.map { it.projectPath }.sorted()
        val effects = module.allowedEffects.map(Enum<*>::name).sorted()
        "${module.id.projectPath}=${dependencies.joinToString("+")};${effects.joinToString("+")}"
    }

abstract class Kvp009ReceiptTaskBase : Kvp006ReceiptTaskBase() {
    @get:Input abstract val firewallTaskId: Property<String>
    @get:Input abstract val firewallRedGateId: Property<String>
    @get:Input abstract val firewallGreenGateId: Property<String>
    @get:Input abstract val firewallCompletionGateId: Property<String>
    @get:Input abstract val firewallRedReceiptId: Property<String>
    @get:Input abstract val firewallGreenReceiptId: Property<String>
    @get:Input abstract val firewallCompletionReceiptId: Property<String>
    @get:Input abstract val firewallRedCommand: Property<String>
    @get:Input abstract val firewallGreenCommand: Property<String>
    @get:Input abstract val firewallCompletionCommand: Property<String>
    @get:Input abstract val firewallTaskInputDigest: Property<String>
    @get:Input abstract val firewallCompletionInputDigest: Property<String>
    @get:Input abstract val firewallProofReportPath: Property<String>
    @get:InputFile abstract val directGateGraphRedReceiptFile: RegularFileProperty
    @get:InputFile abstract val directGateGraphGreenReceiptFile: RegularFileProperty
    @get:InputFile abstract val directGateGraphProofReportFile: RegularFileProperty
    @get:InputFile abstract val directGateGraphCompletionReceiptFile: RegularFileProperty

    /**
     * Proof transition: declared KVP-009 command plus closed gate identity -> successful process.
     * Establishes exact command equality and zero exit. Expected mismatch is
     * [ProofReceiptFailure.COMMAND_DIGEST_MISMATCH]; raw arguments leave only at Gradle exec.
     */
    internal fun runFirewallGate(command: String, gate: Kvp009GateCommand) {
        val expected = when (gate) {
            Kvp009GateCommand.RED -> "./gradlew verifyKastVfsPassiveFirewallNegative"
            Kvp009GateCommand.GREEN ->
                "./gradlew verifyKastModuleGraph verifyForbiddenEffects verifyKastVfsPassiveFirewall"
        }
        if (command != expected) {
            rejectReceipt("KVP-009 gate command", ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
        }
        val arguments = when (gate) {
            Kvp009GateCommand.RED -> listOf("verifyKastVfsPassiveFirewallNegative")
            Kvp009GateCommand.GREEN -> listOf(
                "verifyKastModuleGraph",
                "verifyForbiddenEffects",
                "verifyKastVfsPassiveFirewall",
            )
        }
        execOperations.exec {
            workingDir(repositoryRoot().toFile())
            commandLine(listOf("./gradlew") + arguments)
        }
    }

    /**
     * Proof transition: configured KVP-009 inputs plus `AuthorityGitRevision` ->
     * `Kvp009ReceiptContexts`.
     * Establishes direct admission of KVP-001 and KVP-006 completion and their transitive closure.
     * Expected receipt failures remain closed until rendered at the outer Gradle boundary.
     */
    internal fun firewallContexts(head: AuthorityGitRevision): Kvp009ReceiptContexts {
        val authority = context(head)
        val authorityRed = authority.admit(
            authorityRedReceiptFile.get().asFile.toPath(),
            authority.redExpectation(),
        )
        val authorityGreen = authority.admit(
            authorityGreenReceiptFile.get().asFile.toPath(),
            authority.greenExpectation(authorityRed),
        )
        val authorityCompletion = authority.admit(
            authorityCompletionReceiptFile.get().asFile.toPath(),
            authority.completionExpectation(authorityRed, authorityGreen),
        )
        val gateGraph = gateGraphContexts(head)
        val negative = gateGraph.negativeProof()
        val proof = gateGraph.reportProof()
        val gateGraphRed = gateGraph.boundary.admit(
            directGateGraphRedReceiptFile.get().asFile.toPath(),
            gateGraph.redExpectation(negative),
        )
        val gateGraphGreen = gateGraph.boundary.admit(
            directGateGraphGreenReceiptFile.get().asFile.toPath(),
            gateGraph.greenExpectation(gateGraphRed, proof),
        )
        val gateGraphCompletion = gateGraph.boundary.admit(
            directGateGraphCompletionReceiptFile.get().asFile.toPath(),
            gateGraph.completionExpectation(gateGraphRed, gateGraphGreen),
        )
        return Kvp009ReceiptContexts(
            authority,
            listOf(authorityCompletion, gateGraphCompletion),
            firewallTaskId.get(),
            firewallRedGateId.get(),
            firewallGreenGateId.get(),
            firewallCompletionGateId.get(),
            firewallRedReceiptId.get(),
            firewallGreenReceiptId.get(),
            firewallCompletionReceiptId.get(),
            firewallRedCommand.get(),
            firewallGreenCommand.get(),
            firewallCompletionCommand.get(),
            firewallTaskInputDigest.get(),
            firewallCompletionInputDigest.get(),
            firewallProofReportPath.get(),
        )
    }
}
