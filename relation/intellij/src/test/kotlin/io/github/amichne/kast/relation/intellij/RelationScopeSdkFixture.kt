package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScopeBuilder
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals

/** SDK services provide only all/library scope observations; production owns every refinement decision. */
internal class RelationScopeSdkFixture(
    private val allContains: (VirtualFile) -> Boolean = { true },
    private val libraryContains: (VirtualFile) -> Boolean = { false },
    private val sourceContains: (VirtualFile) -> Boolean = { error("Unexpected source membership") },
) {
    private val data = UserDataHolderBase()
    private val builder: ProjectScopeBuilder
    val fileIndexCalls = mutableListOf<String>()
    private val index =
        Proxy.newProxyInstance(ProjectFileIndex::class.java.classLoader, arrayOf(ProjectFileIndex::class.java)) {
            _,
            method,
            args ->
            when (method.name) {
                "isInSourceContent" -> {
                    fileIndexCalls += "SOURCE"
                    sourceContains(args!![0] as VirtualFile)
                }
                "isExcluded" -> {
                    fileIndexCalls += "EXCLUDED"
                    false
                }
                else -> error("Unexpected file-index call: ${method.name}")
            }
        } as ProjectFileIndex
    private var allScopes = 0
    private var libraryScopes = 0
    @Suppress("UNCHECKED_CAST")
    val project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getService" -> {
                    when (args!![0]) {
                        ProjectScopeBuilder::class.java -> builder
                        ProjectFileIndex::class.java -> index
                        else -> error("Unexpected project service: ${args[0]}")
                    }
                }
                "getUserData" -> data.getUserData(args!![0] as Key<Any>)
                "putUserData" -> {
                    data.putUserData(args!![0] as Key<Any>, args[1])
                    null
                }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected project call: ${method.name}")
            }
        } as Project

    init {
        builder =
            object : ProjectScopeBuilder() {
                override fun buildAllScope(): GlobalSearchScope {
                    allScopes++
                    check(allScopes == 1)
                    return observedScope(true)
                }

                override fun buildLibrariesScope(): GlobalSearchScope {
                    libraryScopes++
                    check(libraryScopes == 1)
                    return observedScope(false)
                }

                override fun buildEverythingScope(): GlobalSearchScope = error("Unexpected everything scope")

                override fun buildProjectScope(): GlobalSearchScope = error("Unexpected project scope")

                override fun buildContentScope(): GlobalSearchScope = error("Unexpected content scope")
            }
    }

    private fun observedScope(all: Boolean) =
        object : GlobalSearchScope(project) {
            override fun contains(file: VirtualFile): Boolean = if (all) allContains(file) else libraryContains(file)

            override fun isSearchInModuleContent(module: Module): Boolean = all

            override fun isSearchInLibraries(): Boolean = !all
        }

    fun module(name: String, disposed: Boolean = false, project: Project = this.project): Module =
        Proxy.newProxyInstance(Module::class.java.classLoader, arrayOf(Module::class.java)) { proxy, method, args ->
            when (method.name) {
                "getName" -> name
                "getProject" -> project
                "isDisposed" -> disposed
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected module call: ${method.name}")
            }
        } as Module

    fun assertConsumed() {
        assertEquals(1, allScopes)
        assertEquals(1, libraryScopes)
    }
}
