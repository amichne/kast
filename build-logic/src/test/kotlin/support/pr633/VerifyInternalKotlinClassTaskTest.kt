package support.pr633

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class VerifyInternalKotlinClassTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `matching internal source and compiled metadata pass`() {
        val task = configuredTask(
            simpleName = "InternalGraphIndexFixture",
            declaration = "internal class InternalGraphIndexFixture",
            classRoots = listOf(testClassesDirectory("InternalGraphIndexFixture")),
        )

        assertDoesNotThrow(task::verify)
    }

    @Test
    fun `public visibility mutation is rejected despite the same JVM access`() {
        val task = configuredTask(
            simpleName = "PublicGraphIndexFixture",
            declaration = "class PublicGraphIndexFixture",
            classRoots = listOf(testClassesDirectory("PublicGraphIndexFixture")),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("not INTERNAL"))
    }

    @Test
    fun `matching source without compiled class is rejected`() {
        val task = configuredTask(
            simpleName = "MissingGraphIndexFixture",
            declaration = "internal class MissingGraphIndexFixture",
            classRoots = listOf(temporaryDirectory.resolve("empty-classes")),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("compiled class"))
    }

    @Test
    fun `duplicate compiled identity across output roots is rejected`() {
        val simpleName = "InternalGraphIndexFixture"
        val compiledRoot = testClassesDirectory(simpleName)
        val relative = Path.of("support/pr633/$simpleName.class")
        val duplicateRoot = temporaryDirectory.resolve("duplicate-classes")
        Files.createDirectories(duplicateRoot.resolve(relative).parent)
        Files.copy(compiledRoot.resolve(relative), duplicateRoot.resolve(relative))
        val task = configuredTask(
            simpleName = simpleName,
            declaration = "internal class $simpleName",
            classRoots = listOf(compiledRoot, duplicateRoot),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("duplicated"))
    }

    @Test
    fun `wrong typed Kotlin metadata field is rejected rather than defaulted`() {
        val simpleName = "InternalGraphIndexFixture"
        val task = configuredTask(
            simpleName = simpleName,
            declaration = "internal class $simpleName",
            classRoots = listOf(mutatedMetadataRoot(simpleName, MetadataMutation.KIND_AS_STRING)),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("field 'k' expected INTEGER"))
    }

    @Test
    fun `enum Kotlin metadata field is captured and rejected`() {
        val simpleName = "InternalGraphIndexFixture"
        val task = configuredTask(
            simpleName = simpleName,
            declaration = "internal class $simpleName",
            classRoots = listOf(mutatedMetadataRoot(simpleName, MetadataMutation.KIND_AS_ENUM)),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("unsupported enum value"))
    }

    @Test
    fun `annotation inside Kotlin metadata array is captured and rejected`() {
        val simpleName = "InternalGraphIndexFixture"
        val task = configuredTask(
            simpleName = simpleName,
            declaration = "internal class $simpleName",
            classRoots = listOf(
                mutatedMetadataRoot(simpleName, MetadataMutation.DATA1_ELEMENT_AS_ANNOTATION),
            ),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("unsupported annotation value"))
    }

    @Test
    fun `nested array inside Kotlin metadata array is captured and rejected`() {
        val simpleName = "InternalGraphIndexFixture"
        val task = configuredTask(
            simpleName = simpleName,
            declaration = "internal class $simpleName",
            classRoots = listOf(
                mutatedMetadataRoot(simpleName, MetadataMutation.DATA1_ELEMENT_AS_ARRAY),
            ),
        )

        val failure = assertThrows<IllegalStateException>(task::verify)

        assertTrue(failure.message.orEmpty().contains("unsupported nested array value"))
    }

    private fun configuredTask(
        simpleName: String,
        declaration: String,
        classRoots: List<Path>,
    ): VerifyInternalKotlinClassTask {
        val sourceFile = temporaryDirectory.resolve("$simpleName.kt")
        Files.writeString(sourceFile, "package support.pr633\n\n$declaration\n")
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        return project.tasks.register(
            "verify${simpleName}InternalUnderTest",
            VerifyInternalKotlinClassTask::class.java,
        ).get().apply {
            this.sourceFile.set(sourceFile.toFile())
            classDirectories.from(classRoots)
            expectedPackageName.set("support.pr633")
            expectedSimpleClassName.set(simpleName)
            reportFile.set(temporaryDirectory.resolve("$simpleName-report.txt").toFile())
        }
    }

    private fun testClassesDirectory(simpleName: String): Path {
        val relative = "support/pr633/$simpleName.class"
        val classFile = Path.of(
            URI(requireNotNull(javaClass.classLoader.getResource(relative)).toString()),
        )
        return relative.split('/').fold(classFile) { path, _ -> path.parent }
    }

    private fun mutatedMetadataRoot(
        simpleName: String,
        mutation: MetadataMutation,
    ): Path {
        val relative = Path.of("support/pr633/$simpleName.class")
        val original = testClassesDirectory(simpleName).resolve(relative)
        val reader = ClassReader(Files.readAllBytes(original))
        val writer = ClassWriter(reader, 0)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    val delegate = super.visitAnnotation(descriptor, visible)
                    return if (descriptor == "Lkotlin/Metadata;") {
                        object : AnnotationVisitor(Opcodes.ASM9, delegate) {
                            override fun visit(name: String, value: Any) {
                                when {
                                    name != "k" -> super.visit(name, value)
                                    mutation == MetadataMutation.KIND_AS_STRING ->
                                        super.visit(name, "not-an-integer")
                                    mutation == MetadataMutation.KIND_AS_ENUM ->
                                        super.visitEnum(name, "Lsupport/pr633/Fake;", "VALUE")
                                    else -> super.visit(name, value)
                                }
                            }

                            override fun visitArray(name: String): AnnotationVisitor {
                                val array = super.visitArray(name)
                                if (name != "d1" || !mutation.replacesData1Element) {
                                    return array
                                }
                                return object : AnnotationVisitor(Opcodes.ASM9, array) {
                                    private var first = true

                                    override fun visit(ignored: String?, value: Any) {
                                        if (first) {
                                            first = false
                                            when (mutation) {
                                                MetadataMutation.DATA1_ELEMENT_AS_ANNOTATION ->
                                                    super.visitAnnotation(
                                                        ignored,
                                                        "Lsupport/pr633/Fake;",
                                                    )?.visitEnd()
                                                MetadataMutation.DATA1_ELEMENT_AS_ARRAY ->
                                                    super.visitArray(ignored)?.apply {
                                                        visit(null, "nested")
                                                        visitEnd()
                                                    }
                                                else -> error("mutation does not replace d1")
                                            }
                                        } else {
                                            super.visit(ignored, value)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        delegate
                    }
                }
            },
            0,
        )
        val root = temporaryDirectory.resolve("wrong-metadata-${mutation.name.lowercase()}")
        val output = root.resolve(relative)
        Files.createDirectories(output.parent)
        Files.write(output, writer.toByteArray())
        return root
    }

    private enum class MetadataMutation {
        KIND_AS_STRING,
        KIND_AS_ENUM,
        DATA1_ELEMENT_AS_ANNOTATION,
        DATA1_ELEMENT_AS_ARRAY,
        ;

        val replacesData1Element: Boolean
            get() = this == DATA1_ELEMENT_AS_ANNOTATION || this == DATA1_ELEMENT_AS_ARRAY
    }
}
