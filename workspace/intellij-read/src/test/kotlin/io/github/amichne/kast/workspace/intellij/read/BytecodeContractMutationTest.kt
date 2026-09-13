package io.github.amichne.kast.workspace.intellij.read

import java.lang.classfile.ClassFile
import java.lang.classfile.ClassTransform
import java.lang.classfile.attribute.SourceFileAttribute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal fun readContractResource(resource: String): ByteArray? =
    BytecodeContractMutationTest::class.java.classLoader.getResourceAsStream(resource)?.use { it.readAllBytes() }

internal class BytecodeContractMutationTest {
    @Test
    fun `detached contract accepts changed debug metadata`() {
        assertEquals(
            emptyList<DetachedModelClassContractFailure>(),
            DetachedModelClassContract.verify(::withoutSourceFile),
        )
    }

    @Test
    fun `epoch characterization accepts changed debug metadata`() {
        assertEquals(emptyList<EpochClassContractFailure>(), EpochSignalClassContract.verify(::withoutSourceFile))
    }

    @Test
    fun `production epoch accepts changed debug metadata`() {
        assertEquals(
            emptyList<EpochClassContractFailure>(),
            EpochSignalClassContract.verifyProductionEpoch(::withoutSourceFile),
        )
    }

    @Test
    fun `epoch listener accepts an ordinary JDK helper reference`() {
        val read = withProbe("RootFilteredProjectEpochVfsListener.class", "allowedHelper")
        assertEquals(emptyList<EpochClassContractFailure>(), EpochSignalClassContract.verifyProductionEpoch(read))
    }

    @Test
    fun `detached contract rejects an inserted blocking call`() {
        val read = withProbe("LiveDetachedModelCapture.class", "blockingCall")
        val failures = DetachedModelClassContract.verify(read)
        assertTrue(
            failures.any {
                it is DetachedModelClassContractFailure.ForbiddenMember &&
                    it.member.owner == "java/lang/Thread" &&
                    it.member.name == "sleep"
            }
        )
    }

    @Test
    fun `production epoch rejects an inserted blocking call`() {
        val read = withProbe("RootFilteredProjectEpochVfsListener.class", "blockingCall")
        val failures = EpochSignalClassContract.verifyProductionEpoch(read)
        assertTrue(
            failures.any {
                it is EpochClassContractFailure.ForbiddenMember &&
                    it.member.owner == "java/lang/Thread" &&
                    it.member.name == "sleep"
            }
        )
    }

    private fun withoutSourceFile(resource: String): ByteArray {
        val bytes = requireNotNull(readContractResource(resource))
        val classFile = ClassFile.of()
        val transformed =
            classFile.transformClass(classFile.parse(bytes), ClassTransform.dropping { it is SourceFileAttribute })
        assertFalse(bytes.contentEquals(transformed), "Fixture must change the class bytes: $resource")
        return transformed
    }

    private fun withProbe(target: String, methodName: String): (String) -> ByteArray? = { resource ->
        val bytes = readContractResource(resource)
        if (bytes == null || !resource.endsWith("/$target")) bytes
        else {
            val classFile = ClassFile.of()
            val probe =
                classFile
                    .parse(requireNotNull(readContractResource(LOCAL + "BytecodeContractProbe.class")))
                    .methods()
                    .single { it.methodName().stringValue() == methodName }
            classFile.transformClass(classFile.parse(bytes), ClassTransform.endHandler { it.with(probe) })
        }
    }

    private companion object {
        const val LOCAL = "io/github/amichne/kast/workspace/intellij/read/"
    }
}

/** Methods are copied into class-file fixtures and never executed. */
private class BytecodeContractProbe {
    fun allowedHelper(value: Int): Int = Math.abs(value)

    fun blockingCall() = Thread.sleep(0)
}
