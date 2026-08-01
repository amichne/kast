package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SourceIndexFilePolicyTest {
    @Test
    fun `accepts Kotlin source files and rejects Kotlin scripts`() {
        assertTrue(SourceIndexFilePolicy.isEligible(Path.of("/workspace/src/main/kotlin/Foo.kt")))

        assertFalse(SourceIndexFilePolicy.isEligible(Path.of("/workspace/src/main/kotlin/Foo.KT")))
        assertFalse(SourceIndexFilePolicy.isEligible(Path.of("/workspace/build.gradle.kts")))
        assertFalse(SourceIndexFilePolicy.isEligible(Path.of("/workspace/settings.gradle.kts")))
        assertFalse(SourceIndexFilePolicy.isEligible(Path.of("/workspace/script.main.kts")))
    }

    @Test
    fun `rejects generated and IDE output roots`() {
        for (path in listOf(
            "/workspace/build/generated/Foo.kt",
            "/workspace/plugin/build/distributions/Plugin.kt",
            "/workspace/.gradle/caches/Foo.kt",
            "/workspace/out/production/Foo.kt",
            "/workspace/.idea/Foo.kt",
        )) {
            assertFalse(SourceIndexFilePolicy.isEligible(Path.of(path)), path)
        }
    }
}
