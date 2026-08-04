package io.github.amichne.kast.idea.transition

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import io.github.amichne.kast.api.client.WorkspaceIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class IdeaSdkSemanticIdentityResolverTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    private val moduleFixture = projectFixture.moduleFixture("main")

    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `SDK presentation name does not affect semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val rootModel = ModuleRootManager.getInstance(module)
        val originalSdk = rootModel.sdk
        val originalSdkInherited = rootModel.isSdkInherited
        val sdkTable = ProjectJdkTable.getInstance(project)
        val classesRoot = workspaceRoot.resolve("jdk/classes").also(Files::createDirectories)
        val virtualClassesRoot = checkNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classesRoot),
        )
        val sdk = sdkTable.createSdk("temurin-21", sdkTable.defaultSdkType)
        val renamedSdk = sdkTable.createSdk("temurin-21 (2)", sdkTable.defaultSdkType)

        try {
            listOf(sdk, renamedSdk).forEach { candidate ->
                updateSdk(candidate) {
                    homePath = workspaceRoot.resolve("jdk").toString()
                    versionString = "21.0.7"
                    addRoot(virtualClassesRoot, OrderRootType.CLASSES)
                }
            }
            ApplicationManager.getApplication().runWriteAction {
                sdkTable.addJdk(sdk)
                sdkTable.addJdk(renamedSdk)
            }
            ModuleRootModificationUtil.setModuleSdk(module, sdk)
            assertEquals("temurin-21", ModuleRootManager.getInstance(module).sdk?.name)
            val before = resolve(project)

            ModuleRootModificationUtil.setModuleSdk(module, renamedSdk)
            assertEquals("temurin-21 (2)", ModuleRootManager.getInstance(module).sdk?.name)

            assertEquals(before, resolve(project))
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                if (originalSdkInherited) model.inheritSdk() else model.sdk = originalSdk
            }
            ApplicationManager.getApplication().runWriteAction {
                sdkTable.removeJdk(sdk)
                sdkTable.removeJdk(renamedSdk)
            }
        }
    }

    @Test
    fun `SDK home and version affect semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val rootModel = ModuleRootManager.getInstance(module)
        val originalSdk = rootModel.sdk
        val originalSdkInherited = rootModel.isSdkInherited
        val sdkTable = ProjectJdkTable.getInstance(project)
        val firstHome = workspaceRoot.resolve("jdk-a").also(Files::createDirectories)
        val secondHome = workspaceRoot.resolve("jdk-b").also(Files::createDirectories)
        val classesRoot = firstHome.resolve("classes").also(Files::createDirectories)
        val virtualClassesRoot = checkNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(classesRoot),
        )
        val sdk = sdkTable.createSdk("temurin-21", sdkTable.defaultSdkType)

        try {
            updateSdk(sdk) {
                homePath = firstHome.toString()
                versionString = "21.0.7"
                addRoot(virtualClassesRoot, OrderRootType.CLASSES)
            }
            ApplicationManager.getApplication().runWriteAction { sdkTable.addJdk(sdk) }
            ModuleRootModificationUtil.setModuleSdk(module, sdk)
            val original = resolve(project)

            updateSdk(sdk) { versionString = "21.0.8" }
            val changedVersion = resolve(project)

            updateSdk(sdk) {
                homePath = secondHome.toString()
                versionString = "21.0.7"
            }
            val changedHome = resolve(project)

            assertNotEquals(original, changedVersion)
            assertNotEquals(original, changedHome)
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                if (originalSdkInherited) model.inheritSdk() else model.sdk = originalSdk
            }
            ApplicationManager.getApplication().runWriteAction { sdkTable.removeJdk(sdk) }
        }
    }

    @Test
    fun `unresolved SDK binding affects semantic environment identity`() {
        val project = projectFixture.get()
        val module = moduleFixture.get()
        val rootModel = ModuleRootManager.getInstance(module)
        val originalSdk = rootModel.sdk
        val originalSdkInherited = rootModel.isSdkInherited

        try {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.setInvalidSdk("missing-temurin-21", "JavaSDK")
            }
            val firstName = resolve(project)

            ModuleRootModificationUtil.updateModel(module) { model ->
                model.setInvalidSdk("missing-zulu-21", "JavaSDK")
            }
            val secondName = resolve(project)

            ModuleRootModificationUtil.updateModel(module) { model ->
                model.setInvalidSdk("missing-zulu-21", "CustomSDK")
            }
            val secondType = resolve(project)

            assertNotEquals(firstName, secondName)
            assertNotEquals(secondName, secondType)
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                if (originalSdkInherited) model.inheritSdk() else model.sdk = originalSdk
            }
        }
    }

    private fun resolve(project: Project): String = IdeaSemanticEnvironmentIdentityResolver.resolve(
        project = project,
        workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
    )

    private fun updateSdk(sdk: Sdk, update: SdkModificator.() -> Unit) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                sdk.sdkModificator.apply(update).commitChanges()
            }
        }
    }
}
