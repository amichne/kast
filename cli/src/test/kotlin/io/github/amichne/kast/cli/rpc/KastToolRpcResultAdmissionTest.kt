package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.LiveReadOutputSchemaTest
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.directToolDocument
import io.github.amichne.kast.cli.installedHostedBootstrap
import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceEntityCountDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastToolRpcResultAdmissionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `RPC rejects malformed semantic results and success status mismatches`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val malformed =
            CanonicalJsonDocument.generated(InvalidSemanticResult.serializer()).create(InvalidSemanticResult())
        for (exit in
            listOf(
                CliExit.Complete(malformed),
                CliExit.Qualified(malformed),
                CliExit.Complete(sourceRejectionDocument()),
                CliExit.Complete(qualifiedSourceDocument()),
                CliExit.OperationRejected(completeSourceDocument()),
                CliExit.Qualified(completeSourceDocument()),
            )) {
            val session =
                KastDirectToolSession(
                    catalog =
                        listOf(
                            installedHostedBootstrap().tools.single { it.name == "query_symbols" }.directToolDocument()
                        ),
                    root = { FilesystemCanonicalRootDiscovery.discover(temporary) },
                    start = { Refinement.Refined(Unit) },
                    invokePublic = { exit },
                )
            assertEquals(
                ToolRpcReply.Rejected(ToolRpcFailure.INVALID_RESULT),
                KastToolRpcBridge(session)
                    .call("query_symbols", publicExample(PublicToolIdentity.QUERY_SYMBOLS, "runByName")),
            )
        }
    }

    @Test
    fun `RPC retains schema admitted qualified continuation and every rejection family`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val qualified = qualifiedSourceDocument()
        val rejection = sourceRejectionDocument()
        val endpoint = CanonicalJsonDocument.generated(TestHostRejected.serializer()).create(TestHostRejected())
        val preauthority =
            CanonicalJsonDocument.generated(TestPreauthorityRejected.serializer()).create(TestPreauthorityRejected())
        val exits =
            listOf(
                CliExit.Qualified(qualified),
                CliExit.OperationRejected(rejection),
                CliExit.OperationRejected(endpoint),
                CliExit.OperationRejected(preauthority),
            )
        for (exit in exits) {
            val session =
                KastDirectToolSession(
                    catalog =
                        listOf(
                            installedHostedBootstrap().tools.single { it.name == "query_symbols" }.directToolDocument()
                        ),
                    root = { FilesystemCanonicalRootDiscovery.discover(temporary) },
                    start = { Refinement.Refined(Unit) },
                    invokePublic = { exit },
                )
            val result =
                KastToolRpcBridge(session)
                    .call("query_symbols", publicExample(PublicToolIdentity.QUERY_SYMBOLS, "runByName"))
            val expected = Json.parseToJsonElement(exit.document.value).jsonObject
            when (exit) {
                is CliExit.Qualified -> assertEquals(ToolRpcReply.Qualified(expected), result)
                is CliExit.OperationRejected -> assertEquals(ToolRpcReply.RejectedDocument(expected), result)
                is CliExit.Complete,
                is CliExit.BoundaryRejected,
                is CliExit.Delegated -> error("Fixture only contains qualified or rejected outcomes")
            }
        }
        val document = Json.parseToJsonElement(qualified.value).jsonObject
        assertEquals(
            "source-read-continuation-v1|" + "a".repeat(64),
            document
                .getValue("qualification")
                .jsonObject
                .getValue("continuation")
                .jsonObject
                .getValue("continuation")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `RPC input rejects invalid UTF-8 rather than replacing reference bytes`() {
        val invalid = byteArrayOf(0x80.toByte())
        assertEquals(Refinement.Rejected(ToolRpcFailure.INVALID_ARGUMENTS), decodeToolRpcInput(invalid))
        assertEquals(Refinement.Refined("valid identity"), decodeToolRpcInput("valid identity".encodeToByteArray()))
    }

    private fun completeSourceDocument(): CanonicalJsonDocument {
        val fixture = LiveReadOutputSchemaTest()
        val basis = EvidenceBasis.Published((EvidenceGeneration.parse(1) as Refinement.Refined).value)
        return (CanonicalSourceReadCliDocuments.project(
                OperationOutcome.Complete(
                    EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, fixture.sourceResult(basis))
                )
            ) as ProjectedOperationOutcome.Complete)
            .document
    }

    private fun qualifiedSourceDocument(): CanonicalJsonDocument {
        val fixture = LiveReadOutputSchemaTest()
        val basis = EvidenceBasis.Published((EvidenceGeneration.parse(1) as Refinement.Refined).value)
        val token = (ProtocolText.parse("source-read-continuation-v1|" + "a".repeat(64)) as Refinement.Refined).value
        val qualification =
            (SourceReadQualification.create(
                    (SourceEntityCountDocument.parse(0) as Refinement.Refined).value,
                    listOf(SourceReadLimitationDocument.WORK_LIMIT_REACHED),
                    SourceQualifiedProgressDocument.Resumable(
                        SourceCheckpointDocument.Upstream(token),
                        ReadResumeActionDocument.RESUME,
                    ),
                ) as Refinement.Refined)
                .value
        return (CanonicalSourceReadCliDocuments.project(
                OperationOutcome.Qualified(
                    EvidenceEnvelope(CanonicalOperation.SOURCE_READ.id, basis, fixture.sourceResult(basis)),
                    qualification,
                )
            ) as ProjectedOperationOutcome.Qualified)
            .document
    }

    private fun sourceRejectionDocument(): CanonicalJsonDocument =
        (CanonicalSourceReadCliDocuments.project(OperationOutcome.Rejected(SourceReadRejection.DOCUMENT_DIRTY))
                as ProjectedOperationOutcome.Rejected)
            .document

    private fun publicExample(identity: PublicToolIdentity, name: String): String =
        PublicToolContract.examples(identity).examples.getValue(name).value.toString()
}

// Deliberately missing required result facts to prove rejection.
@Serializable
private data class InvalidSemanticResult(val status: InvalidSemanticStatus = InvalidSemanticStatus.COMPLETE)

@Serializable
private enum class InvalidSemanticStatus {
    @SerialName("complete") COMPLETE
}

// Independent admitted wire fixtures for preauthority rejections, whose production owners are platform-only.
@Serializable
private enum class TestHostRejectionType {
    HOST_REJECTED
}

@Serializable
private enum class TestHostFailure {
    DIRTY_DOCUMENTS
}

@Serializable
private enum class TestEndpointFailure {
    UNSAVED_DOCUMENTS
}

@Serializable
private enum class TestHostStage {
    EPOCH_OBSERVATION
}

@Serializable
private enum class TestHostOutcome {
    @SerialName("rejected") REJECTED
}

@Serializable
private data class TestHostRejected(
    val type: TestHostRejectionType = TestHostRejectionType.HOST_REJECTED,
    val failure: TestEndpointFailure = TestEndpointFailure.UNSAVED_DOCUMENTS,
)

@Serializable
private data class TestPreauthorityRejected(
    val schemaVersion: Int = 1,
    val outcome: TestHostOutcome = TestHostOutcome.REJECTED,
    val failure: TestHostFailure = TestHostFailure.DIRTY_DOCUMENTS,
    val detail: String = "saved content required",
    val stage: TestHostStage = TestHostStage.EPOCH_OBSERVATION,
)
