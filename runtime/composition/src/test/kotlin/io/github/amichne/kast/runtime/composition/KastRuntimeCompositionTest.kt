package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyOperations
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackPort
import io.github.amichne.kast.change.verify.VerifiedMutationOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.OperationTypeBinding
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.registry.CompletenessPolicy
import io.github.amichne.kast.protocol.registry.OperationCost
import io.github.amichne.kast.protocol.registry.OperationDefinition
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.OperationLane
import io.github.amichne.kast.protocol.registry.OperationScope
import io.github.amichne.kast.protocol.wire.GeneratedOperationSerializers
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.runtime.server.TypedOperationBinding
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.workspace.contract.WorkspaceInspectionOperations
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class KastRuntimeCompositionTest {
    @Test
    fun `all eleven nominal bindings receive their direct target operation boundary`() {
        val services = services()
        val factory = RecordingBindingFactory()

        val composition = (KastRuntimeComposition.create(services, factory) as
            KastRuntimeCompositionConstruction.Created).composition

        assertSame(services.workspace, factory.observed.getValue(CanonicalOperation.WORKSPACE_INSPECT))
        assertSame(services.symbolDiscovery, factory.observed.getValue(CanonicalOperation.SYMBOL_DISCOVER))
        assertSame(services.symbolExact, factory.observed.getValue(CanonicalOperation.SYMBOL_RESOLVE))
        assertSame(services.symbolExact, factory.observed.getValue(CanonicalOperation.SYMBOL_DESCRIBE))
        assertSame(services.relation, factory.observed.getValue(CanonicalOperation.RELATION_READ))
        assertSame(services.traversal, factory.observed.getValue(CanonicalOperation.TRAVERSAL_RUN))
        assertSame(services.diagnostic, factory.observed.getValue(CanonicalOperation.DIAGNOSTIC_CHECK))
        assertSame(services.changeApply, factory.observed.getValue(CanonicalOperation.CHANGE_APPLY))
        assertSame(services.changeVerify, factory.observed.getValue(CanonicalOperation.CHANGE_VERIFY))
        assertEquals(CanonicalOperation.entries.toSet(), factory.observed.keys)
        assertSame(composition.operations.symbolResolve, composition.operations.symbolDescribe)
    }

    @Test
    fun `a binding returned for the wrong nominal operation fails closed`() {
        val factory = RecordingBindingFactory { expected ->
            when (expected) {
                CanonicalOperation.WORKSPACE_INSPECT -> CanonicalOperation.SYMBOL_DISCOVER
                CanonicalOperation.SYMBOL_DISCOVER -> CanonicalOperation.WORKSPACE_INSPECT
                else -> expected
            }
        }

        assertEquals(
            KastRuntimeCompositionConstruction.Rejected(
                setOf(
                    KastRuntimeCompositionFailure.BindingOperationMismatch(
                        CanonicalOperation.WORKSPACE_INSPECT,
                        CanonicalOperation.SYMBOL_DISCOVER,
                    ),
                    KastRuntimeCompositionFailure.BindingOperationMismatch(
                        CanonicalOperation.SYMBOL_DISCOVER,
                        CanonicalOperation.WORKSPACE_INSPECT,
                    ),
                ),
            ),
            KastRuntimeComposition.create(services(), factory),
        )
    }

    private fun services(): KastRuntimeServices = KastRuntimeServices(
        workspace = WorkspaceInspectionOperations { WorkspaceRuntimeState.Absent },
        symbolDiscovery = SymbolDiscoveryOperations { error("not executed") },
        symbolExact = object : SymbolExactOperations {
            override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                error("not executed")

            override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                error("not executed")
        },
        relation = RelationOperations { error("not executed") },
        traversal = TraversalOperations { error("not executed") },
        diagnostic = DiagnosticOperations { error("not executed") },
        changeApply = AddDeclarationApplyOperations { error("not executed") },
        changeVerify = VerifiedMutationOperations { error("not executed") },
        changeRecovery = AddDeclarationRecoveryService(UnusedRecoveryEvidenceStore),
        changeRollback = AddDeclarationRollbackPort { error("not executed") },
    )

    private class RecordingBindingFactory(
        private val operationFor: (CanonicalOperation) -> CanonicalOperation = { it },
    ) : KastOperationBindingFactory {
        val observed = linkedMapOf<CanonicalOperation, Any>()

        override fun workspaceInspect(operations: WorkspaceInspectionOperations) =
            record(CanonicalOperation.WORKSPACE_INSPECT, operations)

        override fun symbolDiscover(operations: SymbolDiscoveryOperations) =
            record(CanonicalOperation.SYMBOL_DISCOVER, operations)

        override fun symbolResolve(operations: SymbolExactOperations) =
            record(CanonicalOperation.SYMBOL_RESOLVE, operations)

        override fun symbolDescribe(operations: SymbolExactOperations) =
            record(CanonicalOperation.SYMBOL_DESCRIBE, operations)

        override fun relationRead(operations: RelationOperations) =
            record(CanonicalOperation.RELATION_READ, operations)

        override fun traversalRun(operations: TraversalOperations) =
            record(CanonicalOperation.TRAVERSAL_RUN, operations)

        override fun diagnosticCheck(operations: DiagnosticOperations) =
            record(CanonicalOperation.DIAGNOSTIC_CHECK, operations)

        override fun changePlan(operations: ChangePlanningOperations) =
            record(CanonicalOperation.CHANGE_PLAN, operations)

        override fun changeApply(operations: AddDeclarationApplyOperations) =
            record(CanonicalOperation.CHANGE_APPLY, operations)

        override fun changeVerify(operations: VerifiedMutationOperations) =
            record(CanonicalOperation.CHANGE_VERIFY, operations)

        override fun changeRecover(operations: ChangeRecoveryOperations) =
            record(CanonicalOperation.CHANGE_RECOVER, operations)

        private fun record(
            expected: CanonicalOperation,
            operations: Any,
        ): TypedOperationBinding<TestRequest, TestResult, TestQualification, TestRejection> {
            observed[expected] = operations
            return binding(operationFor(expected))
        }
    }

    private companion object {
        fun binding(
            operation: CanonicalOperation,
        ): TypedOperationBinding<TestRequest, TestResult, TestQualification, TestRejection> =
            TypedOperationBinding(
                wireBinding = OperationWireBinding(
                    definition = definition(operation),
                    serializers = GeneratedOperationSerializers(
                        TestRequest.serializer(),
                        TestResult.serializer(),
                        TestQualification.serializer(),
                        TestRejection.serializer(),
                    ),
                ),
                handler = {
                    OperationOutcome.Rejected(TestRejection.BLOCKED)
                },
            )

        fun definition(
            operation: CanonicalOperation,
        ): OperationDefinition<
            TestRequest,
            TestResult,
            TestCapability,
            TestQualification,
            TestRejection,
            > = OperationDefinition(
            operation = operation,
            types = OperationTypeBinding(
                TestRequest::class,
                TestResult::class,
                TestQualification::class,
                TestRejection::class,
                SchemaIdentity.parse("kast.${operation.id.value}.v1").refined(),
            ),
            requiredCapability = CapabilityId.parse("semantic.read").refined(),
            capabilityType = TestCapability::class,
            lane = OperationLane.INDEX_LOOKUP,
            effect = OperationEffect.INTELLIJ_READ,
            cost = OperationCost.BOUNDED_READ,
            scope = OperationScope.WORKSPACE,
            budget = ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(10).refined(),
                ElapsedTimeLimitMillis.parse(10).refined(),
            ),
            completeness = CompletenessPolicy.QUALIFIED_ALLOWED,
        )

        fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("invalid test fixture: $failure")
        }
    }
}

private object UnusedRecoveryEvidenceStore : MutationRecoveryEvidenceStore {
    override fun prepare(
        record: MutationRecoveryRecord.PreWriteDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable> = error("not executed")

    override fun recordApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable> = error("not executed")

    override fun <Record : MutationRecoveryRecord.Terminal> recordTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record> = error("not executed")

    override fun load(binding: MutationPlanBinding): MutationRecoveryLoadResult = error("not executed")
}

private data object TestCapability : CapabilityMarker {
    override val id: CapabilityId = when (val refined = CapabilityId.parse("semantic.read")) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> error("invalid test capability: ${refined.failure}")
    }
}

@Serializable
private data class TestRequest(val value: String = "request") : OperationRequest

@Serializable
private data class TestResult(val value: String = "result") : OperationResult

@Serializable
private enum class TestQualification : OperationQualification { LIMITED }

@Serializable
private enum class TestRejection : OperationRejection { BLOCKED }
