package support.pr633

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Rejects the zero-budget graph API inventory from compiled :topology:contract classes. */
@CacheableTask
abstract class VerifyTopologyContractApiTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenClassSimpleNames: SetProperty<String>

    @get:Input
    abstract val forbiddenPublicMethodNames: SetProperty<String>

    @TaskAction
    fun verify() {
        val forbiddenClasses = forbiddenClassSimpleNames.get()
        val forbiddenMethods = forbiddenPublicMethodNames.get()
        val violations = mutableListOf<String>()

        classDirectories.asFileTree
            .matching { include("**/*.class") }
            .files
            .sortedBy { it.path }
            .forEach { classFile ->
                val reader = ClassReader(classFile.readBytes())
                val simpleName = reader.className.substringAfterLast('/').substringBefore('$')
                if (simpleName in forbiddenClasses) {
                    violations += "class ${reader.className}"
                }
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            if (
                                access and Opcodes.ACC_PUBLIC != 0 &&
                                name in forbiddenMethods
                            ) {
                                violations += "method ${reader.className}#$name$descriptor"
                            }
                            return null
                        }
                    },
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
            }

        check(violations.isEmpty()) {
            buildString {
                appendLine(":topology:contract exposes zero-budget graph API:")
                violations.distinct().sorted().forEach { appendLine("  $it") }
            }
        }
    }
}
