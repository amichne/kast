package io.github.amichne.kast.protocol.continuation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ContinuationDetachedSurfaceTest {
    @Test
    fun `public and owned storage surfaces contain no live IntelliJ shaped type`() {
        val forbidden = Regex("com\\.intellij|Psi|VirtualFile|GlobalSearchScope|Project")
        val surface = listOf(
            DetachedContinuationStore::class.java,
            ContinuationBinding::class.java,
            DetachedContinuationRecord::class.java,
            ContinuationPage::class.java,
            OwnedContinuationState::class.java,
            ContinuationEntry::class.java,
        ).flatMap { type ->
            type.declaredFields.map { it.genericType.typeName } +
            type.declaredMethods.flatMap { method ->
                listOf(method.genericReturnType.typeName) +
                method.genericParameterTypes.map { it.typeName }
            }
        }
        assertFalse(surface.any(forbidden::containsMatchIn), surface.joinToString())
    }
}
