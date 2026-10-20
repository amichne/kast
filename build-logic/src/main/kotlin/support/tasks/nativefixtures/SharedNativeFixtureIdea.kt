package support.tasks.nativefixtures

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

class NativeFixtureIdea internal constructor(
    val home: Provider<Directory>,
    val contents: FileCollection,
)

/** The address alone is not buildable: consumers depend on contents to retain the SDK producer. */
object SharedNativeFixtureIdea {
    fun select(project: Project): NativeFixtureIdea = with(project) {
        val consumerDirectory = layout.projectDirectory
        val suppliedHome = providers.gradleProperty("nativeFixtureIdeaHome")
        val home = suppliedHome.map(consumerDirectory::dir).orElse(
            project(":relation:intellij").layout.buildDirectory.dir("native-test/idea"),
        )
        val contents = files(home)
        if (!suppliedHome.isPresent) contents.builtBy(":relation:intellij:extractNativeTestIdea")
        NativeFixtureIdea(home, contents)
    }
}
