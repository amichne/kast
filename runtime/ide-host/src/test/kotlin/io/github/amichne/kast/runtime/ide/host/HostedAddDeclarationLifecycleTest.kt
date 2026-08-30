package io.github.amichne.kast.runtime.ide.host

import io.github.amichne.kast.change.verify.ChangeApplicationIdentity
import io.github.amichne.kast.change.verify.ChangeApplicationIssuance
import io.github.amichne.kast.change.verify.ChangeApplicationLookup
import io.github.amichne.kast.change.verify.ChangePlanIdentity
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangePlanLookup
import io.github.amichne.kast.change.verify.ChangeReceiptIssuance
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.change.verify.DurableChangeAuthorityFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryOutcome
import io.github.amichne.kast.change.recovery.PriorStateEvidence
import io.github.amichne.kast.change.recovery.RecoveryRequiredEvidence
import io.github.amichne.kast.change.recovery.UndurableRecoveryRequirement
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.runtime.server.RuntimeServer
import io.github.amichne.kast.runtime.server.RuntimeServerConstruction
import io.github.amichne.kast.runtime.server.ServerDispatch
import io.github.amichne.kast.topology.contract.TopologyBuildFailure
import io.github.amichne.kast.topology.contract.TopologyBuildOperations
import io.github.amichne.kast.topology.contract.TopologyBuildResult
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HostedAddDeclarationLifecycleTest {
    @Test
    fun `unresolved recovery withdraws clean writer authority`() =
        runTest {
            val fixture = HostedMutationProtocolFixture()
            val planningCalls = AtomicInteger()
            val selectorToken = ProtocolText.parse("exact:v1:11:1").refined()
            val recovery = ChangeRecoveryOperations {
                AddDeclarationRecoveryOutcome.RecoveryRequired(
                    RecoveryRequiredEvidence.Undurable(
                        MutationPlanBinding.parse(fixture.plan.planId.value).refined(),
                        UndurableRecoveryRequirement.EVIDENCE_UNAVAILABLE,
                    ),
                )
            }
            val clean = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ ->
                    planningCalls.incrementAndGet()
                    HostedChangePlanningResult.Rejected(
                        HostedMutationAdmissionFailure.INTENT_REJECTED,
                    )
                },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = recovery,
                publication = publication(),
            )
            val selectors = object : HostedExactSelectorOperations {
                override fun issueExact(selector: io.github.amichne.kast.symbol.contract.SymbolSelector) =
                    HostedExactIssuance.Issued(selectorToken)

                override suspend fun exact(token: ProtocolText) =
                    HostedExactLookup.Found(fixture.selector)
            }
            val authority = object : DurableChangeAuthority by rejectedAuthority {
                override fun loadPlan(identity: ChangePlanIdentity) =
                    ChangePlanLookup.Found(fixture.plan)
            }
            val server = RuntimeServer.createHostedEffects(
                topologyBindings() + HostedMutationProtocol.bindings(
                    clean,
                    HostedMutationAdmissionOperations { error("not invoked") },
                    selectors,
                    authority,
                ),
            ).created()

            server.dispatch(
                CanonicalOperationWireBindings.changeRecover.encodeRequest(
                    ChangeRecoverRequest(
                        ProtocolText.parse("plan:${fixture.plan.planId.value}").refined(),
                    ),
                ).encoded(),
            ).responded()
            val plan = server.dispatch(
                CanonicalOperationWireBindings.changePlan.encodeRequest(
                    ChangePlanRequest(
                        ChangeIntentDocument.AddDeclaration(
                            selectorToken,
                            ProtocolText.parse("fun another(): Unit = Unit").refined(),
                        ),
                    ),
                ).encoded(),
            ).responded()

            assertEquals(
                OperationOutcome.Rejected(ChangePlanRejection.RECOVERY_REQUIRED),
                CanonicalOperationWireBindings.changePlan.decodeOutcome(plan).decoded(),
            )
            assertEquals(0, planningCalls.get())
        }

    @Test
    fun `successful live recovery withdraws and re-admits clean writer authority without restart`() =
        runTest {
            val fixture = HostedMutationProtocolFixture()
            val planningCalls = AtomicInteger()
            val recoveryEvents = mutableListOf<String>()
            val selectorToken = ProtocolText.parse("exact:v1:11:1").refined()
            val selectors = object : HostedExactSelectorOperations {
                override fun issueExact(selector: io.github.amichne.kast.symbol.contract.SymbolSelector) =
                    HostedExactIssuance.Issued(selectorToken)

                override suspend fun exact(token: ProtocolText) =
                    HostedExactLookup.Found(fixture.selector)
            }
            val recovery = ChangeRecoveryOperations {
                recoveryEvents += "recover"
                AddDeclarationRecoveryOutcome.PriorState(
                    PriorStateEvidence.Absent(
                        MutationPlanBinding.parse(fixture.plan.planId.value).refined(),
                    ),
                )
            }
            val restored = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ ->
                    planningCalls.incrementAndGet()
                    HostedChangePlanningResult.Rejected(
                        HostedMutationAdmissionFailure.INTENT_REJECTED,
                    )
                },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = recovery,
                publication = publication(),
            )
            val recoveredWorkspace = fixture.successorWorkspace(12)
            val recoveryPublication = publication(
                publishCurrent = {
                    recoveryEvents += "publish-successor"
                    io.github.amichne.kast.change.verify.ResultingGenerationPublication.Published(
                        recoveredWorkspace,
                    )
                },
            )
            val active = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ -> error("not invoked before recovery") },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = recovery,
                publication = recoveryPublication,
            )
            val authority = object : DurableChangeAuthority by rejectedAuthority {
                override fun loadPlan(identity: ChangePlanIdentity) =
                    ChangePlanLookup.Found(fixture.plan)
            }
            val server = RuntimeServer.createHostedEffects(
                topologyBindings() + HostedMutationProtocol.bindings(
                    active,
                    HostedMutationAdmissionOperations {
                        recoveryEvents += "re-admit"
                        restored
                    },
                    selectors,
                    authority,
                ),
            ).created()

            val recovered = server.dispatch(
                CanonicalOperationWireBindings.changeRecover.encodeRequest(
                    ChangeRecoverRequest(
                        ProtocolText.parse("plan:${fixture.plan.planId.value}").refined(),
                    ),
                ).encoded(),
            ).responded()
            val plan = server.dispatch(
                CanonicalOperationWireBindings.changePlan.encodeRequest(
                    ChangePlanRequest(
                        ChangeIntentDocument.AddDeclaration(
                            selectorToken,
                            ProtocolText.parse("fun another(): Unit = Unit").refined(),
                        ),
                    ),
                ).encoded(),
            ).responded()

            assertEquals(
                OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED),
                CanonicalOperationWireBindings.changePlan.decodeOutcome(plan).decoded(),
            )
            val recoveryOutcome = CanonicalOperationWireBindings.changeRecover
                .decodeOutcome(recovered)
                .decoded() as OperationOutcome.Complete
            assertEquals(12L, recoveryOutcome.evidence.generation.value)
            assertEquals(listOf("recover", "publish-successor", "re-admit"), recoveryEvents)
            assertEquals(1, planningCalls.get())
        }

    @Test
    fun `rejected durable application issuance immediately withdraws writer authority`() =
        runTest {
            val fixture = HostedMutationProtocolFixture()
            val applicationCalls = AtomicInteger()
            val publicationCalls = AtomicInteger()
            val planningCalls = AtomicInteger()
            val selectorToken = ProtocolText.parse("exact:v1:11:1").refined()
            val selectors = object : HostedExactSelectorOperations {
                override fun issueExact(selector: io.github.amichne.kast.symbol.contract.SymbolSelector) =
                    HostedExactIssuance.Issued(selectorToken)

                override suspend fun exact(token: ProtocolText) =
                    HostedExactLookup.Found(fixture.selector)
            }
            val authority = object : DurableChangeAuthority by rejectedAuthority {
                override fun loadPlan(identity: ChangePlanIdentity) =
                    ChangePlanLookup.Found(fixture.plan)
            }
            val state = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ ->
                    planningCalls.incrementAndGet()
                    HostedChangePlanningResult.Rejected(
                        HostedMutationAdmissionFailure.INTENT_REJECTED,
                    )
                },
                application = ChangeApplyOperations {
                    applicationCalls.incrementAndGet()
                    fixture.applied
                },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = ChangeRecoveryOperations { error("not invoked") },
                publication = publication(
                    publishAfter = {
                        publicationCalls.incrementAndGet()
                        publicationRejected()
                    },
                ),
            )
            val server = RuntimeServer.createHostedEffects(
                topologyBindings() + HostedMutationProtocol.bindings(
                    state,
                    HostedMutationAdmissionOperations { state },
                    selectors,
                    authority,
                ),
            ).created()
            val apply = CanonicalOperationWireBindings.changeApply.encodeRequest(
                ChangeApplyRequest(
                    ProtocolText.parse("plan:${"a".repeat(64)}").refined(),
                ),
            ).encoded()

            val first = server.dispatch(apply).responded()
            val second = server.dispatch(apply).responded()
            val plan = server.dispatch(
                CanonicalOperationWireBindings.changePlan.encodeRequest(
                    ChangePlanRequest(
                        ChangeIntentDocument.AddDeclaration(
                            selectorToken,
                            ProtocolText.parse("fun another(): Unit = Unit").refined(),
                        ),
                    ),
                ).encoded(),
            ).responded()

            assertEquals(
                OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED),
                CanonicalOperationWireBindings.changeApply.decodeOutcome(first).decoded(),
            )
            assertEquals(
                OperationOutcome.Rejected(ChangeApplyRejection.RECOVERY_REQUIRED),
                CanonicalOperationWireBindings.changeApply.decodeOutcome(second).decoded(),
            )
            assertEquals(
                OperationOutcome.Rejected(ChangePlanRejection.RECOVERY_REQUIRED),
                CanonicalOperationWireBindings.changePlan.decodeOutcome(plan).decoded(),
            )
            assertEquals(1, applicationCalls.get())
            assertEquals(1, publicationCalls.get())
            assertEquals(0, planningCalls.get())
        }

    @Test
    fun `targeted source divergence requests a live workspace publication`() =
        runTest {
            val fixture = HostedMutationProtocolFixture()
            val publicationCalls = AtomicInteger()
            val authority = object : DurableChangeAuthority by rejectedAuthority {
                override fun loadPlan(identity: ChangePlanIdentity) =
                    ChangePlanLookup.Found(fixture.plan)
            }
            val state = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ -> error("not invoked") },
                application = ChangeApplyOperations {
                    io.github.amichne.kast.change.apply.AddDeclarationApplyResult.Rejected(
                        io.github.amichne.kast.change.apply.AddDeclarationApplyFailure.Admission(
                            io.github.amichne.kast.change.apply.MutationAdmissionFailure.SOURCE_CONTENT_CHANGED,
                        ),
                    )
                },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = ChangeRecoveryOperations { error("not invoked") },
                publication = publication(
                    publishAfter = {
                        publicationCalls.incrementAndGet()
                        publicationRejected()
                    },
                ),
            )
            val server = RuntimeServer.createHostedEffects(
                topologyBindings() + HostedMutationProtocol.bindings(
                    state,
                    HostedMutationAdmissionOperations { state },
                    missingSelectors,
                    authority,
                ),
            ).created()

            val response = server.dispatch(
                CanonicalOperationWireBindings.changeApply.encodeRequest(
                    ChangeApplyRequest(
                        ProtocolText.parse("plan:${"a".repeat(64)}").refined(),
                    ),
                ).encoded(),
            ).responded()

            assertEquals(
                OperationOutcome.Rejected(ChangeApplyRejection.CONTENT_CHANGED),
                CanonicalOperationWireBindings.changeApply.decodeOutcome(response).decoded(),
            )
            assertEquals(1, publicationCalls.get())
        }

    @Test
    fun `hosted mutation table exposes exactly four lifecycle routes and only add declaration`() =
        runTest {
            val planningCalls = AtomicInteger()
            val clean = HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ ->
                    planningCalls.incrementAndGet()
                    HostedChangePlanningResult.Rejected(HostedMutationAdmissionFailure.INTENT_REJECTED)
                },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = ChangeRecoveryOperations { error("not invoked") },
                publication = publication(),
            )
            val mutationBindings = HostedMutationProtocol.bindings(
                clean,
                HostedMutationAdmissionOperations { clean },
                missingSelectors,
                rejectedAuthority,
            )
            assertEquals(
                setOf(
                    CanonicalOperation.CHANGE_PLAN,
                    CanonicalOperation.CHANGE_APPLY,
                    CanonicalOperation.CHANGE_VERIFY,
                    CanonicalOperation.CHANGE_RECOVER,
                ),
                mutationBindings.mapTo(linkedSetOf()) { it.operation },
            )
            val server = RuntimeServer.createHostedEffects(
                topologyBindings() + mutationBindings,
            ).created()
            val request = ChangePlanRequest(
                ChangeIntentDocument.AddFile(
                    ProtocolText.parse("Other.kt").refined(),
                    ProtocolText.parse("class Other").refined(),
                ),
            )
            val encoded = CanonicalOperationWireBindings.changePlan.encodeRequest(request).encoded()
            val response = server.dispatch(encoded).responded()
            assertEquals(
                OperationOutcome.Rejected(ChangePlanRejection.INTENT_REJECTED),
                CanonicalOperationWireBindings.changePlan.decodeOutcome(response).decoded(),
            )
            assertEquals(0, planningCalls.get())
        }

    @Test
    fun `recovery required state cannot carry planning or application authority`() {
        val state = HostedMutationState.RecoveryRequired(
            ChangeRecoveryOperations { error("not invoked") },
            publication(),
        )
        assertFalse(state::class.java.declaredFields.any { it.type == Boolean::class.javaPrimitiveType })
        assertEquals(
            listOf("recovery", "publication"),
            state::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") },
        )
    }

    @Test
    fun `live recovery transition drops clean mutation authority without a boolean protocol`() {
        val recovery = ChangeRecoveryOperations { error("not invoked") }
        val publication = publication()
        val runtime = HostedMutationRuntimeState(
            HostedMutationState.Clean(
                planning = ChangePlanOperations { _, _ -> error("not invoked") },
                application = ChangeApplyOperations { error("not invoked") },
                verification = ChangeVerifyOperations { error("not invoked") },
                recovery = recovery,
                publication = publication,
            ),
            HostedMutationAdmissionOperations { error("not invoked") },
        )

        runtime.requireRecovery()

        assertEquals(
            HostedMutationState.RecoveryRequired(recovery, publication),
            runtime.current(),
        )
        assertFalse(runtime::class.java.declaredFields.any { it.type == Boolean::class.javaPrimitiveType })
    }

    private fun topologyBindings() = HostedTopologyProtocol.bindings(
        HostedTopologyOperations(
            TopologyBuildOperations {
                TopologyBuildResult.Rejected(TopologyBuildFailure.WorkspaceNotReady)
            },
            TraversalOperations {
                TraversalResult.Rejected(TraversalRejection.RequiredEvidenceUnavailable)
            },
        ),
        missingSelectors,
    )

    private fun publication(
        publishAfter: (
            io.github.amichne.kast.workspace.contract.SemanticReadLease,
        ) -> io.github.amichne.kast.change.verify.ResultingGenerationPublication = {
            error("not invoked")
        },
        publishCurrent: () -> io.github.amichne.kast.change.verify.ResultingGenerationPublication = {
            error("not invoked")
        },
    ): HostedMutationPublicationOperations = object : HostedMutationPublicationOperations {
        override fun publishAfter(
            prior: io.github.amichne.kast.workspace.contract.SemanticReadLease,
        ) = publishAfter(prior)

        override fun publishCurrentTransition() = publishCurrent()
    }

    private fun publicationRejected() =
        io.github.amichne.kast.change.verify.ResultingGenerationPublication.Rejected(
            io.github.amichne.kast.change.verify.ResultingGenerationPublicationRejection.CURRENT_PUBLICATION_UNAVAILABLE,
        )

    private fun RuntimeServerConstruction.created(): RuntimeServer = when (this) {
        is RuntimeServerConstruction.Created -> server
        is RuntimeServerConstruction.Rejected -> error(failures.toString())
    }

    private fun WireEncoding.encoded(): String = when (this) {
        is WireEncoding.Encoded -> document
        is WireEncoding.Rejected -> error(failure.toString())
    }

    private fun ServerDispatch.responded(): String = when (this) {
        is ServerDispatch.Responded -> document
        is ServerDispatch.Rejected -> error(failure.toString())
    }

    private fun <Value> WireDecoding<Value>.decoded(): Value = when (this) {
        is WireDecoding.Decoded -> value
        is WireDecoding.Rejected -> error(failure.toString())
    }

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error(failure.toString())
        }

    private companion object {
        val missingSelectors = object : HostedExactSelectorOperations {
            override fun issueExact(selector: io.github.amichne.kast.symbol.contract.SymbolSelector) =
                HostedExactIssuance.Rejected

            override suspend fun exact(token: ProtocolText) = HostedExactLookup.Missing
        }

        val rejectedAuthority = object : DurableChangeAuthority {
            override fun issuePlan(plan: io.github.amichne.kast.change.contract.ChangePlan) =
                ChangePlanIssuance.Rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)

            override fun loadPlan(identity: ChangePlanIdentity) = ChangePlanLookup.Missing

            override fun issueApplication(
                plan: io.github.amichne.kast.change.contract.ChangePlan,
                application: io.github.amichne.kast.change.apply.AppliedUnverified,
            ) = ChangeApplicationIssuance.Rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)

            override fun loadApplication(identity: ChangeApplicationIdentity) =
                ChangeApplicationLookup.Missing

            override fun issueReceipt(receipt: io.github.amichne.kast.change.verify.VerifiedReceipt) =
                ChangeReceiptIssuance.Rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)
        }
    }
}
