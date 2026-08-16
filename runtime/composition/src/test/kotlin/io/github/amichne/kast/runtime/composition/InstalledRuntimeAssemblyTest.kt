package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.verify.ChangeVerificationObserver
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerPort
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.relation.contract.RelationCompilerPort
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelBoundary
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelRead
import io.github.amichne.kast.runtime.composition.platform.projectInstalledGradleModel
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmission
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionFailure
import io.github.amichne.kast.symbol.contract.SymbolCompilerPort
import io.github.amichne.kast.symbol.contract.SymbolExactCompilerPort
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.startCoroutine

class InstalledRuntimeAssemblyTest {
    @Test
    fun `production assembly owns persistence publication handlers and dispatch`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val state = Files.createDirectories(temporary.resolve("state")).toRealPath()
        val fixture = InstalledChangeProtocolFixture.create(root)
        val published = fixture.published
        val sourceRoot = published.sourceRoots.single()
        val read = projectInstalledGradleModel(
            InstalledGradleModelBoundary(
                published.root,
                true,
                listOf(
                    WorkspaceSourceRootBoundary(
                        sourceRoot.owner.module.value,
                        root.resolve(sourceRoot.owner.project.buildRoot.value).normalize(),
                        sourceRoot.owner.project.projectPath.value,
                        sourceRoot.owner.sourceSet.value,
                        root.resolve(sourceRoot.location.value).normalize(),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    ),
                ),
                listOf("classpath:file:///fixture.jar", "source:fixture"),
            ),
        ) as InstalledGradleModelRead.Captured
        val assembler = productionInstalledRuntimeAssembler(
            InstalledRuntimeAssemblyInputs(
                workspaceModel = { read },
                semantic = unusedSemanticPorts(),
                change = unusedChangePhysicalPorts(),
                changeAdmission = {
                    ChangePlanAdmission.Rejected(ChangePlanAdmissionFailure.INTENT_REJECTED)
                },
            ),
        )

        val construction = InstalledKastRuntime.create(root, state, assembler)

        val created = construction as InstalledKastRuntimeConstruction.Created
        assertTrue(Files.isRegularFile(state.resolve("workspace-publication.sqlite")))
        assertTrue(Files.isRegularFile(state.resolve("mutation-recovery.sqlite")))
        val request = CanonicalOperationWireBindings.workspaceInspect
            .encodeRequest(WorkspaceInspectRequest)
            .encoded()
        val response = runAssemblyImmediate { created.dispatch.dispatch(request) } as
            KastRuntimeDispatch.Responded
        val outcome: OperationOutcome<
            WorkspaceInspectResult,
            WorkspaceInspectQualification,
            WorkspaceInspectRejection,
            > = CanonicalOperationWireBindings.workspaceInspect
            .decodeOutcome(response.document)
            .decoded()
        when (outcome) {
            is OperationOutcome.Complete -> {
                assertEquals(root.toString(), outcome.evidence.payload.canonicalRoot.value)
                assertEquals(WorkspaceStateDocument.READY, outcome.evidence.payload.state)
            }
            else -> error("unexpected workspace outcome: $outcome")
        }
    }

    private fun unusedSemanticPorts(): SemanticRuntimePorts = SemanticRuntimePorts(
        symbolDiscovery = SymbolCompilerPort { error("not executed") },
        symbolExact = object : SymbolExactCompilerPort {
            override suspend fun resolve(
                request: io.github.amichne.kast.symbol.contract.SymbolResolutionRequest,
            ): io.github.amichne.kast.symbol.contract.SymbolResolutionCompilation =
                error("not executed")

            override suspend fun describe(
                request: io.github.amichne.kast.symbol.contract.ExactSymbolRequest,
            ): io.github.amichne.kast.symbol.contract.SymbolDescriptionCompilation =
                error("not executed")
        },
        relation = RelationCompilerPort { error("not executed") },
        diagnostic = DiagnosticCompilerPort { error("not executed") },
    )

    private fun unusedChangePhysicalPorts(): InstalledChangePhysicalPorts =
        InstalledChangePhysicalPorts(
            sourceObserver = AddDeclarationSourceObserver { error("not executed") },
            sourceWriter = AddDeclarationSourceWriter { _, _ -> error("not executed") },
            sourceRollback = AddDeclarationSourceRollback { _, _ -> error("not executed") },
            recoveryRollback = AddDeclarationRollbackPort { error("not executed") },
            verificationObserver = ChangeVerificationObserver { error("not executed") },
        )
}

private fun WireEncoding.encoded(): String = when (this) {
    is WireEncoding.Encoded -> document
    is WireEncoding.Rejected -> error("unexpected encoding rejection: $failure")
}

private fun <Value> WireDecoding<Value>.decoded(): Value = when (this) {
    is WireDecoding.Decoded -> value
    is WireDecoding.Rejected -> error("unexpected decoding rejection: $failure")
}

private fun <Value> runAssemblyImmediate(block: suspend () -> Value): Value {
    var completed: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed) { "operation suspended unexpectedly" }.getOrThrow()
}
