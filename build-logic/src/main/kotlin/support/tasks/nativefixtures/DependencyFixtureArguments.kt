package support.tasks.nativefixtures

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

abstract class DependencyFixtureArguments : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("-Dkast.declaredEffectArchive=${archives.singleFile.absolutePath}")
}
