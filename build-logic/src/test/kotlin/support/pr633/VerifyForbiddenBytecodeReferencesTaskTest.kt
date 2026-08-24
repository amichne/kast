import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import support.pr633.BytecodeAuthorityVerification
import support.pr633.BytecodeAuthorityViolation
import support.pr633.JvmInternalName
import support.pr633.VerifyForbiddenBytecodeReferencesTask
import java.nio.file.Files
import java.nio.file.Path

class VerifyForbiddenBytecodeReferencesTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `constructor references to forbidden owners are rejected`() {
        writeClass(
            owner = CALLER,
            body = {
                visitMethod(Opcodes.ACC_PUBLIC, "construct", "()V", null, null).apply {
                    visitCode()
                    visitTypeInsn(Opcodes.NEW, FORBIDDEN)
                    visitInsn(Opcodes.DUP)
                    visitMethodInsn(Opcodes.INVOKESPECIAL, FORBIDDEN, "<init>", "()V", false)
                    visitInsn(Opcodes.POP)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(2, 1)
                    visitEnd()
                }
            },
        )

        val failure = assertThrows<IllegalStateException> { configuredTask().verify() }

        assertTrue(failure.message.orEmpty().contains("$CALLER#construct -> $FORBIDDEN"))
    }

    @Test
    fun `field and method return descriptors to forbidden owners are rejected`() {
        writeClass(
            owner = CALLER,
            body = {
                visitField(Opcodes.ACC_PRIVATE, "authority", "L$FORBIDDEN;", null, null).visitEnd()
                visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                    "authority",
                    "()L$FORBIDDEN;",
                    null,
                    null,
                ).visitEnd()
            },
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
        )

        val failure = assertThrows<IllegalStateException> { configuredTask().verify() }
        val message = failure.message.orEmpty()

        assertTrue(message.contains("$CALLER#field authority -> $FORBIDDEN"))
        assertTrue(message.contains("$CALLER#method authority descriptor -> $FORBIDDEN"))
    }

    @Test
    fun `forbidden authority reached through a helper is rejected`() {
        writeClass(
            owner = CALLER,
            body = {
                staticVoidMethod("call") {
                    visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "call", "()V", false)
                }
            },
        )
        writeClass(
            owner = HELPER,
            body = {
                staticVoidMethod("call") {
                    visitMethodInsn(Opcodes.INVOKESTATIC, FORBIDDEN, "publish", "()V", false)
                }
            },
        )

        val failure = assertThrows<IllegalStateException> { configuredTask().verify() }
        val message = failure.message.orEmpty()

        assertTrue(message.contains(CALLER))
        assertTrue(message.contains(HELPER))
        assertTrue(message.contains(FORBIDDEN))
    }

    @Test
    fun `inner owner after a generic argument is resolved against the outer owner`() {
        writeClass(
            owner = CALLER,
            body = {
                visitField(
                    Opcodes.ACC_PRIVATE,
                    "nested",
                    "Ljava/lang/Object;",
                    "Lfixture/Outer<Lfixture/Argument;>.Forbidden;",
                    null,
                ).visitEnd()
            },
        )

        val failure = assertThrows<IllegalStateException> {
            configuredTask(forbiddenOwnerPrefixes = listOf(NESTED_FORBIDDEN)).verify()
        }

        assertTrue(failure.message.orEmpty().contains("$CALLER#field nested signature -> $NESTED_FORBIDDEN"))
    }

    @Test
    fun `unreachable forbidden reference in an unrelated class is ignored`() {
        writeClass(owner = CALLER) { staticVoidMethod("safe") {} }
        writeClass(
            owner = "fixture/Unrelated",
            body = {
                staticVoidMethod("unsafe") {
                    visitMethodInsn(Opcodes.INVOKESTATIC, FORBIDDEN, "publish", "()V", false)
                }
            },
        )

        assertDoesNotThrow { configuredTask().verify() }
    }

    @Test
    fun `rejected authority evidence preserves typed identity and is non-empty`() {
        val violation = BytecodeAuthorityViolation.DuplicateClass(JvmInternalName.fromClassfile(CALLER))
        val edge = support.pr633.BytecodeReference(
            JvmInternalName.fromClassfile(CALLER),
            "call",
            JvmInternalName.fromClassfile(FORBIDDEN),
        )
        val forbidden = BytecodeAuthorityViolation.ForbiddenReference(
            JvmInternalName.fromClassfile(CALLER),
            edge,
            emptyList(),
        )

        val rejection = BytecodeAuthorityVerification.Rejected(violation, emptyList())

        assertTrue(rejection.firstViolation is BytecodeAuthorityViolation.DuplicateClass)
        assertTrue(rejection.violations == listOf(violation))
        assertTrue(violation.display().contains(CALLER))
        assertTrue(forbidden.path == listOf(edge))
    }

    @Test
    fun `non-canonical forbidden owner prefix is rejected`() {
        writeClass(owner = CALLER) { staticVoidMethod("safe") {} }

        val failure = assertThrows<GradleException> {
            configuredTask(forbiddenOwnerPrefixes = listOf(" fixture/forbidden/")).verify()
        }

        assertTrue(failure.message.orEmpty().contains("NON_CANONICAL_FORBIDDEN_OWNER"))
    }

    @Test
    fun `malformed JVM owner prefixes are rejected`() {
        writeClass(owner = CALLER) { staticVoidMethod("safe") {} }

        val callerFailure = assertThrows<GradleException> {
            configuredTask(callerPrefixes = listOf("Lfixture/Caller;"),).verify()
        }
        val ownerFailure = assertThrows<GradleException> {
            configuredTask(forbiddenOwnerPrefixes = listOf("fixture/not/a prefix?"),).verify()
        }

        assertTrue(callerFailure.message.orEmpty().contains("INVALID_CALLER"))
        assertTrue(ownerFailure.message.orEmpty().contains("INVALID_FORBIDDEN_OWNER"))
    }

    private fun configuredTask(
        callerPrefixes: List<String> = listOf(CALLER),
        forbiddenOwnerPrefixes: List<String> = listOf("fixture/forbidden/"),
    ): VerifyForbiddenBytecodeReferencesTask {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        return project.tasks.register(
            "verifyForbiddenReferencesUnderTest",
            VerifyForbiddenBytecodeReferencesTask::class.java,
        ).get().apply {
            classDirectories.from(classesDirectory())
            callerInternalNamePrefixes.set(callerPrefixes)
            this.forbiddenOwnerPrefixes.set(forbiddenOwnerPrefixes)
            ruleName.set("fixture rule")
        }
    }

    private fun writeClass(
        owner: String,
        access: Int = Opcodes.ACC_PUBLIC,
        body: ClassWriter.() -> Unit,
    ) {
        val bytecode = ClassWriter(0).apply {
            visit(Opcodes.V17, access, owner, null, "java/lang/Object", null)
            body()
            visitEnd()
        }.toByteArray()
        val target = classesDirectory().resolve("$owner.class")
        Files.createDirectories(target.parent)
        Files.write(target, bytecode)
    }

    private fun ClassWriter.staticVoidMethod(
        name: String,
        body: MethodVisitor.() -> Unit,
    ) {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()V", null, null).apply {
            visitCode()
            body()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun classesDirectory(): Path = temporaryDirectory.resolve("classes")

    private companion object {
        const val CALLER = "fixture/Caller"
        const val HELPER = "fixture/Helper"
        const val FORBIDDEN = "fixture/forbidden/Authority"
        const val NESTED_FORBIDDEN = "fixture/Outer\$Forbidden"
    }
}
