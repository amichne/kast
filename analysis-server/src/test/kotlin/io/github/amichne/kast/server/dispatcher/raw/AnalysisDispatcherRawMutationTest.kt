package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readText

class AnalysisDispatcherRawMutationTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `mutation scratch inspection and recovery dispatch through one required capability`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val attemptId = "123e4567-e89b-42d3-a456-426614174000"
        val olderOwner = "123e4567-e89b-42d3-a456-426614174099"
        val target = tempDir.resolve("Recovered.kt").toAbsolutePath().normalize()
        val scratch = mutationScratchSet(target, olderOwner, 3)
        val preimage = ExactByteImage.of("before".toByteArray())
        val postimage = ExactByteImage.of("after".toByteArray())
        val absentObservations = listOf(
            scratch.quarantinePath to MutationScratchRole.QUARANTINE,
            scratch.preparedPath to MutationScratchRole.PREPARED,
            scratch.preparedCleanupPath to MutationScratchRole.PREPARED_CLEANUP,
            scratch.quarantineCleanupPath to MutationScratchRole.QUARANTINE_CLEANUP,
        ).map { (filePath, role) ->
            MutationScratchObservation(
                filePath = filePath,
                ownership = MutationScratchOwnership.OWNED,
                role = role,
                state = MutationScratchState.ABSENT,
            )
        }
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities +
                    MutationCapability.MUTATION_SCRATCH_RECOVERY,
            )

            override suspend fun inspectMutationScratch(
                query: ParsedMutationScratchInspectQuery,
            ): MutationScratchInspectResult {
                assertEquals(attemptId, query.mutationAttemptId.value)
                assertEquals(olderOwner, query.ownedScratchSets.single().ownerAttemptId.value)
                return MutationScratchInspectResult(query.mutationAttemptId, emptyList())
            }

            override suspend fun recoverMutationScratch(
                query: ParsedMutationScratchRecoveryQuery,
            ): MutationScratchRecoveryResult {
                assertEquals(MutationScratchDirection.RESTORE_PREIMAGE, query.scratchDirection)
                assertEquals(target.toString(), query.targetFilePath.value)
                return MutationScratchRecoveryResult(
                    mutationAttemptId = query.mutationAttemptId,
                    action = query.action,
                    outcome = MutationScratchRecoveryOutcome.RESTORED_PREIMAGE,
                    targetState = MutationScratchTargetState.PRESENT,
                    targetSha256 = preimage.sha256,
                    scratchObservations = absentObservations,
                )
            }
        }

        val inspectResult = dispatchSuccessWithBackend<MutationScratchInspectResult>(
            backend = backend,
            method = "raw/inspect-mutation-scratch",
            params = json.encodeToJsonElement(
                MutationScratchInspectQuery.serializer(),
                MutationScratchInspectQuery(attemptId, listOf("."), listOf(scratch)),
            ),
        )
        val recoveryResult = dispatchSuccessWithBackend<MutationScratchRecoveryResult>(
            backend = backend,
            method = "raw/recover-mutation-scratch",
            params = json.encodeToJsonElement(
                MutationScratchRecoveryQuery.serializer(),
                MutationScratchRecoveryQuery(
                    mutationAttemptId = attemptId,
                    action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
                    scratchDirection = MutationScratchDirection.RESTORE_PREIMAGE,
                    targetFilePath = target.toString(),
                    preimage = MutationScratchRecoveryPreimage.Present(preimage),
                    postimage = postimage,
                    scratch = scratch,
                ),
            ),
        )

        assertEquals(attemptId, inspectResult.mutationAttemptId.value)
        assertEquals(MutationScratchRecoveryOutcome.RESTORED_PREIMAGE, recoveryResult.outcome)
        assertEquals(preimage.sha256, recoveryResult.targetSha256)
    }

    @Test
    fun `mutation scratch raw transport denies a backend without recovery capability`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val attemptId = "123e4567-e89b-42d3-a456-426614174000"
        val raw = runBlocking {
            RpcAnalysisDispatcher(delegate, AnalysisServerConfig()).dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "raw/inspect-mutation-scratch",
                    params = json.encodeToJsonElement(
                        MutationScratchInspectQuery.serializer(),
                        MutationScratchInspectQuery(attemptId, listOf("."), emptyList()),
                    ),
                ),
            )
        }
        val error = json.decodeFromString(JsonRpcErrorResponse.serializer(), raw)

        assertEquals("CAPABILITY_NOT_SUPPORTED", error.error.data?.code)
        assertEquals("MUTATION_SCRATCH_RECOVERY", error.error.data?.details?.get("capability"))
    }

    @Test
    fun `exact file observation dispatches one closed workspace-relative image`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val relativePath = "src/main/kotlin/Observed.kt"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "class Observed\r\n".toByteArray()
        val image = ExactByteImage.of(bytes)
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities +
                    MutationCapability.EXACT_FILE_OBSERVATION,
            )

            override suspend fun observeExactFile(
                query: ParsedRawExactFileObservationQuery,
            ): RawExactFileObservationResult {
                assertEquals(relativePath, query.filePath.value)
                return RawExactFileObservationResult.Present(query.filePath, image)
            }
        }

        val result = dispatchSuccessWithBackend<RawExactFileObservationResult>(
            backend = backend,
            method = "raw/exact-file-observation",
            params = json.encodeToJsonElement(
                RawExactFileObservationQuery.serializer(),
                RawExactFileObservationQuery(relativePath),
            ),
        )

        val present = assertInstanceOf(RawExactFileObservationResult.Present::class.java, result)
        assertEquals(relativePath, present.filePath.value)
        assertArrayEquals(bytes, present.image.copyBytes())
    }

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
        val existing = sampleFile()
        val preimage = Files.readAllBytes(existing)
        val declaration = "class RawDeclaration"
        val separator = if (preimage.toString(Charsets.UTF_8).endsWith('\n')) "\n" else "\n\n"
        val postimage = preimage + (separator + declaration + "\n").toByteArray()
        val image = ExactFileImage.of(existing.toString(), preimage, postimage)
        val addDeclarationProof = ExactAddDeclarationProof.of(
            targetPath = AdditionTargetPath.parse(existing.toString()),
            targetPreimageSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(preimage)),
            owner = additionOwner(sourceRoot),
            packageIdentity = AdditionKotlinPackage.Root,
            declaration = additionDeclaration("RawDeclaration", 0, declaration.length),
            insertion = CompilerFileBottomInsertion.at(preimage.toString(Charsets.UTF_8).length),
            newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
            context = additionContext(
                ExactAdditionContextFileHash.of(existing.toString(), FileHashing.sha256(preimage)),
            ),
            collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
            outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
            rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
            postimageSha256 = AdditionPostimageSha256.of(image.postimage.sha256.value),
        )
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities + setOf(
                    MutationCapability.PLAN_ADD_FILE,
                    MutationCapability.PLAN_ADD_DECLARATION,
                ),
            )

            override suspend fun planAddFile(query: ParsedAddFilePlanQuery): AddFilePlanResult {
                assertEquals(addFileTarget.toString(), query.targetPath.value)
                assertEquals(addFileContent, query.proposedContent.value)
                return AddFilePlanResult.of(addFileContent, addFileProof)
            }

            override suspend fun planAddDeclaration(query: ParsedAddDeclarationPlanQuery): AddDeclarationPlanResult {
                assertEquals(existing.toString(), query.targetPath.value)
                assertEquals(declaration, query.proposedDeclaration.value)
                return AddDeclarationPlanResult.of(declaration, image, addDeclarationProof)
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
        val addDeclaration = dispatchSuccessWithBackend<AddDeclarationPlanResult>(
            backend = backend,
            method = "raw/plan-add-declaration",
            params = json.encodeToJsonElement(
                AddDeclarationPlanQuery.serializer(),
                AddDeclarationPlanQuery(
                    AdditionTargetPath.parse(existing.toString()),
                    AdditionTargetPreimageSha256.of(FileHashing.sha256(preimage)),
                    declaration,
                ),
            ),
        )

        assertFalse(Files.exists(addFileTarget))
        assertArrayEquals(preimage, Files.readAllBytes(existing))
        assertEquals(addFileProof, addFile.proof)
        assertEquals(addDeclarationProof, addDeclaration.proof)
    }

    @Test
    fun `exact file image CAS dispatches typed bytes through the internal raw transport`() {
        val delegate = FakeAnalysisBackend.sample(tempDir)
        val file = sampleFile()
        val before = Files.readAllBytes(file)
        val after = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "exact\r\n".toByteArray()
        val expectedBefore = ExactFileImageSha256(FileHashing.sha256(before))
        val expectedAfter = ExactFileImageSha256(FileHashing.sha256(after))
        val backend = object : AnalysisBackend by delegate {
            override suspend fun capabilities(): BackendCapabilities = delegate.capabilities().copy(
                mutationCapabilities = delegate.capabilities().mutationCapabilities +
                    MutationCapability.EXACT_FILE_IMAGE_CAS,
            )

            override suspend fun exactFileImageCas(query: ParsedExactFileImageQuery): ExactFileImageResult {
                assertEquals(file.toString(), query.filePath.value)
                assertEquals(expectedBefore, query.expectedCurrentSha256)
                assertArrayEquals(after, query.content.copyBytes())
                assertEquals(expectedAfter, query.expectedResultSha256)
                return ExactFileImageResult.committed(
                    filePath = query.filePath.value,
                    previousSha256 = query.expectedCurrentSha256,
                    resultSha256 = query.expectedResultSha256,
                )
            }
        }

        val result = dispatchSuccessWithBackend<ExactFileImageResult>(
            backend = backend,
            method = "raw/exact-file-image-cas",
            params = json.encodeToJsonElement(
                ExactFileImageQuery.serializer(),
                ExactFileImageQuery(
                    filePath = ExactFileImagePath(file.toString()),
                    expectedCurrentSha256 = expectedBefore,
                    contentBase64 = ExactFileImageBase64(Base64.getEncoder().encodeToString(after)),
                    expectedResultSha256 = expectedAfter,
                ),
            ),
        )

        assertEquals(ExactFileImageStatus.COMMITTED, result.status)
        assertEquals(expectedBefore, result.previousSha256)
        assertEquals(expectedAfter, result.resultSha256)
        assertArrayEquals(before, Files.readAllBytes(file))
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

    private fun mutationScratchSet(
        target: Path,
        ownerAttemptId: String,
        transitionIndex: Int,
    ): MutationScratchSet {
        val parent = requireNotNull(target.parent)
        return MutationScratchSet(
            targetFilePath = target.toString(),
            quarantinePath = parent.resolve(".kast-quarantine-$ownerAttemptId-$transitionIndex").toString(),
            preparedPath = parent.resolve(".kast-prepared-$ownerAttemptId-$transitionIndex.tmp").toString(),
            preparedCleanupPath = parent.resolve(".kast-cleanup-$ownerAttemptId-$transitionIndex-prepared").toString(),
            quarantineCleanupPath = parent.resolve(".kast-cleanup-$ownerAttemptId-$transitionIndex-quarantine").toString(),
        )
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

    @Test
    fun `rename dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RenameResult>(
            method = "raw/rename",
            params = json.encodeToJsonElement(
                RenameQuery.serializer(),
                RenameQuery(
                    position = FilePosition(filePath = file.toString(), offset = 20),
                    newName = "welcome",
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(result.edits.all { edit -> edit.newText == "welcome" })
        assertEquals(result.affectedFiles, result.fileImages.map { image -> image.filePath.value })
        assertArrayEquals(Files.readAllBytes(file), result.fileImages.single().preimage.copyBytes())
    }

    @Test
    fun `imports optimize dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<ImportOptimizeResult>(
            method = "raw/optimize-imports",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(
                    filePaths = listOf(file.toString()),
                ),
            ),
        )

        assertTrue(result.edits.isEmpty())
        assertTrue(result.affectedFiles.isEmpty())
    }

    @Test
    fun `apply edits dispatches without HTTP`() {
        dispatcher()
        val file = sampleFile()
        val originalContent = file.readText()
        val result = dispatchSuccess<ApplyEditsResult>(
            method = "raw/apply-edits",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = listOf(
                        TextEdit(
                            filePath = file.toString(),
                            startOffset = 20,
                            endOffset = 25,
                            newText = "hello",
                        ),
                    ),
                    fileHashes = listOf(
                        FileHash(
                            filePath = file.toString(),
                            hash = FileHashing.sha256(originalContent),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(file.toString()), result.affectedFiles)
        assertTrue(file.readText().contains("hello"))
    }

    @Test
    fun `apply edits validates absolute file operation paths`() {
        val response = dispatchRaw(
            method = "raw/apply-edits",
            params = json.encodeToJsonElement(
                ApplyEditsQuery.serializer(),
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = "relative/New.kt",
                            content = "class New",
                        ),
                    ),
                ),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `imports optimize validates absolute file paths`() {
        val response = dispatchRaw(
            method = "raw/optimize-imports",
            params = json.encodeToJsonElement(
                ImportOptimizeQuery.serializer(),
                ImportOptimizeQuery(filePaths = listOf("relative/File.kt")),
            ),
        )

        val error = json.decodeFromJsonElement(
            JsonRpcErrorResponse.serializer(),
            response,
        )
        assertEquals("VALIDATION_ERROR", error.error.data?.code)
    }

    @Test
    fun `workspace refresh dispatches without HTTP`() {
        val file = sampleFile()

        val result = dispatchSuccess<RefreshResult>(
            method = "raw/workspace-refresh",
            params = json.encodeToJsonElement(
                RefreshQuery.serializer(),
                RefreshQuery(filePaths = listOf(file.toString())),
            ),
        )

        assertEquals(listOf(file.toString()), result.refreshedFiles)
        assertTrue(result.removedFiles.isEmpty())
        assertEquals(false, result.fullRefresh)
    }
}
