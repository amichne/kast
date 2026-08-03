package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnalysisDispatcherRawMutationRecoveryTest : AnalysisDispatcherTestSupport() {
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
}
