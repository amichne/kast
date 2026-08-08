package io.github.amichne.kast.idea

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IdeaGradleProjectLoadBridgeSourceRootTest {
    @Test
    fun `Gradle bridge preserves source-root provenance from model evidence`() {
        listOf(
            Path.of("/workspace/build/generated/authored-by-model"),
            Path.of("/workspace/buildSrc/src/main/kotlin"),
            Path.of("/workspace/build-logic/src/main/kotlin"),
        ).forEach { path ->
            val root = IdeaGradleProjectLoadBridge.classifySourceRoot(
                path,
                listOf(ExternalSystemSourceType.SOURCE),
            )
            val authored = assertInstanceOf(
                IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Authored::class.java,
                root.provenance(),
            )
            assertEquals(listOf("SOURCE"), authored.modelEvidence().map { it.name })
        }

        val generated = IdeaGradleProjectLoadBridge.classifySourceRoot(
            Path.of("/workspace/generated-outside-build/kotlin"),
            listOf(ExternalSystemSourceType.SOURCE_GENERATED),
        )
        val generatedProvenance = assertInstanceOf(
            IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Generated::class.java,
            generated.provenance(),
        )
        assertEquals(
            listOf("SOURCE_GENERATED"),
            generatedProvenance.modelEvidence().map { it.name },
        )

        val unknown = IdeaGradleProjectLoadBridge.classifySourceRoot(
            Path.of("/workspace/unclassified/kotlin"),
            emptyList(),
        )
        val unknownProvenance = assertInstanceOf(
            IdeaGradleProjectLoadBridge.GradleSourceRootProvenance.Unknown::class.java,
            unknown.provenance(),
        )
        assertEquals("Gradle model supplied no source-type classification", unknownProvenance.reason())
        assertEquals(emptyList<String>(), unknownProvenance.modelEvidence().map { it.name })
    }
}
