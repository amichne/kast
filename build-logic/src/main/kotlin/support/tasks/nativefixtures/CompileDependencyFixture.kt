package support.tasks.nativefixtures

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** One immutable compiler input serves independent, case-owned archive copies. */
@DisableCachingByDefault(because = "Portable archive repeatability needs separate qualification.")
abstract class CompileDependencyFixture @Inject constructor(private val execution: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val sourceFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val equivalentSources: ConfigurableFileCollection
    @get:Classpath abstract val compilerClasspath: ConfigurableFileCollection
    @get:Classpath abstract val dependencies: ConfigurableFileCollection
    @get:Input abstract val compilerArguments: ListProperty<String>
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jdkRelease: RegularFileProperty
    @get:OutputFile abstract val archiveFile: RegularFileProperty

    init {
        compilerArguments.convention(listOf("-no-stdlib", "-no-reflect"))
        jdkRelease.convention(javaLauncher.map { it.metadata.installationPath.file("release") })
    }

    @TaskAction
    fun compile() {
        val archive = archiveFile.get().asFile.toPath()
        Files.deleteIfExists(archive)
        val source = sourceFile.get().asFile
        val bytes = source.readBytes()
        equivalentSources.forEach {
            if (!bytes.contentEquals(it.readBytes())) {
                throw GradleException("Dependency fixture sources differ: ${source.name} and $it")
            }
        }
        val staged = temporaryDir.resolve("effects.jar").toPath()
        Files.deleteIfExists(staged)
        val transcript = temporaryDir.resolve("compiler.txt")
        try {
            logger.lifecycle("Dependency fixture compiler invocation: $path")
            val result = transcript.outputStream().use { output ->
                execution.javaexec {
                    executable = javaLauncher.get().executablePath.asFile.absolutePath
                    classpath = compilerClasspath
                    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
                    args(compilerArguments.get())
                    args("-classpath", dependencies.asPath, "-d", staged.toString(), source.absolutePath)
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
            }
            if (result.exitValue != 0) {
                val detail = transcript.inputStream().use { it.readNBytes(65_536).toString(Charsets.UTF_8) }
                throw GradleException("Dependency fixture compiler failed with exit=${result.exitValue}: $detail")
            }
            ZipFile(staged.toFile()).use { zip ->
                if (zip.size() == 0) throw GradleException("Dependency fixture compiler produced an empty archive")
            }
            Files.createDirectories(archive.parent)
            Files.move(staged, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}
