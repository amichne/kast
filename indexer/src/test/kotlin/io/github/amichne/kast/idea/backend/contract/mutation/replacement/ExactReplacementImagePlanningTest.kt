package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.ReplacementPlanQuery
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
internal class ExactReplacementImagePlanningTest : KastIndexerBackendContractTestFixture() {
    private val exactImageDeclaration =
        "fun exactReplacement(value: String): String = \"😀 ${'$'}value\""
    private val exactImageReplacementSource =
        "\uFEFFpackage demo.replacementimage\r\n\r\n$exactImageDeclaration\r\n"
    private val exactImageReplacementFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ExactImageReplacement.kt",
        exactImageReplacementSource,
    )

    @Test
    fun `replacement planning returns exact BOM CRLF and non-BMP byte images without writing`() = runBlocking {
        ensureProjectReady()
        val file = exactImageReplacementFileFixture.get()
        waitUntilIndexesAreReady(project)
        val filePath = Path.of(file.virtualFile.path)
        val before = Files.readAllBytes(filePath)
        assertArrayEquals(exactImageReplacementSource.toByteArray(), before)
        val target = readAction {
            SymbolIdentity(
                fqName = "demo.replacementimage.exactReplacement",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse(filePath.toString()),
                declarationStartOffset = NonNegativeInt(file.text.indexOf("exactReplacement")),
            )
        }
        val proposed = "fun exactReplacement(value: String): String = \"🙂 ${'$'}value\""

        val result = backend(
            workspaceRoot = commonWorkspaceRoot(filePath.toString(), hierarchyFile.virtualFile.path),
        ).planReplacement(ReplacementPlanQuery(target = target, proposedDeclaration = proposed))

        val image = result.fileImages.single()
        val expected = exactImageReplacementSource.replace(exactImageDeclaration, proposed).toByteArray()
        assertEquals(filePath.toString(), image.filePath.value)
        assertArrayEquals(before, image.preimage.copyBytes())
        assertArrayEquals(expected, image.postimage.copyBytes())
        assertEquals(FileHashing.sha256(before), result.proof.fileHashes.single().hash)
        assertArrayEquals(before, Files.readAllBytes(filePath), "replacement planning must not write")
    }
}
