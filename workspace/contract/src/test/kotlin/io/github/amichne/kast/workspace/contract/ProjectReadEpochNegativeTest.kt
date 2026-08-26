package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.nio.file.Files
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProjectReadEpochNegativeTest {
    @Test
    fun `malformed epochs cannot be constructed copied parsed or unpacked`() {
        assertTrue(
            ProjectReadEpoch::class.java.declaredConstructors
                .filterNot { constructor -> constructor.isSynthetic }
                .all { constructor -> Modifier.isPrivate(constructor.modifiers) },
        )
        assertEquals(
            setOf("relationTo"),
            ProjectReadEpoch::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
                .mapTo(linkedSetOf()) { method -> method.name },
        )
        assertTrue(
            ProjectReadEpoch::class.java.declaredFields.all { field ->
                Modifier.isPrivate(field.modifiers)
            },
        )
        assertFalse(
            ProjectReadEpoch::class.java.declaredMethods.any { method ->
                method.name == "copy" || method.name.startsWith("component") ||
                    method.name == "parse" || method.name == "isValid"
            },
        )
    }

    @Test
    fun `rejected state cannot manufacture an observed epoch`() {
        val source = FixtureEpochSource(FixtureEpochState.stable()).apply {
            result = Refinement.Rejected(ProjectReadEpochObservationFailure.GradleRootMalformed)
        }

        val rejected = assertInstanceOf(
            ProjectReadEpochObservation.Rejected::class.java,
            source.observe(),
        )

        assertEquals(ProjectReadEpochObservationFailure.GradleRootMalformed, rejected.failure)
    }

    @Test
    fun `comparison consumes proof without repeating platform observation`() {
        val source = FixtureEpochSource(FixtureEpochState.stable())
        val before = source.observeEpoch()
        val after = source.observeEpoch()

        repeat(100) {
            assertEquals(ProjectReadEpochRelation.SAME, before.relationTo(after))
        }

        assertEquals(2, source.observationCount)
    }

    @Test
    fun `an unrelated source cannot recreate a comparable epoch`() {
        val state = FixtureEpochState.stable()
        val admitted = FixtureEpochSource(state).observeEpoch()
        val recreated = FixtureEpochSource(state).observeEpoch()

        assertEquals(ProjectReadEpochRelation.INCOMPARABLE, admitted.relationTo(recreated))
    }

    @Test
    fun `public epoch contract exposes no primitive counter or source authority`() {
        ProjectReadEpoch::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) }
            .forEach { method ->
                assertFalse(method.returnType.isPrimitive, method.toGenericString())
                assertFalse(method.returnType.isArray, method.toGenericString())
                assertFalse(
                    ProjectReadEpoch.Source::class.java.isAssignableFrom(method.returnType),
                    method.toGenericString(),
                )
            }
        assertTrue(
            ProjectReadEpoch.Source::class.java.declaredConstructors
                .filterNot { constructor -> constructor.isSynthetic }
                .all { constructor -> Modifier.isPrivate(constructor.modifiers) },
        )
        assertTrue(
            ProjectReadEpoch.Source::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .all { method -> method.isSynthetic },
        )
    }

    @Test
    fun `detached epochs retain no source callback`() {
        val epoch = FixtureEpochSource(FixtureEpochState.stable()).observeEpoch()
        val domainField = ProjectReadEpoch::class.java.declaredFields.single { field ->
            field.name == "comparisonDomain"
        }.apply { isAccessible = true }

        assertTrue(domainField.get(epoch).javaClass.declaredFields.isEmpty())
        assertTrue(ProjectReadEpoch::class.java.declaredFields.none { field ->
            Function::class.java.isAssignableFrom(field.type) ||
                ProjectReadEpoch.Source::class.java.isAssignableFrom(field.type)
        })
    }

    @Test
    fun `non-friend Kotlin callers cannot create an epoch source`(@TempDir directory: java.nio.file.Path) {
        val source = directory.resolve("ForgeEpoch.kt")
        Files.writeString(source, """
            import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
            fun forge() = ProjectReadEpoch.Source.create<Any> { error("forged") }
        """.trimIndent())
        val output = ByteArrayOutputStream()
        val exit = PrintStream(output).use { stream ->
            K2JVMCompiler().exec(
                stream,
                "-classpath", System.getProperty("java.class.path"),
                "-d", directory.resolve("classes").toString(),
                source.toString(),
            )
        }
        val diagnostics = output.toString(Charsets.UTF_8)

        assertEquals(ExitCode.COMPILATION_ERROR, exit, diagnostics)
        assertTrue("cannot access" in diagnostics, diagnostics)
        assertTrue("Source" in diagnostics || "create" in diagnostics, diagnostics)
    }
}
