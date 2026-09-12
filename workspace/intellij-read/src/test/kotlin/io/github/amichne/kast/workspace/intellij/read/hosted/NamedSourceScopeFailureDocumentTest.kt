package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.BoundedModuleName
import io.github.amichne.kast.workspace.intellij.read.BoundedSourceRootIdentity
import io.github.amichne.kast.workspace.intellij.read.CodeSourceRootClassification
import io.github.amichne.kast.workspace.intellij.read.GradleModuleOwnershipFailure
import io.github.amichne.kast.workspace.intellij.read.IdeRootMappingFailure
import io.github.amichne.kast.workspace.intellij.read.IdeSourceRootEvidence
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamedSourceScopeFailureDocumentTest {
    private val evidence =
        IdeSourceRootEvidence(
            BoundedModuleName.observe("app.main"),
            BoundedSourceRootIdentity.observe("file:///workspace/app/src/main/kotlin"),
        )

    @Test
    fun `each mapping failure retains independent expected wire shape`() {
        val cases =
            listOf(
                "unavailable" to IdeRootMappingFailure.SourceFolderUnavailable(evidence),
                "observation-failed" to IdeRootMappingFailure.SourceFolderObservationFailed(evidence),
                "unmapped" to IdeRootMappingFailure.GradleOwnerMissing(evidence),
                "unsupported" to IdeRootMappingFailure.UnsupportedRootType(evidence),
                "properties-unavailable" to IdeRootMappingFailure.SourcePropertiesUnavailable(evidence),
                "classification-mismatch" to
                    IdeRootMappingFailure.RootClassificationMismatch(
                        evidence,
                        CodeSourceRootClassification(
                            WorkspaceSourceRootKind.TEST,
                            WorkspaceSourceRootProvenance.GENERATED,
                        ),
                        CodeSourceRootClassification(
                            WorkspaceSourceRootKind.PRODUCTION,
                            WorkspaceSourceRootProvenance.AUTHORED,
                        ),
                    ),
            )
        for ((name, cause) in cases) assertDocument(name, NamedGradleSourceScopeFailure.RootMapping(cause))
        assertDocument(
            "owner-unavailable",
            NamedGradleSourceScopeFailure.ModuleOwnership(
                evidence.module,
                GradleModuleOwnershipFailure.BUILD_ROOT_UNAVAILABLE,
            ),
        )
    }

    @Test
    fun `unknown ownership and mapping reason values are rejected`() {
        for (name in listOf("unavailable", "owner-unavailable")) {
            val expected = requireNotNull(javaClass.getResource("/named-source-scope/$name.json")).readText()
            val detail = Json.parseToJsonElement(expected).jsonObject.getValue("detail").toString()
            val known = if (name == "unavailable") "SOURCE_FOLDER_UNAVAILABLE" else "BUILD_ROOT_UNAVAILABLE"
            assertThrows(SerializationException::class.java) {
                hostedFailureJson.decodeFromString<NamedSourceScopeFailureDocument>(
                    detail.replace(known, "UNKNOWN_REASON")
                )
            }
        }
    }

    @Test
    fun `unknown root classification is rejected by the typed document`() {
        val expected =
            requireNotNull(javaClass.getResource("/named-source-scope/classification-mismatch.json")).readText()
        val detail = Json.parseToJsonElement(expected).jsonObject.getValue("detail").toString()
        for (known in listOf("TEST", "GENERATED")) {
            assertThrows(SerializationException::class.java) {
                hostedFailureJson.decodeFromString<NamedSourceScopeFailureDocument>(
                    detail.replace(known, "UNSUPPORTED_CLASSIFICATION")
                )
            }
        }
    }

    @Test
    fun `oversized diagnostic identities retain explicit bounded prefixes`() {
        val module = BoundedModuleName.observe("m".repeat(1024))
        val root = BoundedSourceRootIdentity.observe("r".repeat(16384))
        assertEquals(512, module.value.length)
        assertEquals(8192, root.value.length)
        assertTrue(module.truncated)
        assertTrue(root.truncated)
    }

    private fun assertDocument(name: String, cause: NamedGradleSourceScopeFailure) {
        val expected =
            Json.parseToJsonElement(requireNotNull(javaClass.getResource("/named-source-scope/$name.json")).readText())
        val failure = HostedQueryFailure.NamedSourceScope(cause)
        val actual =
            Json.parseToJsonElement(
                HostedQueryWire.encode(HostedQueryResult.Rejected(failure, HostedQueryStage.MODEL_CAPTURE))
            )
        assertEquals(expected, actual, name)
        val diagnostic = HostedDiagnosticOutcomeDocument.Rejected(failure.code(), failure.detail())
        val encoded = Json.parseToJsonElement(Json.encodeToString(diagnostic)).jsonObject.getValue("detail")
        assertEquals(
            expected.jsonObject.getValue("detail"),
            encoded,
            "Diagnostic receipt must preserve the same evidence",
        )
    }
}
