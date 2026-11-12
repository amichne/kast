package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueNativeOperation
import io.github.amichne.kast.relation.contract.ValueNativePrerequisite
import io.github.amichne.kast.relation.contract.ValueNativeRelationOrigin
import io.github.amichne.kast.relation.contract.ValueNativeUnexpectedKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Only project inspection is controlled; the production adapter owns mapping and observation. */
class ValueFlowFailureDistinctionTest {
    @Test
    fun `nested index rejection and compiler contract rejection remain different`() {
        val origin = ValueNativeRelationOrigin.IMPLEMENTATION_DISPATCH
        assertNotEquals(
            RelationCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE.nativeFlowRejection(origin),
            RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION.nativeFlowRejection(origin),
        )
        for (received in RelationCompilerRejection.entries) {
            for (source in ValueNativeRelationOrigin.entries) {
                assertEquals(
                    ValueFlowRejection.Native.RelationRejected(source, received),
                    received.nativeFlowRejection(source),
                )
            }
        }
    }

    @Test
    fun `runtime and linkage failures remain different`() = runTest {
        val reasons = mutableListOf<IntellijReadTermination>()
        val observation =
            object : IntellijReadObservation by IntellijReadObservation.None {
                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
                    reasons += reason
                }
            }
        val runtime = rejected(observation) { throw IllegalStateException("private source") }
        val linkage = rejected(observation) { throw NoSuchMethodError("private source") }
        assertEquals(
            listOf(
                IntellijReadTermination.VALUE_FLOW_UNEXPECTED_RUNTIME,
                IntellijReadTermination.VALUE_FLOW_UNEXPECTED_LINKAGE,
            ),
            reasons,
        )
        assertNotEquals(runtime, linkage)
        assertEquals(
            ValueNativeUnexpectedKind.RUNTIME,
            (runtime.cause as ValueFlowRejection.Native.Unexpected).failure.kind,
        )
        assertEquals(
            ValueNativeUnexpectedKind.LINKAGE,
            (linkage.cause as ValueFlowRejection.Native.Unexpected).failure.kind,
        )
        assertEquals(0L, runtime.examinedWorkUnits.value)
        assertEquals(0L, linkage.examinedWorkUnits.value)
        assertFalse(runtime.toString().contains("private source"))
        assertFalse(linkage.toString().contains("private source"))
    }

    @Test
    fun `disposed host does not become an unexpected native failure`() = runTest {
        val disposed = rejected { true }
        assertEquals(
            ValueFlowRejection.Native.HostUnavailable(
                ValueNativeOperation.FLOW_READ,
                ValueNativePrerequisite.PROJECT_DISPOSED,
            ),
            disposed.cause,
        )
        assertNotEquals(disposed, rejected { throw IllegalStateException("private source") })
        assertEquals(0L, disposed.examinedWorkUnits.value)
    }

    @Test
    fun `each native operation binds its bounded unexpected evidence before returning`() = runTest {
        val request = RelationReadTest().request(RelationMeaning.References)
        val stages =
            listOf(
                ValueNativeOperation.FLOW_READ to IntellijReadStage.VALUE_FLOW,
                ValueNativeOperation.SITE_RESOLUTION to IntellijReadStage.VALUE_FLOW,
                ValueNativeOperation.PRODUCER_SEED to IntellijReadStage.VALUE_PRODUCER_SEED,
                ValueNativeOperation.MODEL_DECLARATION to IntellijReadStage.VALUE_MODEL_ADMISSION,
                ValueNativeOperation.MODEL_SITE to IntellijReadStage.VALUE_MODEL_SITE_ADMISSION,
            )
        assertEquals(ValueNativeOperation.entries.toSet(), stages.map { it.first }.toSet())
        for ((operation, stage) in stages) {
            val failures = mutableListOf<IntellijReadUnexpectedFailure>()
            val observation =
                object : IntellijReadObservation by IntellijReadObservation.None {
                    override fun unexpected(failure: IntellijReadUnexpectedFailure) {
                        failures += failure
                    }
                }
            val result =
                IntellijValueFlowCompilerAdapter(observation)
                    .nativeBoundary(
                        project = project { throw IllegalStateException("private source") },
                        current = request.subject.lease,
                        requested = request.subject.lease,
                        rejected = { it },
                        nativeOperation = operation,
                        operation = { error("Semantic work must not be entered") },
                    )
            val actual = assertInstanceOf(ValueFlowRejection.Native.Unexpected::class.java, result)
            val recorded = failures.single()
            assertEquals(operation, actual.operation)
            assertEquals(stage, recorded.stage)
            assertEquals(recorded.exceptionType, actual.failure.exceptionType)
            assertEquals(recorded.adapterFrames, actual.failure.adapterFrames)
            assertFalse(actual.toString().contains("private source"))
        }
    }

    @Test
    fun `cancellation and platform cancellation do not become native rejection`() {
        assertThrows(CancellationException::class.java) {
            runTest { rejected { throw CancellationException("cancel") } }
        }
        assertThrows(ProcessCanceledException::class.java) { runTest { rejected { throw ProcessCanceledException() } } }
    }

    private fun project(disposed: () -> Boolean): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            check(method.name == "isDisposed") { "Unexpected project call ${method.name}" }
            disposed()
        } as Project

    private suspend fun rejected(
        observation: IntellijReadObservation = IntellijReadObservation.None,
        disposed: () -> Boolean,
    ): ValueFlowRead.Rejected {
        val request = RelationReadTest().request(RelationMeaning.References)
        val model =
            WorkspaceSearchScopeModel.compile(
                request.subject.lease.workspaceRoot,
                ImportedWorkspaceModelState.INCOMPLETE,
                emptyList(),
            )
        val site = ValueSite.fromCompiler(request.subject, request.subject.range, ValueRole.ExpressionResult).refined()
        return assertInstanceOf(
            ValueFlowRead.Rejected::class.java,
            IntellijValueFlowCompilerAdapter(observation)
                .read(
                    project = project(disposed),
                    current = request.subject.lease,
                    request = ValueFlowRequest(site, request.budget, request.boundary),
                    model = model,
                ),
        )
    }
}

private fun <T> Refinement<T, *>.refined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
