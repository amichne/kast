package io.github.amichne.kast.workspace.intellij.read.hosted

import com.google.gson.JsonParser
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.intellij.read.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedQueryContractTest {
    @Test
    fun `published wire preserves both compiler identities and request-local scope evidence`() {
        val model =
            (captureDetachedFixture(DetachedModelObservation.Observed(detachedModelBoundary()))
                    as DetachedModelCapture.Captured)
                .model
        val selection =
            (HostedKotlinSelection.parse(FIXTURE_ROOT, "module0/src/main/kotlin/Child.kt", 0) as Refinement.Refined)
                .value
        val scope = (HostedSourceScope.admit(model, selection.file) as Refinement.Refined).value
        fun declaration(name: String): HostedCompilerDeclaration {
            val qualified = "example.$name"
            val signature = (CanonicalCompilerSignature.classLike(qualified) as Refinement.Refined).value
            val evidence =
                (CompilerGroundedSymbolEvidence.fromBoundary(
                        selection.file,
                        0,
                        50,
                        name,
                        qualified,
                        CompilerSymbolKind.CLASSLIKE,
                        signature,
                    ) as Refinement.Refined)
                    .value
            val content = (HostedContentRevision.observe(12, 34) as Refinement.Refined).value
            return HostedCompilerDeclaration(evidence, content, scope.module, scope.root)
        }
        val relation = HostedInheritorEvidence(declaration("Parent"), declaration("Child"))
        val publication =
            HostedQueryPublication(
                HostedQueryEndpoint(),
                RecordingFreshnessEpochSource().observeEpoch(),
                model,
                relation,
            )
        val json = JsonParser.parseString(HostedQueryWire.encode(HostedQueryResult.Published(publication))).asJsonObject
        assertEquals("request_local_same_source_epoch", json["publication"].asString)
        assertEquals("saved_committed_ide_vfs", json["content"].asString)
        assertEquals(FIXTURE_ROOT.value, json["workspaceRoot"].asString)
        for ((field, declaration) in listOf("supertype" to relation.supertype, "inheritor" to relation.inheritor)) {
            val endpoint = json[field].asJsonObject
            assertEquals(declaration.symbol.compilerIdentity.value, endpoint["compilerIdentity"].asString)
            assertEquals(
                declaration.symbol.signature.canonicalEncoding().value,
                endpoint["canonicalSignature"].asString,
            )
            assertEquals("cached_source_folder_flag", endpoint["provenanceAuthority"].asString)
            assertEquals(12, endpoint["documentStamp"].asInt)
            assertEquals(34, endpoint["vfsStamp"].asInt)
        }
    }

    @Test
    fun `selection preserves the exact root and excludes external or malformed paths`() {
        val good =
            HostedKotlinSelection.parse(FIXTURE_ROOT, "module0/src/main/kotlin/Child.kt", 12) as Refinement.Refined
        assertEquals(FIXTURE_ROOT, good.value.root)
        assertEquals("/workspace/kast/module0/src/main/kotlin/Child.kt", good.value.file.stableValue)
        for (path in
            listOf(
                "../Child.kt",
                "/outside/Child.kt",
                "module/../Child.kt",
                "module//Child.kt",
                "module\\Child.kt",
                "Child.java",
                "./Child.kt",
                "x".repeat(4096) + ".kt",
            )) {
            assertEquals(
                Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION),
                HostedKotlinSelection.parse(FIXTURE_ROOT, path, 0),
                path,
            )
        }
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION),
            HostedKotlinSelection.parse(FIXTURE_ROOT, "Child.kt", -1),
        )
    }

    @Test
    fun `scope requires a unique supported cached source-folder owner`() {
        val selection =
            (HostedKotlinSelection.parse(FIXTURE_ROOT, "module0/src/main/kotlin/Child.kt", 0) as Refinement.Refined)
                .value
        fun model(roots: List<DetachedSourceRootBoundary>) =
            (captureDetachedFixture(
                    DetachedModelObservation.Observed(
                        detachedModelBoundary(modules = listOf(detachedModuleBoundary(sourceRoots = roots)))
                    )
                )
                    as DetachedModelCapture.Captured)
                .model
        val root = detachedSourceRootBoundary("module0/src/main/kotlin")
        assertTrue(HostedSourceScope.admit(model(listOf(root)), selection.file) is Refinement.Refined)
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.OUTSIDE_SCOPE),
            HostedSourceScope.admit(model(listOf(detachedSourceRootBoundary("other"))), selection.file),
        )
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.AMBIGUOUS_SCOPE),
            HostedSourceScope.admit(model(listOf(root, detachedSourceRootBoundary("module0/src"))), selection.file),
        )
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_MODEL),
            HostedSourceScope.admit(
                model(listOf(root.copy(provenance = DetachedSourceRootProvenance.GENERATED))),
                selection.file,
            ),
        )
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_MODEL),
            HostedSourceScope.admit(model(listOf(root.copy(kind = DetachedSourceRootKind.RESOURCE))), selection.file),
        )
    }

    @Test
    fun `rejection wire retains the bounded failing stage and model causes`() {
        val json =
            JsonParser.parseString(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(
                            HostedQueryFailure.ModelCapture(
                                DetachedModelCapture.Rejected(DetachedModelCaptureFailure.SDK_UNAVAILABLE)
                            ),
                            HostedQueryStage.MODEL_CAPTURE,
                        )
                    )
                )
                .asJsonObject
        assertEquals("rejected", json["outcome"].asString)
        assertEquals("MODEL_CAPTURE", json["stage"].asString)
        assertEquals("SDK_UNAVAILABLE", json["detail"].asJsonArray.single().asString)
        assertFalse(json.has("publication"))
    }
}
