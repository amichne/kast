package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactTryBranchAlternativeDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The frozen three-module fixture traverses native proof, query scheduling, projection and retention. */
class NativeDecoderValueQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testSuccessfulDecodeResultReachesNestedReturnWithExactFieldAndInvocations() = runBlocking {
        val services =
            NativeDecoderCallbackFixture(project, getOrCreateProjectBaseDir(), module)
                .prepare(::registerTestProjectJdk)
                .valueServices()
        val nested = declaration(services, DecoderFixtureModule.USE, "nested(")
        val decode = declaration(services, DecoderFixtureModule.CONTRACT, "decode(")
        val useSource = DecoderFixtureModule.USE.source()
        val start = useSource.indexOf("decoder.decode(value)", useSource.indexOf("fun nested"))
        val producer =
            QueryImpactProducerDocument(
                nested.ref.token,
                decode.ref.token,
                ImpactSourceRangeDocument(offset(start), offset(start + "decoder.decode(value)".length)),
            )
        val paths =
            NativePacketQueryCapture(services)
                .paths(
                    producer = producer,
                    targets = emptyList(),
                    outgoing = false,
                    declarations = emptyList(),
                    domain = DecoderCallbackRequests.useScope.copy(directory = null),
                )
        val encoded = Json.encodeToString(paths)
        println("Decoder result query paths=$encoded")
        val delivered = Json.decodeFromString<List<ImpactPathDocument>>(encoded)
        assertEquals(paths, delivered)
        assertTrue(delivered.single().terminal is ImpactPathTerminalDocument.SupportedDomainEnd)
        val route = delivered.single().steps.values.map { (it as ImpactPathStepDocument.Compiler).transfer }
        assertSuccessfulRoute(route, useSource)
    }

    private fun assertSuccessfulRoute(route: List<ImpactCompilerTransferDocument>, useSource: String) {
        assertTrue(route.all { it.admitsEvidence() })
        assertEquals(
            2,
            route.count { transfer ->
                val operation = (transfer.evidence as? ImpactTransferEvidenceDocument.Source)?.operation
                operation is ImpactSourceOperationDocument.CallbackReturn
            },
        )
        val construction = route.single { it.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD }
        val field = construction.target.valuePath.values.single() as ImpactValuePathSegmentDocument.Field
        val flowSource = DecoderFixtureModule.FLOW.source()
        assertEquals(flowSource.indexOf("val value: T"), field.declaration.range.start.value)
        assertTrue(field.declaration.file.value.endsWith(DecoderFixtureModule.FLOW.relativePath))
        val branch = route.single { it.kind == ImpactTransferKindDocument.BRANCH_ALTERNATIVE }
        val evidence = branch.evidence as ImpactTransferEvidenceDocument.NormalBranchResult
        assertEquals(ImpactTryBranchAlternativeDocument.TryBody, evidence.alternative)
        val last = route.last().target
        assertEquals(ImpactValueRoleDocument.Return, last.role)
        assertEquals(construction.target.valuePath, last.valuePath)
        assertTrue(last.callStack.values.isEmpty())
        assertTrue(last.enclosing.file.value.endsWith(DecoderFixtureModule.USE.relativePath))
        assertEquals(useSource.indexOf("fun nested"), last.enclosing.range.start.value)
        assertTrue(
            route.none { transfer ->
                transfer.source.range.start.value == flowSource.indexOf("Attempt.Failure(cause)") &&
                    transfer.source.enclosing.file.value.endsWith(DecoderFixtureModule.FLOW.relativePath)
            }
        )
    }

    private suspend fun declaration(
        services: NativePacketQueryServices,
        unit: DecoderFixtureModule,
        anchor: String,
    ): QueryResultItemDocument.ExactSymbol {
        val request =
            QueryRunRequest.Run(
                from =
                    QueryFromDocument.Location(
                        packetQueryText(unit.relativePath),
                        offset(unit.source().indexOf(anchor)),
                    ),
                steps = packetQueryList(emptyList()),
                output = DecoderCallbackRequests.output,
                execution =
                    QueryExecutionDocument(
                        QueryExecutionKindDocument.EXHAUSTIVE,
                        QueryExecutionBudgetDocument.INTERACTIVE,
                    ),
            )
        val result =
            services.protocol.execute(
                request = request,
                lease = services.lease,
                budget = NativePacketQueryServices.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        check(result is OperationOutcome.Complete) { "Decoder declaration prerequisite failed: $result" }
        return result.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
    }

    private fun offset(value: Int) = ProtocolOffset.parse(value).packetQueryRefined()
}
