package io.github.amichne.kast.cli.skill

import io.github.amichne.kast.api.wrapper.KastRenameRequest
import io.github.amichne.kast.api.wrapper.KastWriteAndValidateRequest
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class SkillWrapperDiscriminatorTest {
    @Test
    fun `quickstart documents every request discriminator`() {
        val quickstart = repoRoot()
            .resolve(".agents/skills/kast/references/quickstart.md")
            .readText()
        val discriminators = discriminatorValues(KastRenameRequest.serializer()) +
            discriminatorValues(KastWriteAndValidateRequest.serializer())

        discriminators.forEach { discriminator ->
            assertTrue(
                quickstart.contains(discriminator),
                "quickstart.md is missing $discriminator",
            )
        }
    }

    private fun discriminatorValues(serializer: KSerializer<*>): Set<String> =
        (0 until serializer.descriptor.elementsCount)
            .map(serializer.descriptor::getElementName)
            .filter { it.endsWith("_REQUEST") }
            .toSet()

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { current -> current.parent }
            .first { candidate -> Files.isDirectory(candidate.resolve(".agents")) }
}
