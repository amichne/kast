package io.github.amichne.kast.idea.transition

import com.intellij.compiler.CompilerConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.LanguageLevelUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LanguageLevelModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import org.jetbrains.jps.model.java.compiler.ProcessorConfigProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class IdeaJavaSemanticEnvironmentIdentityResolverTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    private val moduleFixture = projectFixture.moduleFixture("main")

    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `effective Java language level affects semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val original = moduleLanguageLevel(module)
        val changed = if (LanguageLevelUtil.getEffectiveLanguageLevel(module) == LanguageLevel.JDK_21) {
            LanguageLevel.JDK_17
        } else {
            LanguageLevel.JDK_21
        }
        val before = resolve(project)

        setModuleLanguageLevel(module, changed)
        try {
            assertNotEquals(before, resolve(project))
        } finally {
            setModuleLanguageLevel(module, original)
        }
    }

    @Test
    fun `module javac options affect semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val configuration = CompilerConfiguration.getInstance(project)
        val original = configuration.getAdditionalOptions(module)
        val before = resolve(project)

        updateCompilerConfiguration {
            configuration.setAdditionalOptions(module, original + "-Akast.semantic.option=changed")
        }
        try {
            assertNotEquals(before, resolve(project))
        } finally {
            updateCompilerConfiguration { configuration.setAdditionalOptions(module, original) }
        }
    }

    @Test
    fun `module Java bytecode target affects semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val configuration = CompilerConfiguration.getInstance(project)
        val original = configuration.getBytecodeTargetLevel(module)
        val changed = if (original == "21") "17" else "21"
        val before = resolve(project)

        updateCompilerConfiguration { configuration.setBytecodeTargetLevel(module, changed) }
        try {
            assertNotEquals(before, resolve(project))
        } finally {
            updateCompilerConfiguration { configuration.setBytecodeTargetLevel(module, original) }
        }
    }

    @Test
    fun `annotation processing configuration affects semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val configuration = CompilerConfiguration.getInstance(project)
        val before = resolve(project)
        lateinit var profile: ProcessorConfigProfile

        updateCompilerConfiguration {
            profile = configuration.addNewProcessorProfile("kast-semantic-identity-test")
            profile.setEnabled(true)
            profile.setProcessorPath(workspaceRoot.resolve("processors.jar").toString())
            profile.setUseProcessorModulePath(true)
            profile.setObtainProcessorsFromClasspath(false)
            profile.setGeneratedSourcesDirectoryName("generated/main", false)
            profile.setGeneratedSourcesDirectoryName("generated/test", true)
            profile.setOutputRelativeToContentRoot(true)
            profile.setProcOnly(true)
            profile.addProcessor("demo.KastProcessor")
            profile.setOption("mode", "semantic")
            profile.addModuleName(module.name)
        }
        try {
            assertNotEquals(before, resolve(project))
        } finally {
            updateCompilerConfiguration {
                profile.clearModuleNames()
                profile.clearProcessors()
                profile.clearProcessorOptions()
                profile.setEnabled(false)
                profile.setProcessorPath("")
            }
        }
    }

    @Test
    fun `annotation processor artifact content affects semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val processorPath = workspaceRoot.resolve("processors.jar")
        val configuration = CompilerConfiguration.getInstance(project)
        lateinit var profile: ProcessorConfigProfile
        Files.writeString(processorPath, "before")

        updateCompilerConfiguration {
            profile = configuration.addNewProcessorProfile("kast-processor-content-identity-test")
            profile.setEnabled(true)
            profile.setProcessorPath(processorPath.toString())
            profile.setObtainProcessorsFromClasspath(false)
            profile.addModuleName(module.name)
        }
        try {
            val before = resolve(project)
            Files.writeString(processorPath, "after")

            assertNotEquals(before, resolve(project))
        } finally {
            updateCompilerConfiguration {
                profile.clearModuleNames()
                profile.setEnabled(false)
                profile.setProcessorPath("")
            }
        }
    }

    @Test
    fun `unrelated module metadata does not affect semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val metadataKey = Key.create<String>("kast.test.display-metadata")
        val before = resolve(project)

        module.putUserData(metadataKey, "display-only")
        try {
            assertEquals(before, resolve(project))
        } finally {
            module.putUserData(metadataKey, null)
        }
    }

    private fun resolve(project: Project): String = IdeaSemanticEnvironmentIdentityResolver.resolve(
        project = project,
        workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
    )

    private fun moduleLanguageLevel(module: Module): LanguageLevel? =
        ModuleRootManager.getInstance(module)
            .getModuleExtension(LanguageLevelModuleExtension::class.java)
            .languageLevel

    private fun setModuleLanguageLevel(module: Module, languageLevel: LanguageLevel?) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                try {
                    model.getModuleExtension(LanguageLevelModuleExtension::class.java).languageLevel = languageLevel
                    model.commit()
                } catch (failure: Throwable) {
                    if (!model.isDisposed) model.dispose()
                    throw failure
                }
            }
        }
    }

    private fun updateCompilerConfiguration(action: () -> Unit) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction(action)
        }
    }
}
