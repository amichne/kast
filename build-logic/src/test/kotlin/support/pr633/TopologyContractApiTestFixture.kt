package support.pr633

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

internal class TopologyContractApiTestFixture(
    private val temporaryDirectory: Path,
) {
    fun configuredTask(
        vararg manifestEntries: String,
        forbiddenClasses: Set<String> = setOf("ForbiddenGraph"),
        forbiddenMethods: Set<String> = setOf("forbiddenTraversal"),
        classRoots: List<Path> = listOf(classesDirectory()),
    ): VerifyTopologyContractApiTask {
        val manifest = temporaryDirectory.resolve("topology-contract-api.txt")
        Files.write(manifest, manifestEntries.toList())
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        return project.tasks.register(
            "verifyTopologyContractApiUnderTest",
            VerifyTopologyContractApiTask::class.java,
        ).get().apply {
            classDirectories.from(classRoots)
            manifestFile.set(manifest.toFile())
            forbiddenClassSimpleNames.set(forbiddenClasses)
            forbiddenPublicMethodNames.set(forbiddenMethods)
        }
    }

    fun writeClass(
        owner: String,
        access: Int,
        signature: String? = null,
        methods: List<PublicMethod> = emptyList(),
        fields: List<PublicField> = emptyList(),
        root: Path = classesDirectory(),
        permittedSubclasses: List<String> = emptyList(),
        superclass: String = "java/lang/Object",
        recordComponents: List<PublicRecordComponent> = emptyList(),
        innerClass: PublicInnerClass? = null,
    ) {
        val bytecode = ClassWriter(0).apply {
            visit(Opcodes.V17, access, owner, signature, superclass, null)
            permittedSubclasses.forEach(::visitPermittedSubclass)
            innerClass?.let { nested ->
                visitInnerClass(owner, nested.outerName, nested.innerName, nested.access)
            }
            recordComponents.forEach { component ->
                visitRecordComponent(
                    component.name,
                    component.descriptor,
                    component.signature,
                ).visitEnd()
            }
            fields.forEach { field ->
                visitField(field.access, field.name, "I", null, field.constant).visitEnd()
            }
            methods.forEach { method ->
                visitMethod(method.access, method.name, "()V", null, null).visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val target = root.resolve("$owner.class")
        Files.createDirectories(target.parent)
        Files.write(target, bytecode)
    }

    fun compileJava(output: Path, source: String) {
        val sourceFile = temporaryDirectory.resolve("java-source/fixture/TopologyQuery.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, source)
        Files.createDirectories(output)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "test requires a JDK Java compiler"
        }
        assertEquals(
            0,
            compiler.run(null, null, null, "-d", output.toString(), sourceFile.toString()),
        )
    }

    fun classesDirectory(): Path = temporaryDirectory.resolve("classes")
}

internal data class PublicMethod(
    val name: String,
    val access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE,
)

internal data class PublicField(
    val name: String,
    val access: Int,
    val constant: Any? = null,
)

internal data class PublicRecordComponent(
    val name: String,
    val descriptor: String,
    val signature: String? = null,
)

internal data class PublicInnerClass(
    val outerName: String,
    val innerName: String,
    val access: Int,
)
