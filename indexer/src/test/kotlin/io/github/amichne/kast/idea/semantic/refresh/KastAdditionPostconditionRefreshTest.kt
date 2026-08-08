package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestApplication
internal class KastAdditionPostconditionRefreshTest : IdeaEditApplicationTestFixture() {
    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )
    }

    private val workspaceRoot: Path
        get() = requireNotNull(sourceRoot.parent)

    private val sourceRoot: Path
        get() = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()

    private fun additionBackend(): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
        semanticAdmissionAwaiter = IdeaSemanticAdmissionAwaiter.forRequestBudget(
            defaultLimits.requestTimeoutMillis,
        ),
        semanticAdmissionOperations = IdeaSemanticAdmissionOperations.idea(),
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
        workspaceModelReader = ::workspaceModel,
    )

    private fun workspaceModel(): IdeaGradleProjectLoadBridge.GradleWorkspaceModel {
        val identity = IdeaGradleProjectLoadBridge.GradleModuleIdentity(workspaceRoot, ":")
        val association = IdeaGradleProjectLoadBridge.GradleModuleAssociation(
            "main",
            workspaceRoot,
            workspaceRoot,
            ":",
            true,
            false,
            listOf(
                IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(
                    "main",
                    listOf(authoredGradleSourceRoot(sourceRoot)),
                ),
            ),
        )
        return IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            listOf(workspaceRoot),
            true,
            listOf(identity),
            listOf(IdeaGradleProjectLoadBridge.LoadedGradleModule("main", identity)),
            listOf(authoredGradleSourceRoot(sourceRoot)),
            listOf(association),
        )
    }

    @Test
    fun `add declaration postcondition verifier rejects changed declaration identity and context`() = runBlocking {
        ensureProjectReady()
        val target = Path.of(testFile.virtualFile.path).toAbsolutePath().normalize()
        val backend = additionBackend()
        val before = Files.readAllBytes(target)
        val authorizedName = "AuthorizedDeclaration"
        val driftedName = "DriftedDeclaration   "
        val plan = backend.planAddDeclaration(
            AddDeclarationPlanQuery(
                targetPath = AdditionTargetPath.parse(target.toString()),
                expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                proposedDeclaration = "class $authorizedName",
            ),
        )
        val driftText = plan.proposedContent.replace("class $authorizedName", "class $driftedName")
        val driftImage = ExactFileImage.of(target.toString(), before, driftText.toByteArray())
        val driftProof = ExactAddDeclarationProof.of(
            targetPath = plan.proof.targetPath,
            targetPreimageSha256 = plan.proof.targetPreimageSha256,
            owner = plan.proof.owner,
            packageIdentity = plan.proof.packageIdentity,
            declaration = plan.proof.declaration,
            insertion = plan.proof.insertion,
            newlinePolicy = plan.proof.newlinePolicy,
            context = plan.proof.context,
            collisionEvidence = plan.proof.collisionEvidence,
            outboundEvidence = plan.proof.outboundEvidence,
            rebindingBaseline = plan.proof.rebindingBaseline,
            postimageSha256 = AdditionPostimageSha256.of(driftImage.postimage.sha256.value),
        )
        applyPostimage(backend, target, before, driftText, plan.proof.insertion.offset.value)

        val declarationFailure = runCatching {
            backend.verifyMutationPostcondition(
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.AddDeclaration(driftProof, driftImage),
                ).parsed(),
            )
        }.exceptionOrNull() as? MutationPostconditionFailedException
            ?: error("Expected changed add-declaration identity to fail")
        assertEquals(
            listOf(MutationPostconditionLimitation.DECLARATION_SET_MISMATCH),
            declarationFailure.limitations,
        )
    }

    @Test
    fun `add declaration postcondition verifier reproves exact persisted authority without writing`() = runBlocking {
        ensureProjectReady()
        val target = Path.of(testFile.virtualFile.path).toAbsolutePath().normalize()
        val backend = additionBackend()
        val before = Files.readAllBytes(target)
        val plan = backend.planAddDeclaration(
            AddDeclarationPlanQuery(
                targetPath = AdditionTargetPath.parse(target.toString()),
                expectedCurrentSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(before)),
                proposedDeclaration = "class VerifiedPostconditionDeclaration",
            ),
        )
        applyPostimage(backend, target, before, plan.proposedContent, plan.proof.insertion.offset.value)
        val postimageBeforeVerification = Files.readAllBytes(target)

        val result = backend.verifyMutationPostcondition(
            MutationPostconditionQuery(
                MutationPostconditionAuthority.AddDeclaration(plan.proof, plan.image),
            ).parsed(),
        )

        assertEquals(MutationPostconditionStatus.VERIFIED, result.status)
        assertArrayEquals(plan.image.postimage.copyBytes(), postimageBeforeVerification)
        assertArrayEquals(postimageBeforeVerification, Files.readAllBytes(target))
    }

    private suspend fun applyPostimage(
        backend: KastIndexerBackend,
        target: Path,
        preimage: ByteArray,
        postimage: String,
        insertionOffset: Int,
    ) {
        assertEquals(insertionOffset, readAction { testFile.textLength })
        backend.applyEdits(
            ApplyEditsQuery(
                edits = listOf(
                    TextEdit(
                        filePath = target.toString(),
                        startOffset = insertionOffset,
                        endOffset = insertionOffset,
                        newText = postimage.substring(insertionOffset),
                    ),
                ),
                fileHashes = listOf(FileHash(target.toString(), FileHashing.sha256(preimage))),
            ),
        )
        val refresh = backend.refresh(RefreshQuery(filePaths = listOf(target.toString())))
        assertEquals(SemanticAnalysisOutcome.COMPLETE, refresh.semanticOutcome)
        waitUntilIndexesAreReady(project)
    }
}
