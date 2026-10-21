package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactBoundaryCompatibilityDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryContractDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryKindDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactBoundaryTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactModelDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentifierDocument
import io.github.amichne.kast.protocol.contract.ImpactModelIdentityDocument
import io.github.amichne.kast.protocol.contract.ImpactModelVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedBoundaryRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedModelDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactRequestedValueSiteDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryImpactFlowDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PublicImpactModelInputTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `public boundary models preserve metadata and lower all input roles with server owned state`() {
        val invocation = ImpactRequestedInvocationDocument(range(5, 25), declarationReference())
        val roles =
            listOf(
                ImpactRequestedRoleDocument.ExpressionResult to ImpactValueRoleDocument.ExpressionResult,
                ImpactRequestedRoleDocument.LocalBinding to ImpactValueRoleDocument.LocalBinding,
                ImpactRequestedRoleDocument.LocalRead to ImpactValueRoleDocument.LocalRead,
                ImpactRequestedRoleDocument.Return to ImpactValueRoleDocument.Return,
                ImpactRequestedRoleDocument.PropertyAssignment to ImpactValueRoleDocument.PropertyAssignment,
                ImpactRequestedRoleDocument.Argument(invocation, ProtocolOffset.parse(0).refined()) to
                    ImpactValueRoleDocument.Argument(
                        ImpactInvocationReferenceDocument(
                            invocation.range,
                            invocation.callable,
                            ImpactExecutionOwnerDocument.Declaration,
                        ),
                        ProtocolOffset.parse(0).refined(),
                    ),
            )
        for ((role, expectedRole) in roles) {
            val model = boundaryModel(role)
            val request = boundaryRequest(model)
            val raw = json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), request)
            val admitted = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, raw).refined()
            val run = (admitted.canonical as PublicToolCanonical.Query).request as QueryRunRequest.Run
            assertEquals(
                listOf(expectedBoundaryModel(expectedRole)),
                (run.from as QueryFromDocument.Impact).investigation.models.values,
                role.toString(),
            )
            assertEquals(raw, PublicToolContract.encode(admitted))
        }
    }

    @Test
    fun `public model decoder and schema reject retained state at every boundary site`() {
        val invocation = ImpactRequestedInvocationDocument(range(5, 25), declarationReference())
        val role = ImpactRequestedRoleDocument.Argument(invocation, ProtocolOffset.parse(0).refined())
        val model = boundaryModel(role)
        val raw = json.encodeToString(PublicToolQuerySymbols.serializer(), boundaryRequest(model))
        val rules = model.rules.values
        val continuation = rules[0] as ImpactRequestedBoundaryRuleDocument.Continuation
        val terminal = rules[1] as ImpactRequestedBoundaryRuleDocument.Terminal
        val encodedInvocation = json.encodeToString(ImpactRequestedInvocationDocument.serializer(), invocation)
        for (position in listOf(continuation.source, continuation.target, terminal.source)) {
            val site = json.encodeToString(ImpactRequestedValueSiteDocument.serializer(), position.site)
            val malformedSites =
                listOf(
                    site.dropLast(1) + ",\"executionOwner\":{\"type\":\"DECLARATION\"}}",
                    site.dropLast(1) + ",\"valuePath\":[]}",
                    site.dropLast(1) + ",\"callStack\":[]}",
                    site.dropLast(1) + ",\"extra\":true}",
                    site.replace("\"type\":\"ARGUMENT\"", "\"type\":\"RECEIVER\""),
                    site.replace("\"type\":\"ARGUMENT\"", "\"type\":\"FORMAL\""),
                    site.replaceFirst(
                        encodedInvocation,
                        encodedInvocation.dropLast(1) + ",\"executionOwner\":{\"type\":\"DECLARATION\"}}",
                    ),
                )
            for (malformedSite in malformedSites) {
                val malformed = raw.replaceFirst(site, malformedSite)
                assertNotEquals(raw, malformed)
                assertThrows(SerializationException::class.java) {
                    json.decodeFromString(PublicToolQuerySymbols.serializer(), malformed)
                }
                assertEquals(
                    Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
                    PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(malformed)),
                )
            }
        }
    }

    private fun expectedBoundaryModel(expectedRole: ImpactValueRoleDocument): ImpactModelDocument.Boundary {
        val expectedSource =
            ImpactBoundaryPositionDocument(
                ImpactValueSiteReferenceDocument(
                    enclosing = declarationReference(),
                    range = range(10, 11),
                    role = expectedRole,
                    executionOwner = ImpactExecutionOwnerDocument.Declaration,
                    valuePath = bounded(emptyList()),
                    callStack = bounded(emptyList()),
                ),
                ImpactBoundaryKindDocument.PERSISTENCE,
                ImpactBoundaryContractDocument(id("cache-schema"), ImpactModelVersionDocument.parse(2).refined()),
                id("writer"),
            )
        val expectedTarget =
            expectedSource.copy(
                site = expectedSource.site.copy(range = range(12, 13)),
                slot = id("reader"),
            )
        val expectedTerminal =
            expectedSource.copy(
                site = expectedSource.site.copy(range = range(14, 15)),
                slot = id("sink"),
            )
        return ImpactModelDocument.Boundary(
            ImpactModelFormatDocument.Current,
            ImpactModelIdentityDocument(
                id("cache"),
                ImpactModelVersionDocument.parse(3).refined(),
                id("review:914"),
            ),
            bounded(
                listOf(
                    ImpactBoundaryRuleDocument.Continuation(
                        id("reader-writer"),
                        expectedSource,
                        expectedTarget,
                        bounded(ImpactBoundaryCompatibilityDocument.entries),
                    ),
                    ImpactBoundaryRuleDocument.Terminal(
                        id("retention"),
                        expectedTerminal,
                        ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
                    ),
                )
            ),
        )
    }

    private fun boundaryRequest(model: ImpactRequestedModelDocument) =
        PublicToolQuerySymbols(
            PublicToolRunAction(
                PublicToolImpactSource(
                    producerSeeds(),
                    bounded(emptyList()),
                    bounded(listOf(model)),
                    PublicToolImpactWorkspaceDomain,
                    QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                ),
                output = PublicToolValuePathsOutput,
            )
        )

    private fun boundaryModel(role: ImpactRequestedRoleDocument): ImpactRequestedModelDocument.Boundary {
        val source =
            ImpactRequestedBoundaryPositionDocument(
                ImpactRequestedValueSiteDocument(declarationReference(), range(10, 11), role),
                ImpactBoundaryKindDocument.PERSISTENCE,
                ImpactBoundaryContractDocument(id("cache-schema"), ImpactModelVersionDocument.parse(2).refined()),
                id("writer"),
            )
        return ImpactRequestedModelDocument.Boundary(
            ImpactModelFormatDocument.Current,
            ImpactModelIdentityDocument(id("cache"), ImpactModelVersionDocument.parse(3).refined(), id("review:914")),
            bounded(
                listOf(
                    ImpactRequestedBoundaryRuleDocument.Continuation(
                        id("reader-writer"),
                        source,
                        source.copy(site = source.site.copy(range = range(12, 13)), slot = id("reader")),
                        bounded(ImpactBoundaryCompatibilityDocument.entries),
                    ),
                    ImpactRequestedBoundaryRuleDocument.Terminal(
                        id("retention"),
                        source.copy(site = source.site.copy(range = range(14, 15)), slot = id("sink")),
                        ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
                    ),
                )
            ),
        )
    }

    private fun declarationReference() =
        ImpactDeclarationReferenceDocument(
            basis =
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).refined(),
                ),
            file = text("/workspace/File.kt"),
            range = range(0, 200),
            compilerIdentity = text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )

    private fun producerSeeds() =
        bounded(
            listOf(
                QueryImpactProducerDocument(
                    text("NON_ISSUED_ENCLOSING_TEST_ONLY"),
                    text("NON_ISSUED_CALLABLE_TEST_ONLY"),
                    range(20, 35),
                )
            )
        )

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).refined(), ProtocolOffset.parse(end).refined())

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun id(value: String) = ImpactModelIdentifierDocument.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
