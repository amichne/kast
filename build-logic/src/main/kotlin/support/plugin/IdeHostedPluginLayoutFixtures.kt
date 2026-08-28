package support.plugin

import java.io.ByteArrayOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

internal class IdeHostedNegativeFixture(
    val expected: IdePluginLayoutFailure,
    archive: ByteArray,
) {
    private val content = IdeHostedArchiveContent.snapshot(archive)

    /**
     * Proof transition: fixed fixture `IdeHostedNegativeFixture -> VerifiedIdePluginLayout`.
     *
     * Establishes the same complete layout scan as the repository archive boundary. Expected
     * rejection is the finite [IdePluginLayoutFailure]; fixture bytes remain encapsulated.
     */
    internal fun inspect(): IdePluginLayoutResult = admitIdePluginLayoutFixture(content)
}

/**
 * Proof transition: fixed KVP-011 fixture definitions `Unit -> List<IdeHostedNegativeFixture>`.
 *
 * Establishes one deterministic archive for each required forbidden-definition category and the
 * process-launch reference category. This transition has no expected failure. Fixture bytes are
 * exposed only to the negative Gradle proof task.
 */
internal fun ideHostedNegativeCases(): List<IdeHostedNegativeFixture> {
    val definitions = listOf(
        IdePluginLayoutFailure.INTELLIJ_PLATFORM_CLASS to "com/intellij/idea/Main",
        IdePluginLayoutFailure.KOTLIN_PLATFORM_CLASS to "org/jetbrains/kotlin/idea/KotlinPlugin",
        IdePluginLayoutFailure.GRADLE_PLATFORM_CLASS to "org/gradle/launcher/GradleMain",
        IdePluginLayoutFailure.JBR_CLASS to "sun/jvmstat/monitor/Monitor",
        IdePluginLayoutFailure.BOOTSTRAP_CLASS to
            "io/github/amichne/kast/indexer/KastIndexerMain",
        IdePluginLayoutFailure.MUTATION_CLASS to
            "io/github/amichne/kast/change/apply/ChangeWriter",
        IdePluginLayoutFailure.TOPOLOGY_CLASS to
            "io/github/amichne/kast/topology/build/TopologyBuilder",
        IdePluginLayoutFailure.JDBC_CLASS to "org/sqlite/JDBC",
        IdePluginLayoutFailure.RUNTIME_ACQUISITION_CLASS to
            "io/github/amichne/kast/distribution/managed/RuntimeStore",
    ).map { (failure, owner) -> IdeHostedNegativeFixture(failure, fixtureArchive(owner)) }
    return definitions + IdeHostedNegativeFixture(
        IdePluginLayoutFailure.PROCESS_LAUNCH_REFERENCE,
        fixtureArchive("fixture/ProcessLauncher", "java/lang/ProcessBuilder"),
    )
}

private fun fixtureArchive(owner: String, invokedOwner: String = "java/lang/Object"): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
    val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "exercise", "()V", null, null)
    method.visitCode()
    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, invokedOwner, "start", "()V", false)
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(1, 1)
    method.visitEnd()
    writer.visitEnd()
    val jarBytes = ByteArrayOutputStream().use { bytes ->
        JarOutputStream(bytes).use { jar ->
            jar.putNextEntry(JarEntry("$owner.class"))
            jar.write(writer.toByteArray())
            jar.closeEntry()
        }
        bytes.toByteArray()
    }
    return ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(
                ZipEntry("${IDE_HOSTED_PLUGIN_ROOT.value}fixture.jar").apply { time = 0L },
            )
            zip.write(jarBytes)
            zip.closeEntry()
        }
        bytes.toByteArray()
    }
}
