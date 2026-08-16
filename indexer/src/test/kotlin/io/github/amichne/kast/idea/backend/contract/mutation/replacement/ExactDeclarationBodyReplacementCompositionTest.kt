package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.ReplacementPlanQuery
import io.github.amichne.kast.api.contract.result.ExactFileImageResult
import io.github.amichne.kast.api.contract.result.ExactFileImageStatus
import io.github.amichne.kast.api.contract.result.MutationPostconditionOperation
import io.github.amichne.kast.api.contract.result.MutationPostconditionResult
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.protocol.JsonRpcSuccessResponse
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RpcAnalysisDispatcher
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class ExactDeclarationBodyReplacementCompositionTest {
    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )
    }

    private val rpcJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    private val projectFixture: TestFixture<Project> = projectFixture(openAfterCreation = true)
    private val mainModuleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val mainSourceRootFixture: TestFixture<PsiDirectory> = mainModuleFixture.sourceRootFixture()
    private val bodyFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ExactDeclarationBodyReplacementRpc.kt",
        """
            package demo.rpc

            fun transform(value: String): String {
                return value
            }
        """.trimIndent(),
    )
    private val contextFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ExactDeclarationBodyContextRpc.kt",
        """
            package demo.rpc

            fun unchangedRpcContext(): String = "context"
        """.trimIndent(),
    )
    private val project: Project
        get() = projectFixture.get()

    @Test
    fun `public JSON RPC composes real replacement planner CAS and verified postcondition`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val source = readAction { file.text }
        val selectedNameOffset = source.indexOf("transform(value: String)")
        val selectedBodyStart = source.indexOf("{", selectedNameOffset)
        val selectedBodyEnd = source.indexOf("}", selectedBodyStart) + 1
        val proposedBody = "{ return \"through rpc\" }"
        val proposed = "fun transform(value: String): String $proposedBody"
        val expectedPostimage = source.replaceRange(selectedBodyStart, selectedBodyEnd, proposedBody)
        val query = ReplacementPlanQuery(
            target = SymbolIdentity(
                fqName = "demo.rpc.transform",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse(file.virtualFile.path),
                declarationStartOffset = NonNegativeInt(selectedNameOffset),
            ),
            proposedDeclaration = proposed,
        )
        val dispatcher = RpcAnalysisDispatcher(
            backend = backend(workspaceRoot(file)),
            config = AnalysisServerConfig(),
        )

        try {
            val params = rpcJson.encodeToJsonElement(ReplacementPlanQuery.serializer(), query)
            val plan = dispatchPublicSuccess(
                dispatcher = dispatcher,
                method = "raw/plan-replacement",
                params = params,
                serializer = ReplacementPlanResult.serializer(),
            )
            assertEquals(selectedBodyStart, plan.edit.startOffset)
            assertEquals(selectedBodyEnd, plan.edit.endOffset)
            assertEquals(proposedBody, plan.edit.newText)
            assertEquals(
                proposedBody,
                proposed.substring(
                    plan.proof.proposedBodySlice.startOffset.value,
                    plan.proof.proposedBodySlice.endOffset.value,
                ),
            )

            val revalidated = dispatchPublicSuccess(
                dispatcher = dispatcher,
                method = "raw/plan-replacement",
                params = params,
                serializer = ReplacementPlanResult.serializer(),
            )
            assertEquals(plan, revalidated, "public revalidation must reproduce the exact typed authority")

            val image = plan.fileImages.single()
            val cas = dispatchPublicSuccess(
                dispatcher = dispatcher,
                method = "raw/exact-file-image-cas",
                params = rpcJson.encodeToJsonElement(
                    ExactFileImageQuery.serializer(),
                    ExactFileImageQuery(
                        filePath = image.filePath,
                        expectedCurrentSha256 = image.preimage.sha256,
                        contentBase64 = image.postimage.contentBase64,
                        expectedResultSha256 = image.postimage.sha256,
                    ),
                ),
                serializer = ExactFileImageResult.serializer(),
            )
            assertEquals(ExactFileImageStatus.COMMITTED, cas.status)
            assertEquals(image.postimage.sha256, cas.resultSha256)
            waitUntilIndexesAreReady(project)

            val receipt = dispatchPublicSuccess(
                dispatcher = dispatcher,
                method = "raw/verify-mutation-postcondition",
                params = rpcJson.encodeToJsonElement(
                    MutationPostconditionQuery.serializer(),
                    MutationPostconditionQuery(
                        MutationPostconditionAuthority.Replacement(plan.proof, plan.edit, plan.fileImages),
                    ),
                ),
                serializer = MutationPostconditionResult.serializer(),
            )
            assertEquals(MutationPostconditionStatus.VERIFIED, receipt.status)
            assertEquals(MutationPostconditionOperation.REPLACEMENT, receipt.operation)
            assertEquals(image.postimage.sha256, receipt.postimages.single().sha256)
            assertEquals(expectedPostimage, readAction { file.text })
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `G1 rejects admitted compiler-source byte drift without VFS refresh authority`() = runBlocking {
        ensureProjectReady()
        val file = bodyFixture.get()
        val context = contextFixture.get()
        val backend = backend(workspaceRoot(file))
        val plan = selectedPlan(file, backend)
        assertTrue(readAction { ProjectFileIndex.getInstance(project).isInSourceContent(context.virtualFile) })
        assertEquals(
            setOf(context.virtualFile.path),
            plan.proof.compilerContext.files.map { it.filePath.value }.toSet(),
            "compiler context must be exactly the admitted non-target ProjectFileIndex source set",
        )
        assertEquals(
            readAction { ProjectRootModificationTracker.getInstance(project).modificationCount },
            plan.proof.compilerContext.modelGeneration.value,
        )
        val undiscoveredPath = Path.of(context.virtualFile.path).resolveSibling("Undiscovered.kt")
        assertTrue(Files.notExists(undiscoveredPath))
        assertTrue(plan.proof.compilerContext.files.none { it.filePath.value == undiscoveredPath.toString() })

        applyPlan(backend, plan)
        val contextPath = Path.of(context.virtualFile.path)
        Files.writeString(contextPath, Files.readString(contextPath).replace("context", "changed"))

        val failure = assertThrows(MutationPostconditionFailedException::class.java) {
            runBlocking {
                backend.verifyMutationPostcondition(
                    MutationPostconditionQuery(
                        MutationPostconditionAuthority.Replacement(plan.proof, plan.edit, plan.fileImages),
                    ).parsed(),
                )
            }
        }
        assertEquals(listOf(MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED), failure.limitations)
    }

    private suspend fun <T> dispatchPublicSuccess(
        dispatcher: RpcAnalysisDispatcher,
        method: String,
        params: JsonElement,
        serializer: KSerializer<T>,
    ): T {
        val raw = dispatcher.dispatch(
            JsonRpcRequest(
                id = JsonPrimitive(1),
                method = method,
                params = params,
            ),
        )
        val response = rpcJson.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        return rpcJson.decodeFromJsonElement(serializer, response.result)
    }

    private suspend fun selectedPlan(
        file: PsiFile,
        backend: KastIndexerBackend,
    ): ReplacementPlanResult {
        val declarationOffset = readAction { file.text.indexOf("transform(value: String)") }
        return backend.planReplacement(
            ReplacementPlanQuery(
                target = SymbolIdentity(
                    fqName = "demo.rpc.transform",
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(file.virtualFile.path),
                    declarationStartOffset = NonNegativeInt(declarationOffset),
                ),
                proposedDeclaration = "fun transform(value: String): String { return \"changed\" }",
            ),
        )
    }

    private suspend fun applyPlan(
        backend: KastIndexerBackend,
        plan: ReplacementPlanResult,
    ) {
        val image = plan.fileImages.single()
        backend.exactFileImageCas(
            ExactFileImageQuery(
                filePath = image.filePath,
                expectedCurrentSha256 = image.preimage.sha256,
                contentBase64 = image.postimage.contentBase64,
                expectedResultSha256 = image.postimage.sha256,
            ),
        )
        waitUntilIndexesAreReady(project)
    }

    private fun backend(workspaceRoot: Path): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        semanticAdmissionAwaiter = IdeaSemanticAdmissionAwaiter.forRequestBudget(
            defaultLimits.requestTimeoutMillis,
        ),
        semanticAdmissionOperations = IdeaSemanticAdmissionOperations.idea(),
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
        workspaceModelReader = { IdeaGradleProjectLoadBridge.readWorkspaceModel(project) },
    )

    private fun ensureProjectReady() {
        mainModuleFixture.get()
        mainSourceRootFixture.get()
        bodyFixture.get()
        contextFixture.get()
        waitUntilIndexesAreReady(project)
    }

    private fun workspaceRoot(file: PsiFile): Path =
        commonWorkspaceRoot(file.virtualFile.path, contextFixture.get().virtualFile.path)

    private fun commonWorkspaceRoot(
        first: String,
        second: String,
    ): Path {
        val firstPath = Path.of(first).toAbsolutePath().normalize()
        val secondPath = Path.of(second).toAbsolutePath().normalize()
        return generateSequence(firstPath.parent) { it.parent }
            .first { candidate -> secondPath.startsWith(candidate) }
    }
}
