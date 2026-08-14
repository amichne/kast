package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.selector.DigestSelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.selectorOperationFamilies
import io.github.amichne.kast.api.contract.selector.toExactSelector
import io.github.amichne.kast.api.contract.skill.KastDiscoverRequest
import io.github.amichne.kast.api.contract.skill.KastDiscoverResponse
import io.github.amichne.kast.api.contract.skill.KastDiscoverSuccessResponse
import io.github.amichne.kast.api.contract.skill.KastNativeReadCompleteness
import io.github.amichne.kast.api.contract.skill.KastNativeReadQualification
import io.github.amichne.kast.api.contract.skill.KastNativeReadStage
import io.github.amichne.kast.api.contract.skill.KastNativeReadStages
import io.github.amichne.kast.api.contract.skill.KastNativeReadWork
import io.github.amichne.kast.api.contract.skill.KastReadEvidence
import io.github.amichne.kast.api.contract.skill.KastReadStageObservation
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.testing.FakeAnalysisBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeSymbolPageReviewRegressionTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `native result-limit evidence marks a full visible page as truncated`() {
        val symbol = lookupSymbol("sample.ReviewPage", SymbolKind.CLASS, "ReviewPage.kt")
        val selectorHandles = DigestSelectorHandleAuthority(
            workspaceRoot = tempDir.toAbsolutePath().normalize().toString(),
            backendName = "native-intellij",
            backendVersion = "test",
            backendInstanceId = "native-page-review-regression",
            semanticGeneration = { 43L },
        )
        val binding = PublicSymbolReadBinding.Native(
            workspaceRoot = NormalizedPath.ofAbsolute(tempDir),
            selectorHandles = selectorHandles,
            reader = {
                val issued = selectorHandles.issue(
                    symbol.toExactSelector(),
                    symbol.kind.selectorOperationFamilies(),
                ) as SelectorHandleAuthority.IssueResult.Issued
                NativePublicSymbolReadResult.Completed(
                    definitions = listOf(
                        NativePublicSymbolReadResult.Definition(symbol, issued.handle),
                    ),
                    evidence = resultLimitedEvidence(),
                )
            },
        )
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(maxResults = 1),
            publicSymbolReads = binding,
        )

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "symbol/discover",
                    params = json.encodeToJsonElement(
                        KastDiscoverRequest.serializer(),
                        KastDiscoverRequest(symbol = "ReviewPage", maxResults = 1),
                    ),
                ),
            )
        }
        val envelope = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val response = json.decodeFromJsonElement(
            KastDiscoverResponse.serializer(),
            envelope.result,
        ) as KastDiscoverSuccessResponse

        assertNotNull(response.page)
        assertTrue(checkNotNull(response.page).truncated)
    }

    private fun resultLimitedEvidence() = KastReadEvidence.NativeIntellij(
        generation = 43L,
        completeness = KastNativeReadCompleteness.QUALIFIED,
        qualifications = setOf(KastNativeReadQualification.RESULT_LIMIT_REACHED),
        stages = KastNativeReadStages(
            KastNativeReadStage.entries.associateWith { stage ->
                if (stage == KastNativeReadStage.IPC) {
                    KastReadStageObservation.OutsideResponseBoundary
                } else {
                    KastReadStageObservation.Measured(1L)
                }
            },
        ),
        work = KastNativeReadWork(
            vfsRefreshCount = 0L,
            gradleImportCount = 0L,
            graphBuildCount = 0L,
            sqliteWriteCount = 0L,
            readActionCount = 1L,
        ),
        projectionBytes = 64L,
    )
}
