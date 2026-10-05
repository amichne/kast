package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RelationScopeFingerprintTest {
    @Test
    fun `distinct frontier identities can share a selector scope fingerprint`() {
        val fixture = CallbackInvocationFlowFixture()
        val first = fixture.endpoint("first", 20, 30, emptyList())
        val second = fixture.endpoint("second", 40, 50, emptyList())
        assertNotEquals(first.compilerIdentity, second.compilerIdentity)
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertEquals(
            RelationScopeFingerprint.from(first, RelationSearchBoundary.WORKSPACE_EXPANSION),
            RelationScopeFingerprint.from(second, RelationSearchBoundary.WORKSPACE_EXPANSION),
        )
    }
}
