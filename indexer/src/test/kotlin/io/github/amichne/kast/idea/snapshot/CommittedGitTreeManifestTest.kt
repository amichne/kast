package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommittedGitTreeManifestTest {
    @Test
    fun `invalid UTF-8 path rejects the complete tree manifest`() {
        val treeOid = GitObjectId.fromCanonical("a".repeat(40))
        val rawManifest =
            "100644 blob ${"b".repeat(40)}\tsrc/Invalid".encodeToByteArray() +
                byteArrayOf(0x80.toByte()) +
                ".kt\u0000".encodeToByteArray()

        val resolution = CommittedGitTreeManifest.resolve(treeOid, rawManifest)

        assertEquals(
            CommittedGitTreeResolution.Unavailable(
                CommittedGitTreeFailure.InvalidGitOutput(GitTreeReadRequest.TreeManifest(treeOid)),
            ),
            resolution,
        )
    }
}
