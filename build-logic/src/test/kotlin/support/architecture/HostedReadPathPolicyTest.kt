package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import support.delivery.ProofReceiptExpectation
import support.delivery.ProofReceiptExpectationResult
import support.delivery.AdmittedProofReceipt
import support.delivery.ProofReceiptAdmission
import support.delivery.admitProofReceipt
import support.delivery.encodeProofReceiptDocument
import support.delivery.issueProofReceipt
import java.time.Instant

class HostedReadPathPolicyTest {
    @Test
    fun `all finite injected families are classified and passive controls stay allowed`() {
        val module = hostedModule()
        val complete = assertInstanceOf<HostedReadInjectionVerification.Complete>(
            HostedReadPathPolicy.verifyInjectedAuthorities(module),
        )

        assertEquals(120, Kvp018RequiredForbiddenFamily.entries.size)
        assertEquals(
            Kvp018RequiredForbiddenFamily.entries,
            HostedReadForbiddenAuthority.entries.map { it.family },
        )
        assertEquals(Kvp018RequiredForbiddenFamily.entries.toSet(), complete.proof.observations().keys)
        complete.proof.observations().forEach { (family, effects) ->
            val authority = HostedReadForbiddenAuthority.entries.single { it.family == family }
            assertTrue(authority.requiredEffect in effects, family.name)
        }
        val first = complete.proof.observations().entries.first()
        (first.value as MutableSet).clear()
        (complete.proof.observations() as MutableMap).clear()
        assertEquals(120, complete.proof.observations().size)
        val firstAuthority = HostedReadForbiddenAuthority.entries.single {
            it.family == first.key
        }
        assertTrue(firstAuthority.requiredEffect in complete.proof.observations().getValue(first.key))
        passiveControls.forEach { target ->
            val effects = JvmEffectClassifier.classify(module, fixtureCaller, target)
            assertTrue(effects.all { it == ForbiddenEffect.INTELLIJ_PLATFORM }, target.toString())
        }
        listOf(
            HostedReadForbiddenAuthority.FILE_INDEX_REBUILD,
            HostedReadForbiddenAuthority.DUMB_QUEUE_TASK,
        ).forEach { authority ->
            val effects = JvmEffectClassifier.classify(module, fixtureCaller, authority.target)
            assertTrue(ForbiddenEffect.INDEXING_CYCLE in effects, authority.name)
            assertFalse(ForbiddenEffect.RECURSIVE_VFS_REFRESH in effects, authority.name)
        }
    }

    @Test
    fun `inventory refinement rejects gaps and retains immutable admitted members`() {
        assertInstanceOf<HostedReadInventoryRefinement.Rejected>(
            HostedReadClassInventory.refine(emptyList(), setOf("Required.class")),
        )
        val rejected = assertInstanceOf<HostedReadInventoryRefinement.Rejected>(
            HostedReadClassInventory.refine(
                listOf(
                    RawHostedReadClassArtifact("../Escape.class", "bad"),
                    RawHostedReadClassArtifact("Duplicate.class", "a".repeat(64)),
                    RawHostedReadClassArtifact("Duplicate.class", "b".repeat(64)),
                ),
                setOf("Required.class"),
            ),
        )
        val failures = listOf(rejected.first) + rejected.additional
        assertTrue(failures.any { it is HostedReadInventoryFailure.InvalidClassName })
        assertTrue(failures.any { it is HostedReadInventoryFailure.InvalidClassDigest })
        assertTrue(failures.any { it is HostedReadInventoryFailure.DuplicateClass })
        assertTrue(failures.any { it is HostedReadInventoryFailure.MissingRequiredClass })

        val inventory = assertInstanceOf<HostedReadInventoryRefinement.Refined>(
            HostedReadClassInventory.refine(
                listOf(RawHostedReadClassArtifact("Hosted.class", "a".repeat(64))),
                setOf("Hosted.class"),
            ),
        ).inventory
        inventory.classes().toMutableList().clear()
        assertEquals(1, inventory.classes().size)
    }

