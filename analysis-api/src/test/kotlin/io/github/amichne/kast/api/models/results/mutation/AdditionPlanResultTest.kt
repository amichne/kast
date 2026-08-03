package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.result.AddDeclarationPlanResult
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.AdditionClasspathFingerprint
import io.github.amichne.kast.api.contract.result.AdditionDeclarationCollisionSignature
import io.github.amichne.kast.api.contract.result.AdditionGradleBuildRoot
import io.github.amichne.kast.api.contract.result.AdditionGradleProjectPath
import io.github.amichne.kast.api.contract.result.AdditionGradleSourceSetName
import io.github.amichne.kast.api.contract.result.AdditionIdeaModuleName
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionNewlinePolicy
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionProjectModelFingerprint
import io.github.amichne.kast.api.contract.result.AdditionSourceOwner
import io.github.amichne.kast.api.contract.result.AdditionSourceRoot
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclarationKind
import io.github.amichne.kast.api.contract.result.CompilerFileBottomInsertion
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.ExactAdditionCollisionEvidence
import io.github.amichne.kast.api.contract.result.ExactAdditionContextFileHash
import io.github.amichne.kast.api.contract.result.ExactAdditionOutboundEvidence
import io.github.amichne.kast.api.contract.result.ExactAdditionProofContext
import io.github.amichne.kast.api.contract.result.ExactAdditionRebindingBaseline
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AdditionPlanResultTest {
    @Test
    fun `add file result binds every proof range and hash to the exact proposed image`() {
        val content = "package sample\n\nclass Added\n"
        val result = AddFilePlanResult.of(content, addFileProof(content, 16, 27))

        assertEquals(content, result.proposedContent)
        assertArrayEquals(content.utf8(), result.postimage.copyBytes())
        assertThrows(IllegalArgumentException::class.java) {
            AddFilePlanResult.of(content, addFileProof(content, 16, content.length + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddFilePlanResult.of(content + " ", addFileProof(content, 16, 27))
        }
    }

    @Test
    fun `add declaration result binds insertion and exact pre post images`() {
        val target = "/workspace/src/main/kotlin/sample/Existing.kt"
        val preimage = "package sample\n\nclass Existing\n"
        val declaration = "class Added"
        val postimage = preimage + "\n" + declaration + "\n"
        val image = ExactFileImage.of(target, preimage.utf8(), postimage.utf8())
        val result = AddDeclarationPlanResult.of(
            proposedDeclaration = declaration,
            image = image,
            proof = addDeclarationProof(preimage, postimage, 0, declaration.length),
        )

        assertEquals(declaration, result.proposedDeclaration)
        assertEquals(postimage, result.proposedContent)
        assertThrows(IllegalArgumentException::class.java) {
            AddDeclarationPlanResult.of(
                declaration,
                image,
                addDeclarationProof(preimage, postimage, 0, declaration.length, insertion = preimage.length - 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddDeclarationPlanResult.of(
                declaration,
                image,
                addDeclarationProof(preimage, postimage, 0, declaration.length + 1),
            )
        }
    }

    @Test
    fun `add declaration rejects a normalized-equal postimage that rewrites existing raw bytes`() {
        val target = "/workspace/src/main/kotlin/sample/Existing.kt"
        val rawPreimage = "package sample\r\n\r\nclass Existing\r\n"
        val declaration = "class Added"
        val rewrittenPostimage = "package sample\n\nclass Existing\n\nclass Added\n"
        val image = ExactFileImage.of(target, rawPreimage.utf8(), rewrittenPostimage.utf8())

        assertThrows(IllegalArgumentException::class.java) {
            AddDeclarationPlanResult.of(
                proposedDeclaration = declaration,
                image = image,
                proof = addDeclarationProof(
                    preimage = rawPreimage,
                    postimage = rewrittenPostimage,
                    start = 0,
                    end = declaration.length,
                    insertion = "package sample\n\nclass Existing\n".length,
                ),
            )
        }
    }

    private fun addFileProof(content: String, start: Int, end: Int): ExactAddFileProof = ExactAddFileProof.of(
        targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Added.kt"),
        owner = owner(),
        packageIdentity = PACKAGE,
        declarations = listOf(declaration(start, end)),
        context = context(),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
        rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
        postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(content.utf8())),
    )

    private fun addDeclarationProof(
        preimage: String,
        postimage: String,
        start: Int,
        end: Int,
        insertion: Int = preimage.length,
    ): ExactAddDeclarationProof {
        val target = "/workspace/src/main/kotlin/sample/Existing.kt"
        val prehash = FileHashing.sha256(preimage.utf8())
        return ExactAddDeclarationProof.of(
            targetPath = AdditionTargetPath.parse(target),
            targetPreimageSha256 = AdditionTargetPreimageSha256.of(prehash),
            owner = owner(),
            packageIdentity = PACKAGE,
            declaration = declaration(start, end),
            insertion = CompilerFileBottomInsertion.at(insertion),
            newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
            context = context(ExactAdditionContextFileHash.of(target, prehash)),
            collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
            outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
            rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
            postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(postimage.utf8())),
        )
    }

    private fun declaration(start: Int, end: Int): AdditionTopLevelDeclaration =
        AdditionTopLevelDeclaration.of(
            packageIdentity = PACKAGE,
            name = "Added",
            kind = AdditionTopLevelDeclarationKind.CLASS,
            relativeStartOffset = start,
            relativeEndOffset = end,
            collisionSignature = AdditionDeclarationCollisionSignature.of("1".repeat(64)),
        )

    private fun context(vararg hashes: ExactAdditionContextFileHash): ExactAdditionProofContext =
        ExactAdditionProofContext.of(
            requiredGeneration = MutationSemanticGeneration(7),
            projectModelFingerprint = AdditionProjectModelFingerprint.of("2".repeat(64)),
            classpathFingerprint = AdditionClasspathFingerprint.of("3".repeat(64)),
            contextFileHashes = hashes.toList(),
        )

    private fun owner(): AdditionSourceOwner = AdditionSourceOwner.of(
        sourceRoot = AdditionSourceRoot.parse("/workspace/src/main/kotlin"),
        ideaModuleName = AdditionIdeaModuleName.of("main"),
        gradleBuildRoot = AdditionGradleBuildRoot.parse("/workspace"),
        gradleProjectPath = AdditionGradleProjectPath.parse(":"),
        sourceSetName = AdditionGradleSourceSetName.of("main"),
    )

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private companion object {
        val PACKAGE: AdditionKotlinPackage = AdditionKotlinPackage.Named.of("sample")
    }
}
