package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactStep
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.relation.contract.BoundaryContractIdentity
import io.github.amichne.kast.relation.contract.BoundaryKind
import io.github.amichne.kast.relation.contract.BoundaryModel
import io.github.amichne.kast.relation.contract.BoundaryPosition
import io.github.amichne.kast.relation.contract.BoundaryRequiredEvidence
import io.github.amichne.kast.relation.contract.BoundaryTerminalMeaning
import io.github.amichne.kast.relation.contract.ConsumerRepresentationEvidence
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RepresentationCurrent
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RepresentationState
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactModelExecutionTest {
    @Test
    fun `guarded origin and transformation retain exact representation at consumer`() = runTest {
        val f = QueryImpactExecutionFixture()
        val decrypt = f.call("decrypt", 70, 80)
        val sink = f.call("sink", 90, 100)
        val input = f.argument(decrypt)
        val consumption = f.argument(sink)
        val model = representationCase(f, decrypt, sink)
        val plaintext = model.plaintext
        val expectation = model.expectation
        val plan = f.plan(model.rules)
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        listOf(f.edge(f.producer.site, input, ValueTransferKind.ARGUMENT)),
                    ),
                    ImpactReadExpectation(input, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)),
                    ImpactReadExpectation(
                        decrypt.resultSite(),
                        listOf(f.edge(decrypt.resultSite(), consumption, ValueTransferKind.ARGUMENT)),
                    ),
                    ImpactReadExpectation(consumption, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL)),
                )
            )
        val complete =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                f.service(native.port).run(f.request(plan = plan)),
            )
        val path = (complete.result.rows as QueryRows.ValuePaths).values.single()
        val evidence = (path.representation as QueryImpactRepresentation.Present).evidence
        assertEquals(RepresentationCurrent.Known(plaintext), evidence.branches.single().current)
        assertEquals(
            listOf(f.producer.site, input, decrypt.resultSite(), consumption),
            listOf(path.producer) + path.steps.map { it.target },
        )
        assertTrue(path.steps[1] is QueryImpactStep.ModeledRepresentation)
        val consumer = (path.terminal as QueryImpactTerminal.Consumer).evidence
        assertTrue(consumer is ConsumerRepresentationEvidence.Different)
        assertEquals(expectation, consumer.rule)
        native.assertConsumed()
    }

    @Test
    fun `reviewed persistence terminal retains boundary obligations`() = runTest {
        val f = QueryImpactExecutionFixture()
        val contract = BoundaryContractIdentity(id("storage"), ModelVersion.parse(1).value())
        val position = BoundaryPosition.at(f.producer.site, BoundaryKind.PERSISTENCE, contract, id("ciphertext"))
        val reference =
            ModelRuleReference(
                ContractModelIdentity(id("storage"), ModelVersion.parse(1).value(), id("review:914")),
                id("retention"),
            )
        val boundary =
            BoundaryModel.Terminal.admit(
                    reference,
                    position.reference,
                    position,
                    BoundaryTerminalMeaning.REVIEWED_RETENTION,
                )
                .value()
        val plan = f.plan(boundaries = listOf(boundary))
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(f.producer.site, causes = listOf(ValueFlowUnsupportedCause.UNMODELED_CALL))
                )
            )
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port).run(f.request(plan = plan)),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        val terminal = rows.values.single().terminal as QueryImpactTerminal.ModeledTerminal
        assertEquals(
            setOf(
                BoundaryRequiredEvidence.RETENTION_POLICY,
                BoundaryRequiredEvidence.DECODING_COMPATIBILITY,
                BoundaryRequiredEvidence.MIGRATION_PROOF,
            ),
            terminal.boundary.obligations.single().required,
        )
        assertEquals(
            setOf(QueryImpactRequiredObligation.BOUNDARY),
            (rows.accountingStatus as QueryValuePathAccountingStatus.Unresolved).required,
        )
        native.assertConsumed()
    }

    private data class RepresentationCase(
        val rules: List<RepresentationRule>,
        val plaintext: RepresentationState,
        val expectation: RepresentationRule.ConsumerExpectation,
    )

    private fun representationCase(
        f: QueryImpactExecutionFixture,
        decrypt: ValueInvocation,
        sink: ValueInvocation,
    ): RepresentationCase {
        val identity = ContractModelIdentity(id("encryption"), ModelVersion.parse(1).value(), id("review:913"))
        val vocabulary =
            RepresentationDomain.admit(identity, listOf(id("HIPED"), id("PLAINTEXT"), id("VOLTAGE"))).value()
        val hiped = vocabulary.state(id("HIPED")).value()
        val plaintext = vocabulary.state(id("PLAINTEXT")).value()
        val voltage = vocabulary.state(id("VOLTAGE")).value()
        fun rule(name: String) = ModelRuleReference(identity, id(name))
        val origin =
            RepresentationRule.Origin.admit(
                    rule("origin"),
                    f.bind(f.producer.invocation.callable, ModelValuePosition.Result),
                    hiped,
                )
                .value()
        val argument = ModelValuePosition.Argument(ValueArgumentPosition.parse(0).value())
        val transformation =
            RepresentationRule.Transformation.admit(
                    rule("decrypt"),
                    f.bind(decrypt.callable, argument),
                    f.bind(decrypt.callable, ModelValuePosition.Result),
                    hiped,
                    plaintext,
                )
                .value()
        val expectation =
            RepresentationRule.ConsumerExpectation.admit(rule("sink"), f.bind(sink.callable, argument), voltage).value()
        return RepresentationCase(listOf(origin, transformation, expectation), plaintext, expectation)
    }

    private fun id(value: String) = ModelIdentifier.parse(value).value()
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
