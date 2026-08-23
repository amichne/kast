package support.pr633

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Rejects direct bytecode references from one declared caller namespace to forbidden owners.
 *
 * Use this for authority boundaries that share a composition module and therefore cannot be
 * expressed as a project dependency rule.
 */
@CacheableTask
abstract class VerifyForbiddenBytecodeReferencesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:Input
    abstract val callerInternalNamePrefixes: ListProperty<String>

    @get:Input
    abstract val forbiddenOwnerPrefixes: ListProperty<String>

    @get:Input
    abstract val ruleName: Property<String>

    @TaskAction
    fun verify() {
        val callers = callerInternalNamePrefixes.get()
        val forbidden = forbiddenOwnerPrefixes.get()
        val violations = mutableListOf<String>()

        classDirectories.asFileTree
            .matching { include("**/*.class") }
            .files
            .sortedBy { it.path }
            .forEach { classFile ->
                val reader = ClassReader(classFile.readBytes())
                if (callers.none(reader.className::startsWith)) return@forEach
                reader.accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitTypeInsn(opcode: Int, type: String) =
                                record(name, type)

                            override fun visitFieldInsn(
                                opcode: Int,
                                owner: String,
                                fieldName: String,
                                fieldDescriptor: String,
                            ) = record(name, owner)

                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                methodName: String,
                                methodDescriptor: String,
                                isInterface: Boolean,
                            ) = record(name, owner)

                            override fun visitLdcInsn(value: Any?) {
                                if (value is Type && value.sort == Type.OBJECT) {
                                    record(name, value.internalName)
                                }
                            }

                            override fun visitInvokeDynamicInsn(
                                name: String,
                                descriptor: String,
                                bootstrapMethodHandle: Handle,
                                vararg bootstrapMethodArguments: Any,
                            ) {
                                record(name, bootstrapMethodHandle.owner)
                                bootstrapMethodArguments.filterIsInstance<Handle>()
                                    .forEach { record(name, it.owner) }
                            }

                            private fun record(method: String, owner: String) {
                                if (forbidden.any(owner::startsWith)) {
                                    violations += "${reader.className}#$method -> $owner"
                                }
                            }
                        }
                    },
                    ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
            }

        check(violations.isEmpty()) {
            buildString {
                appendLine("${ruleName.get()} rejected forbidden bytecode references:")
                violations.distinct().sorted().forEach { appendLine("  $it") }
            }
        }
    }
}
