package io.github.amichne.kast.api.contract.selector

import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class DigestSelectorHandleAuthorityTest {
    @Test
    fun `rehashed selector claims are rejected as tampered`() {
        val authority = authority()
        val issued = issue(authority)
        val forged = issued.rehashReplacing("sample.first", "sample.forge")

        val resolution = authority.resolve(
            handle = forged,
            workspaceRoot = WORKSPACE_ROOT,
            family = SelectorOperationFamily.REFERENCES,
        )

        assertEquals(
            SelectorHandleAuthority.Resolution.Rejected(
                SelectorHandleAuthority.Resolution.RejectionReason.TAMPERED,
            ),
            resolution,
        )
    }

    @Test
    fun `valid handles distinguish scope generation and family rejections`() {
        var generation = 1L
        val authority = authority(semanticGeneration = { generation })
        val issued = issue(authority)

        assertRejected(
            SelectorHandleAuthority.Resolution.RejectionReason.WRONG_WORKSPACE,
            authority.resolve(issued, "/other", SelectorOperationFamily.REFERENCES),
        )
        assertRejected(
            SelectorHandleAuthority.Resolution.RejectionReason.WRONG_BACKEND,
            authority(backendName = "other-backend").resolve(
                issued,
                WORKSPACE_ROOT,
                SelectorOperationFamily.REFERENCES,
            ),
        )

        generation += 1
        assertRejected(
            SelectorHandleAuthority.Resolution.RejectionReason.STALE,
            authority.resolve(issued, WORKSPACE_ROOT, SelectorOperationFamily.REFERENCES),
        )

        val current = issue(authority)
        assertRejected(
            SelectorHandleAuthority.Resolution.RejectionReason.FAMILY_NOT_ALLOWED,
            authority.resolve(current, WORKSPACE_ROOT, SelectorOperationFamily.CALLERS),
        )
        assertRejected(
            SelectorHandleAuthority.Resolution.RejectionReason.TAMPERED,
            authority.resolve(current.withChangedAuthenticatedByte(), WORKSPACE_ROOT, SelectorOperationFamily.REFERENCES),
        )
    }

    private fun authority(
        backendName: String = "test-backend",
        semanticGeneration: () -> Long = { 1L },
    ): DigestSelectorHandleAuthority = DigestSelectorHandleAuthority(
        workspaceRoot = WORKSPACE_ROOT,
        backendName = backendName,
        backendVersion = "1.0.0",
        backendInstanceId = "instance-1",
        semanticGeneration = semanticGeneration,
    )

    private fun issue(authority: DigestSelectorHandleAuthority): String {
        val result = authority.issue(
            selector = KastExactSymbolSelector(
                fqName = "sample.first",
                declarationFile = "$WORKSPACE_ROOT/src/Sample.kt",
                declarationStartOffset = 42,
                kind = SymbolKind.FUNCTION,
                containingType = "sample.Sample",
            ),
            allowedFamilies = setOf(SelectorOperationFamily.REFERENCES),
        )
        return assertInstanceOf(
            SelectorHandleAuthority.IssueResult.Issued::class.java,
            result,
        ).handle.value
    }

    private fun assertRejected(
        reason: SelectorHandleAuthority.Resolution.RejectionReason,
        resolution: SelectorHandleAuthority.Resolution,
    ) {
        assertEquals(SelectorHandleAuthority.Resolution.Rejected(reason), resolution)
    }

    private fun String.rehashReplacing(from: String, to: String): String {
        require(from.length == to.length)
        val envelope = Base64.getUrlDecoder().decode(removePrefix(SelectorHandle.PREFIX))
        val payload = envelope.copyOfRange(0, envelope.size - SHA_256_LENGTH)
        val fromBytes = from.encodeToByteArray()
        val replacementOffset = payload.find(fromBytes)
        require(replacementOffset >= 0) { "Selector claim was not found in the handle" }
        to.encodeToByteArray().copyInto(payload, replacementOffset)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return SelectorHandle.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload + digest)
    }

    private fun String.withChangedAuthenticatedByte(): String {
        val envelope = Base64.getUrlDecoder().decode(removePrefix(SelectorHandle.PREFIX))
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        return SelectorHandle.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)
    }

    private fun ByteArray.find(needle: ByteArray): Int = indices.firstOrNull { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    } ?: -1

    private companion object {
        const val WORKSPACE_ROOT: String = "/workspace"
        const val SHA_256_LENGTH: Int = 32
    }
}
