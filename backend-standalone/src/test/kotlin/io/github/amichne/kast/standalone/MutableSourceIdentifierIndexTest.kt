package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutableSourceIdentifierIndexTest {

    private fun emptyIndex(): MutableSourceIdentifierIndex =
        MutableSourceIdentifierIndex.fromCandidatePathsByIdentifier(emptyMap())

    @Test
    fun `candidatePathsForFqName filters by explicit import`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/a/Caller.kt",
            newContent = """
                package consumer
                
                import lib.Foo
                
                fun use() = Foo()
            """.trimIndent(),
        )
        index.updateFile(
            normalizedPath = "/project/src/b/Bystander.kt",
            newContent = """
                package bystander
                
                fun Foo() = "local shadow"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "Foo",
            targetPackage = "lib",
            targetFqName = "lib.Foo",
        )

        assertEquals(listOf("/project/src/a/Caller.kt"), candidates)
    }

    @Test
    fun `candidatePathsForFqName includes same-package files`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/lib/Foo.kt",
            newContent = """
                package lib
                
                class Foo
            """.trimIndent(),
        )
        index.updateFile(
            normalizedPath = "/project/src/lib/Bar.kt",
            newContent = """
                package lib
                
                fun useFoo() = Foo()
            """.trimIndent(),
        )
        index.updateFile(
            normalizedPath = "/project/src/other/Other.kt",
            newContent = """
                package other
                
                fun Foo() = "shadow"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "Foo",
            targetPackage = "lib",
            targetFqName = "lib.Foo",
        )

        assertEquals(
            listOf("/project/src/lib/Bar.kt", "/project/src/lib/Foo.kt"),
            candidates,
        )
    }

    @Test
    fun `candidatePathsForFqName includes wildcard import files`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/consumer/WildcardUser.kt",
            newContent = """
                package consumer
                
                import lib.*
                
                fun use() = Foo()
            """.trimIndent(),
        )
        index.updateFile(
            normalizedPath = "/project/src/other/NoImport.kt",
            newContent = """
                package other
                
                fun Foo() = "unrelated"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "Foo",
            targetPackage = "lib",
            targetFqName = "lib.Foo",
        )

        assertEquals(listOf("/project/src/consumer/WildcardUser.kt"), candidates)
    }

    @Test
    fun `candidatePathsForModule filters by allowed module names`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/lib/Library.kt",
            newContent = """
                package sample

                fun Target() = "lib"
            """.trimIndent(),
            moduleName = ModuleName(":lib[main]"),
        )
        index.updateFile(
            normalizedPath = "/project/src/app/Caller.kt",
            newContent = """
                package sample

                fun call() = Target()
            """.trimIndent(),
            moduleName = ModuleName(":app[main]"),
        )
        index.updateFile(
            normalizedPath = "/project/src/unrelated/Other.kt",
            newContent = """
                package sample

                fun other() = Target()
            """.trimIndent(),
            moduleName = ModuleName(":unrelated[main]"),
        )

        assertEquals(
            listOf("/project/src/app/Caller.kt"),
            index.candidatePathsForModule(
                identifier = "Target",
                allowedModuleNames = setOf(ModuleName(":app[main]")),
            ),
        )
    }

    @Test
    fun `candidatePathsForModule falls back when module metadata is incomplete`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/app/Caller.kt",
            newContent = """
                package sample

                fun call() = Target()
            """.trimIndent(),
            moduleName = ModuleName(":app[main]"),
        )
        index.updateFile(
            normalizedPath = "/project/src/lib/Library.kt",
            newContent = """
                package sample

                fun Target() = "lib"
            """.trimIndent(),
        )

        assertEquals(
            listOf("/project/src/app/Caller.kt", "/project/src/lib/Library.kt"),
            index.candidatePathsForModule(
                identifier = "Target",
                allowedModuleNames = setOf(ModuleName(":app[main]")),
            ),
        )
    }

    @Test
    fun `updateFile round-trips import metadata correctly`() {
        val index = emptyIndex()
        val path = "/project/src/File.kt"

        index.updateFile(
            normalizedPath = path,
            newContent = """
                package alpha
                
                fun something() = Unit
            """.trimIndent(),
        )

        // No import of beta.Target → not a candidate
        assertTrue(
            index.candidatePathsForFqName(
                identifier = "Target",
                targetPackage = "beta",
                targetFqName = "beta.Target",
            ).isEmpty(),
        )

        // Add the import
        index.updateFile(
            normalizedPath = path,
            newContent = """
                package alpha
                
                import beta.Target
                
                fun something() = Target()
            """.trimIndent(),
        )

        assertEquals(
            listOf(path),
            index.candidatePathsForFqName(
                identifier = "Target",
                targetPackage = "beta",
                targetFqName = "beta.Target",
            ),
        )
    }

    @Test
    fun `removeFile clears import and package metadata`() {
        val index = emptyIndex()
        val path = "/project/src/File.kt"

        index.updateFile(
            normalizedPath = path,
            newContent = """
                package lib
                
                import other.Foo
                
                fun use() = Foo()
            """.trimIndent(),
        )

        assertEquals(
            listOf(path),
            index.candidatePathsForFqName(
                identifier = "Foo",
                targetPackage = "other",
                targetFqName = "other.Foo",
            ),
        )

        index.removeFile(path)

        assertTrue(
            index.candidatePathsForFqName(
                identifier = "Foo",
                targetPackage = "other",
                targetFqName = "other.Foo",
            ).isEmpty(),
        )
    }

    @Test
    fun `candidatePathsForFqName matches ancestor import of containing class`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/consumer/Caller.kt",
            newContent = """
                package consumer

                import pkg.Foo

                fun use() = Foo.create()
            """.trimIndent(),
        )
        index.updateFile(
            normalizedPath = "/project/src/bystander/Bystander.kt",
            newContent = """
                package bystander

                fun create() = "local shadow"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "create",
            targetPackage = "pkg",
            targetFqName = "pkg.Foo.Companion.create",
        )

        assertEquals(listOf("/project/src/consumer/Caller.kt"), candidates)
    }

    @Test
    fun `candidatePathsForFqName matches wildcard import of ancestor package`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/consumer/WildcardCaller.kt",
            newContent = """
                package consumer

                import pkg.Foo.*

                fun use() = create()
            """.trimIndent(),
        )
        index.updateFile(
            normalizedPath = "/project/src/bystander/Bystander.kt",
            newContent = """
                package bystander

                fun create() = "unrelated"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "create",
            targetPackage = "pkg",
            targetFqName = "pkg.Foo.create",
        )

        assertEquals(listOf("/project/src/consumer/WildcardCaller.kt"), candidates)
    }

    @Test
    fun `candidatePathsForFqName matches deeply nested member via intermediate ancestor import`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/consumer/DeepCaller.kt",
            newContent = """
                package consumer

                import pkg.Outer

                fun use() = Outer.Inner.deepMethod()
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "deepMethod",
            targetPackage = "pkg",
            targetFqName = "pkg.Outer.Inner.deepMethod",
        )

        assertEquals(listOf("/project/src/consumer/DeepCaller.kt"), candidates)
    }

    @Test
    fun `candidatePathsForFqName does not produce ancestors below target package`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/consumer/Caller.kt",
            newContent = """
                package consumer

                import pkg

                fun use() = "something"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "create",
            targetPackage = "pkg",
            targetFqName = "pkg.Foo.Companion.create",
        )

        // import "pkg" alone should not match — only ancestors between package and FQ name
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `candidatePathsForFqName returns empty when identifier is absent from all files`() {
        val index = emptyIndex()
        index.updateFile(
            normalizedPath = "/project/src/File.kt",
            newContent = """
                package lib
                
                import other.Target
                
                fun use() = "no Target reference"
            """.trimIndent(),
        )

        val candidates = index.candidatePathsForFqName(
            identifier = "Missing",
            targetPackage = "other",
            targetFqName = "other.Missing",
        )

        assertTrue(candidates.isEmpty())
    }
}
