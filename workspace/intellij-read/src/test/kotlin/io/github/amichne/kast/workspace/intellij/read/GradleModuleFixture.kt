package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManagerFactory
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.SourceFolder
import com.intellij.testFramework.LightVirtualFile
import java.lang.reflect.Proxy
import java.nio.file.Path
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRoot

/** Runs the real ExternalSystemApiUtil against the module's external-system service boundary. */
internal fun gradleModule(name: String, build: String?, project: String?, system: String = "GRADLE"): Module {
    val properties =
        object : ExternalSystemModulePropertyManager() {
            override fun getExternalSystemId() = system

            override fun getRootProjectPath() = build

            override fun getLinkedProjectPath() = project

            override fun getExternalModuleType(): String? = null

            override fun getExternalModuleVersion(): String? = null

            override fun getExternalModuleGroup(): String? = null

            override fun getLinkedProjectId(): String? = null

            override fun isMavenized() = false

            override fun setMavenized(mavenized: Boolean) = error("read only")

            override fun setMavenized(mavenized: Boolean, moduleVersion: String?) = error("read only")

            override fun swapStore() = error("read only")

            override fun unlinkExternalOptions() = error("read only")

            override fun setExternalOptions(id: ProjectSystemId, moduleData: ModuleData, projectData: ProjectData?) =
                error("read only")

            override fun setExternalId(id: ProjectSystemId) = error("read only")

            override fun setLinkedProjectId(id: String?) = error("read only")

            override fun setLinkedProjectPath(path: String?) = error("read only")

            override fun setRootProjectPath(path: String?) = error("read only")

            override fun setExternalModuleType(type: String?) = error("read only")
        }
    val factory =
        fixtureProxy<ExternalSystemModulePropertyManagerFactory> { method, _ ->
            check(method == "getService")
            properties
        }
    val host =
        fixtureProxy<Project> { method, args ->
            check(method == "getService" && args.single() == ExternalSystemModulePropertyManagerFactory::class.java)
            factory
        }
    return fixtureProxy { method, args ->
        when (method) {
            "getProject" -> host
            "getName" -> name
            "isDisposed" -> false
            "getService" ->
                if (args.single() == ExternalSystemModulePropertyManager::class.java) properties
                else error("Unexpected service ${args.single()}")
            else -> error("Unexpected module observation $method")
        }
    }
}

internal fun sourceFolder(
    path: Path,
    available: Boolean = true,
    resource: Boolean = false,
    mapping: () -> Path = { path },
): SourceFolder = fixtureProxy { method, _ ->
    when (method) {
        "getUrl" -> "file://$path"
        "getRootType" ->
            if (resource) org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE else JavaSourceRootType.SOURCE
        "getFile" ->
            if (available)
                object : LightVirtualFile(path.fileName.toString()) {
                    override fun toNioPath() = mapping()
                }
            else null
        "getJpsElement" -> sourceProperties()
        else -> error("Unexpected source-folder observation $method")
    }
}

internal inline fun <reified T> fixtureProxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.single()
            "toString" -> T::class.java.simpleName
            else -> call(method.name, args ?: emptyArray())
        }
    } as T

private fun sourceProperties(): JpsModuleSourceRoot = fixtureProxy { name, _ ->
    when (name) {
        "getProperties" -> JavaSourceRootProperties("", false)
        else -> error("Unexpected JPS observation $name")
    }
}

internal fun gradleProjectIndex(
    root: io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot,
    projects: List<org.jetbrains.plugins.gradle.model.DefaultExternalProject>,
): GradleBuildProjectIndex {
    val imported =
        org.jetbrains.plugins.gradle.model.DefaultExternalProject().apply {
            path = ":"
            projectDir = Path.of(root.value).toFile()
            childProjects = projects.mapIndexed { index, project -> "project$index" to project }.toMap()
        }
    return (GradleBuildProjectIndex.capture(root, imported) as io.github.amichne.kast.kernel.Refinement.Refined).value
}