    @Test
    fun `predecessor pair rejects malformed members and retains exact immutable order`() {
        assertInstanceOf<Kvp018PredecessorArtifactRefinement.Rejected>(
            Kvp018PredecessorReceiptArtifact.decode(
                Kvp018PredecessorReceiptId.KVP_016_COMPLETE,
                "not-json",
            ),
        )
        assertInstanceOf<Kvp018PredecessorArtifactRefinement.Rejected>(
            Kvp018PredecessorReceiptArtifact.fromAdmitted(
                Kvp018PredecessorReceiptId.KVP_017_COMPLETE,
                predecessorReceipt(Kvp018PredecessorReceiptId.KVP_016_COMPLETE),
            ),
        )
        val expectedId = Kvp018PredecessorReceiptId.KVP_016_COMPLETE
        val wrongTask = assertInstanceOf<Kvp018PredecessorArtifactRefinement.Rejected>(
            Kvp018PredecessorReceiptArtifact.decode(
                expectedId,
                rawPredecessorReceipt(expectedId, "KVP-017", expectedId.gateIdValue),
            ),
        )
        assertEquals(
            Kvp018PredecessorReceiptFailure.ReceiptTaskMismatch(expectedId, "KVP-017"),
            wrongTask.failure,
        )
        val wrongGate = assertInstanceOf<Kvp018PredecessorArtifactRefinement.Rejected>(
            Kvp018PredecessorReceiptArtifact.decode(
                expectedId,
                rawPredecessorReceipt(
                    expectedId,
                    expectedId.taskIdValue,
                    "KVP-017-COMPLETE-GATE",
                ),
            ),
        )
        assertEquals(
            Kvp018PredecessorReceiptFailure.ReceiptGateMismatch(
                expectedId,
                "KVP-017-COMPLETE-GATE",
            ),
            wrongGate.failure,
        )
        val predecessors = assertInstanceOf<Kvp018PredecessorReceiptRefinement.Admitted>(
            Kvp018PredecessorReceipts.refine(
                listOf(
                    predecessor(Kvp018PredecessorReceiptId.KVP_016_COMPLETE),
                    predecessor(Kvp018PredecessorReceiptId.KVP_017_COMPLETE),
                ),
            ),
        ).receipts
        predecessors.artifacts().toMutableList().clear()
        assertEquals(Kvp018PredecessorReceiptId.entries, predecessors.artifacts().map { it.id })
    }

    private fun hostedModule(): ValidatedModulePolicy =
        (KastArchitecturePolicy.validate() as ArchitecturePolicyValidation.Valid)
            .architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ)

    private fun predecessor(id: Kvp018PredecessorReceiptId): Kvp018PredecessorReceiptArtifact {
        val receipt = predecessorReceipt(id)
        return assertInstanceOf<Kvp018PredecessorArtifactRefinement.Admitted>(
            Kvp018PredecessorReceiptArtifact.fromAdmitted(id, receipt),
        ).artifact
    }

    private fun predecessorReceipt(id: Kvp018PredecessorReceiptId): AdmittedProofReceipt {
        val expectation = predecessorExpectation(id, id.taskIdValue, id.gateIdValue)
        val document = issueProofReceipt(expectation, Instant.parse("2026-08-26T00:00:00Z"))
        val raw = encodeProofReceiptDocument(document)
        assertInstanceOf<Kvp018PredecessorArtifactRefinement.Admitted>(
            Kvp018PredecessorReceiptArtifact.decode(id, raw),
        )
        return assertInstanceOf<ProofReceiptAdmission.Complete>(
            admitProofReceipt(document, expectation),
        ).receipt
    }

    private fun rawPredecessorReceipt(
        id: Kvp018PredecessorReceiptId,
        taskId: String,
        gateId: String,
    ): String = encodeProofReceiptDocument(
        issueProofReceipt(
            predecessorExpectation(id, taskId, gateId),
            Instant.parse("2026-08-26T00:00:00Z"),
        ),
    )

    private fun predecessorExpectation(
        id: Kvp018PredecessorReceiptId,
        taskId: String,
        gateId: String,
    ): ProofReceiptExpectation {
        val expectation = assertInstanceOf<ProofReceiptExpectationResult.Complete>(
            ProofReceiptExpectation.parse(
                id.receiptIdValue,
                "a".repeat(40),
                "b".repeat(40),
                "c".repeat(64),
                "d".repeat(64),
                taskId,
                gateId,
                emptyMap(),
                "e".repeat(64),
                "f".repeat(64),
                mapOf("outcome" to "COMPLETE"),
                emptyMap(),
            ),
        ).expectation
        return expectation
    }

    private companion object {
        val fixtureCaller = JvmMember.of("fixture/HostedRead", "read", "()V")
        val passiveControls = listOf(
            JvmMember.of("java/net/URI", "toString", "()Ljava/lang/String;"),
            JvmMember.of("com/intellij/openapi/project/Project", "getBasePath", "()Ljava/lang/String;"),
            JvmMember.of("com/intellij/openapi/vfs/VirtualFile", "getPath", "()Ljava/lang/String;"),
            JvmMember.of("com/intellij/util/indexing/FileBasedIndex", "getInstance", "()Lcom/intellij/util/indexing/FileBasedIndex;"),
            JvmMember.of(
                "com/intellij/psi/PsiManager",
                "findFile",
                "(Lcom/intellij/openapi/vfs/VirtualFile;)Lcom/intellij/psi/PsiFile;",
            ),
        )
    }
}
