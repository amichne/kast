package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.architecture.gradle.HostedReadExternalInputFailure
import support.architecture.gradle.HostedReadExternalInputResult
import support.architecture.gradle.HostedReadProjectInputFailure
import support.architecture.gradle.HostedReadProjectInputResult
import support.architecture.gradle.loadHostedReadExternalInputs
import support.architecture.gradle.loadHostedReadProjectInputs
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class HostedReadInputRefinementTest {
    @Test
    fun `duplicate declared project artifact name is rejected`() {
        val result =
            loadHostedReadProjectInputs(
                identities =
                    listOf(
                        ":kernel|shared.jar",
                        ":protocol:contract|shared.jar",
                    ),
                files = emptySet(),
            )

        val rejected = assertInstanceOf(HostedReadProjectInputResult.Rejected::class.java, result)
        assertEquals(
            HostedReadProjectInputFailure.DuplicateArtifactName("shared.jar"),
            rejected.failure,
        )
    }

    @Test
    fun `duplicate observed project artifact name is rejected`(@TempDir temporaryDirectory: Path) {
        val result =
            loadHostedReadProjectInputs(
                identities = listOf(":kernel|shared.jar"),
                files = duplicateNamedFiles(temporaryDirectory),
            )

        val rejected = assertInstanceOf(HostedReadProjectInputResult.Rejected::class.java, result)
        assertEquals(
            HostedReadProjectInputFailure.DuplicateArtifactName("shared.jar"),
            rejected.failure,
        )
    }

    @Test
    fun `duplicate declared external artifact name is rejected`() {
        val result =
            loadHostedReadExternalInputs(
                identities =
                    listOf(
                        "example:first:1|shared.jar",
                        "example:second:1|shared.jar",
                    ),
                files = emptySet(),
            )

        val rejected = assertInstanceOf(HostedReadExternalInputResult.Rejected::class.java, result)
        assertEquals(
            HostedReadExternalInputFailure.DuplicateArtifactName("shared.jar"),
            rejected.failure,
        )
    }

    @Test
    fun `duplicate observed external artifact name is rejected`(@TempDir temporaryDirectory: Path) {
        val result =
            loadHostedReadExternalInputs(
                identities = listOf("example:first:1|shared.jar"),
                files = duplicateNamedFiles(temporaryDirectory),
            )

        val rejected = assertInstanceOf(HostedReadExternalInputResult.Rejected::class.java, result)
        assertEquals(
            HostedReadExternalInputFailure.DuplicateArtifactName("shared.jar"),
            rejected.failure,
        )
    }

    private fun duplicateNamedFiles(temporaryDirectory: Path): Set<File> =
        setOf(
            createArtifact(temporaryDirectory.resolve("first/shared.jar")),
            createArtifact(temporaryDirectory.resolve("second/shared.jar")),
        )

    private fun createArtifact(path: Path): File {
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf())
        return path.toFile()
    }
}
