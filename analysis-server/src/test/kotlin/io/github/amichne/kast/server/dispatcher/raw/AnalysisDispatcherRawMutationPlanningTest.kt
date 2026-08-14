package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnalysisDispatcherRawMutationPlanningTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `mutation postcondition verifier dispatches persisted authority without writing`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val target = tempDir.resolve("VerifiedAdded.kt").toAbsolutePath().normalize()
        val content = "class VerifiedAdded"
        val postimage = ExactByteImage.of(content.toByteArray())
        val proof = ExactAddFileProof.of(
            targetPath = AdditionTargetPath.parse(target.toString()),
            owner = additionOwner(tempDir.toAbsolutePath().normalize()),
            packageIdentity = AdditionKotlinPackage.Root,
            declarations = listOf(additionDeclaration("VerifiedAdded", 0, content.length)),
            context = additionContext(),
            collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
            outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
            rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
            postimageSha256 = AdditionPostimageSha256.of(postimage.sha256.value),
        )
        val expected = MutationPostconditionResult.verified(
            operation = MutationPostconditionOperation.ADD_FILE,
            currentGeneration = MutationSemanticGeneration(9),
            postimages = listOf(VerifiedMutationPostimage(ExactFileImagePath(target.toString()), postimage.sha256)),
            evidence = MutationPostconditionEvidence.AddFile(
                owner = proof.owner,
                packageIdentity = proof.packageIdentity,
                declarations = proof.declarations,
                outboundEvidence = proof.outboundEvidence,
            ),
        )
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities +
                    MutationCapability.VERIFY_MUTATION_POSTCONDITION,
            )

            override suspend fun verifyMutationPostcondition(
                query: ParsedMutationPostconditionQuery,
            ): MutationPostconditionResult {
                val authority = query.authority as ParsedMutationPostconditionAuthority.AddFile
                assertEquals(proof, authority.proof)
                assertArrayEquals(content.toByteArray(), authority.postimage.copyBytes())
                return expected
            }
        }

        val result = dispatchSuccessWithBackend<MutationPostconditionResult>(
            backend = backend,
            method = "raw/verify-mutation-postcondition",
            params = json.encodeToJsonElement(
                MutationPostconditionQuery.serializer(),
                MutationPostconditionQuery(MutationPostconditionAuthority.AddFile(proof, postimage)),
            ),
        )

        assertEquals(MutationPostconditionStatus.VERIFIED, result.status)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `addition planners dispatch strict typed plans without writing`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val sourceRoot = tempDir.toAbsolutePath().normalize()
        val addFileTarget = sourceRoot.resolve("RawAdded.kt")
        val addFileContent = "class RawAdded"
        val addFileProof = ExactAddFileProof.of(
            targetPath = AdditionTargetPath.parse(addFileTarget.toString()),
            owner = additionOwner(sourceRoot),
            packageIdentity = AdditionKotlinPackage.Root,
            declarations = listOf(additionDeclaration("RawAdded", 0, addFileContent.length)),
            context = additionContext(),
            collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
            outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
            rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
            postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(addFileContent.toByteArray())),
        )
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities +
                    MutationCapability.PLAN_ADD_FILE,
            )

            override suspend fun planAddFile(query: ParsedAddFilePlanQuery): AddFilePlanResult {
                assertEquals(addFileTarget.toString(), query.targetPath.value)
                assertEquals(addFileContent, query.proposedContent.value)
                return AddFilePlanResult.of(addFileContent, addFileProof)
            }
        }

        val addFile = dispatchSuccessWithBackend<AddFilePlanResult>(
            backend = backend,
            method = "raw/plan-add-file",
            params = json.encodeToJsonElement(
                AddFilePlanQuery.serializer(),
                AddFilePlanQuery(AdditionTargetPath.parse(addFileTarget.toString()), addFileContent),
            ),
        )
        assertFalse(Files.exists(addFileTarget))
        assertEquals(addFileProof, addFile.proof)
    }

    @Test
    fun `replacement plan dispatches through required non-mutating transport`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val file = sampleFile()
        val sourceBefore = file.readText()
        val target = SymbolIdentity(
            fqName = "sample.greet",
            kind = SymbolKind.FUNCTION,
            declarationFile = NormalizedPath.parse(file.toString()),
            declarationStartOffset = NonNegativeInt(20),
        )
        val proposed = "fun greet(name: String): String = name"
        val fileImage = ExactFileImage.of(
            filePath = file.toString(),
            preimageBytes = Files.readAllBytes(file),
            postimageBytes = proposed.toByteArray(),
        )
        val signature = ReplacementFunctionSignature.of(
            name = "greet",
            receiverType = null,
            contextReceiverTypes = emptyList(),
            typeParameters = emptyList(),
            valueParameters = listOf(
                ReplacementValueParameterSignature(
                    name = "name",
                    type = "kotlin.String",
                    vararg = false,
                    hasDefaultValue = false,
                    noinline = false,
                    crossinline = false,
                ),
            ),
            returnType = "kotlin.String",
            visibility = ReplacementVisibility.PUBLIC,
            modality = ReplacementModality.FINAL,
            hasStableParameterNames = true,
            suspend = false,
            operator = false,
            inline = false,
            override = false,
            infix = false,
            static = false,
            tailrec = false,
            external = false,
            expect = false,
            actual = false,
        )
        val proof = ExactReplacementProof.of(
            target = target,
            requiredGeneration = MutationSemanticGeneration(7),
            sourceRange = Location(
                filePath = file.toString(),
                startOffset = 0,
                endOffset = sourceBefore.length,
                startLine = 1,
                startColumn = 1,
                preview = sourceBefore.lineSequence().first(),
            ),
            fileHashes = listOf(FileHash(file.toString(), FileHashing.sha256(sourceBefore))),
            oldSignature = signature,
            proposedSignature = signature,
            proposedDeclarationHash = ReplacementDeclarationSha256(FileHashing.sha256(proposed)),
            proposedDeclarationLength = proposed.length,
            declarationSlice = ReplacementDeclarationSlice(NonNegativeInt(0), NonNegativeInt(proposed.length)),
            evidence = ReplacementOutboundEvidence.Complete.of(0),
            outboundReferences = emptyList(),
        )
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities +
                    MutationCapability.PLAN_REPLACEMENT,
            )

            override suspend fun planReplacement(query: ParsedReplacementPlanQuery): ReplacementPlanResult {
                assertEquals(target, query.target)
                assertEquals(proposed, query.proposedDeclaration.value)
                return ReplacementPlanResult.of(
                    edit = TextEdit(
                        filePath = file.toString(),
                        startOffset = 0,
                        endOffset = sourceBefore.length,
                        newText = proposed,
                    ),
                    proof = proof,
                    fileImages = listOf(fileImage),
                )
            }
        }

        val result = dispatchSuccessWithBackend<ReplacementPlanResult>(
            backend = backend,
            method = "raw/plan-replacement",
            params = json.encodeToJsonElement(
                ReplacementPlanQuery.serializer(),
                ReplacementPlanQuery(target = target, proposedDeclaration = proposed),
            ),
        )

        assertEquals(target, result.proof.target)
        assertEquals(ReplacementProofDimension.entries, result.proof.evidence.dimensions)
        assertArrayEquals(fileImage.preimage.copyBytes(), result.fileImages.single().preimage.copyBytes())
        assertArrayEquals(fileImage.postimage.copyBytes(), result.fileImages.single().postimage.copyBytes())
        assertEquals(sourceBefore, file.readText())
    }

    private fun additionOwner(sourceRoot: Path): AdditionSourceOwner = AdditionSourceOwner.of(
        sourceRoot = AdditionSourceRoot.parse(sourceRoot.toString()),
        ideaModuleName = AdditionIdeaModuleName.of("main"),
        gradleBuildRoot = AdditionGradleBuildRoot.parse(sourceRoot.parent.toString()),
        gradleProjectPath = AdditionGradleProjectPath.parse(":"),
        sourceSetName = AdditionGradleSourceSetName.of("main"),
    )

    private fun additionDeclaration(name: String, start: Int, end: Int): AdditionTopLevelDeclaration =
        AdditionTopLevelDeclaration.of(
            packageIdentity = AdditionKotlinPackage.Root,
            name = name,
            kind = AdditionTopLevelDeclarationKind.CLASS,
            relativeStartOffset = start,
            relativeEndOffset = end,
            collisionSignature = AdditionDeclarationCollisionSignature.of("1".repeat(64)),
        )

    private fun additionContext(vararg hashes: ExactAdditionContextFileHash): ExactAdditionProofContext =
        ExactAdditionProofContext.of(
            requiredGeneration = MutationSemanticGeneration(1),
            projectModelFingerprint = AdditionProjectModelFingerprint.of("2".repeat(64)),
            classpathFingerprint = AdditionClasspathFingerprint.of("3".repeat(64)),
            contextFileHashes = hashes.toList(),
        )
}
