package io.github.amichne.kast.shared.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PsiSourceIndexScannerTest {
    @Test
    fun `Kotlin PSI preserves canonical package evidence`() {
        listOf(
            KotlinPackageFixture("class Root", IndexedPackageEvidence.ProvenRoot),
            KotlinPackageFixture(
                "package com.example.`when`\nclass EscapedKeyword",
                IndexedPackageEvidence.ProvenNamed(canonicalName("com.example.when")),
            ),
            KotlinPackageFixture(
                "package com.example.`not-an-identifier`\nclass BacktickedName",
                IndexedPackageEvidence.ProvenNamed(canonicalName("com.example.not-an-identifier")),
            ),
            KotlinPackageFixture(
                "package café.日本\nclass UnicodeName",
                IndexedPackageEvidence.ProvenNamed(canonicalName("café.日本")),
            ),
        ).forEachIndexed { index, fixture ->
            val update = scannerFor(kotlinFile("Fixture$index.kt", fixture.source)).scanFile("Fixture$index.kt")

            assertEquals(fixture.expected, update?.packageEvidence)
        }
    }

    @Test
    fun `non Kotlin PSI cannot prove a package`() {
        val textFile = PsiFileFactory.getInstance(project).createFileFromText(
            "Fixture.txt",
            PlainTextLanguage.INSTANCE,
            "package parser.only\nclass NotKotlin",
        )

        val update = scannerFor(textFile).scanFile("Fixture.txt")

        assertEquals(
            IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_UNAVAILABLE),
            update?.packageEvidence,
        )
    }

    @Test
    fun `failed semantic package producer cannot prove the root package`() {
        val environment = TestReferenceIndexEnvironment(kotlinFile("Fixture.kt", "class Root"))
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = { null },
            packageEvidenceForFile = { error("semantic package analysis failed") },
        )

        val update = scanner.scanFile("Fixture.kt")

        assertEquals(
            IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_FAILED),
            update?.packageEvidence,
        )
    }

    @Test
    fun `semantic package cancellation remains cancellation`() {
        val cancellation = ProcessCanceledException()
        val environment = TestReferenceIndexEnvironment(kotlinFile("Fixture.kt", "class Root"))
        val scanner = PsiSourceIndexScanner(
            environment = environment,
            moduleNameForFile = { null },
            packageEvidenceForFile = { throw cancellation },
        )

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            scanner.scanFile("Fixture.kt")
        }

        assertSame(cancellation, thrown)
    }

    private fun scannerFor(psiFile: PsiFile): PsiSourceIndexScanner =
        PsiSourceIndexScanner(TestReferenceIndexEnvironment(psiFile))

    private fun kotlinFile(name: String, source: String): PsiFile = psiFactory.createFile(name, source)

    private fun canonicalName(raw: String): IndexedPackageEvidence.CanonicalName =
        IndexedPackageEvidence.CanonicalName.parse(raw)

    private data class KotlinPackageFixture(
        val source: String,
        val expected: IndexedPackageEvidence,
    )

    private class TestReferenceIndexEnvironment(
        private val psiFile: PsiFile,
    ) : ReferenceIndexEnvironment {
        override fun allFilePaths(): Collection<String> = listOf(psiFile.name)

        override fun findPsiFile(filePath: String): PsiFile = psiFile

        override fun <T> withReadAccess(action: () -> T): T = action()

        override fun <T> withExclusiveAccess(action: () -> T): T = action()

        override fun isCancelled(): Boolean = false
    }

    private companion object {
        val disposable = Disposer.newDisposable("PsiSourceIndexScannerTest")
        val project = KotlinCoreEnvironment.createForTests(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ).project
        val psiFactory = KtPsiFactory(project, markGenerated = false)

        @JvmStatic
        @AfterAll
        fun disposeEnvironment() {
            ApplicationManager.getApplication().runWriteAction {
                Disposer.dispose(disposable)
            }
        }
    }
}
