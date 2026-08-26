package support.architecture

import org.junit.jupiter.api.assertInstanceOf
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.CRC32

internal fun fixtureProjectClasspath(
    module: ValidatedModulePolicy,
): HostedReadProjectClasspath {
    val jars = listOf(
        ModuleId.KERNEL to "kernel.jar",
        ModuleId.PROTOCOL_CONTRACT to "protocol-contract.jar",
        ModuleId.WORKSPACE_CONTRACT to "workspace-contract.jar",
    ).mapIndexed { index, (project, artifactName) ->
        HostedReadProjectJarBytes.capture(
            project.projectPath,
            artifactName,
            fixtureJar("fixture/project/Project$index"),
        )
    }
    return assertInstanceOf<HostedReadProjectClasspathRefinement.Admitted>(
        HostedReadProjectClasspath.refine(
            module,
            jars,
            setOf(ModuleId.KERNEL, ModuleId.PROTOCOL_CONTRACT, ModuleId.WORKSPACE_CONTRACT),
        ),
    ).classpath
}

internal fun fixtureJar(className: String): ByteArray {
    val classBytes = ClassWriter(0).apply {
        visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            className,
            null,
            "java/lang/Object",
            null,
        )
        visitEnd()
    }.toByteArray()
    return fixtureJar(className, classBytes)
}

internal fun fixtureJarWithCall(className: String, target: JvmMember): ByteArray {
    val classBytes = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "invoke", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                target.owner.internalName,
                target.name.value,
                target.descriptor.value,
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
    return fixtureJar(className, classBytes)
}

private fun fixtureJar(className: String, classBytes: ByteArray): ByteArray {
    val crc = CRC32().apply { update(classBytes) }.value
    return ByteArrayOutputStream().also { output ->
        JarOutputStream(output).use { jar ->
            jar.putNextEntry(JarEntry("$className.class").apply {
                method = JarEntry.STORED
                size = classBytes.size.toLong()
                compressedSize = size
                this.crc = crc
                time = 0L
            })
            jar.write(classBytes)
            jar.closeEntry()
        }
    }.toByteArray()
}
