package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamWriter

internal const val INSTALLED_INDEX_BOOTSTRAP_MODULE_NAME = "kast-index-bootstrap"

private const val IDEA_DIRECTORY_NAME = ".idea"
private const val MODULE_FILE_NAME = "$INSTALLED_INDEX_BOOTSTRAP_MODULE_NAME.iml"
private const val MODULES_FILE_NAME = "modules.xml"

/** Directory names that have no source authority until the Gradle model proves otherwise. */
private enum class InstalledGeneratedDirectoryKind(
    val directoryName: String,
) {
    AGENT_EVIDENCE(".agent-turn"),
    AMBIENT_PROJECT_CONFIGURATION(".idea"),
    BUILD_OUTPUT("build"),
    GRADLE_CACHE(".gradle"),
    KOTLIN_CACHE(".kotlin"),
    NODE_DEPENDENCIES("node_modules"),
    VCS_METADATA(".git"),
    ;

    companion object {
        private val byDirectoryName = entries.associateBy(InstalledGeneratedDirectoryKind::directoryName)

        fun identify(path: Path): InstalledGeneratedDirectoryKind? {
            val identified = path.fileName?.toString()?.let(byDirectoryName::get)
            return when (identified) {
                BUILD_OUTPUT -> if (path.parent?.ownsConventionalGradleBuild() == true) {
                    identified
                } else {
                    null
                }
                else -> identified
            }
        }
    }
}

private fun Path.ownsConventionalGradleBuild(): Boolean = GRADLE_BUILD_OWNER_FILES.any { fileName ->
    Files.isRegularFile(resolve(fileName), LinkOption.NOFOLLOW_LINKS)
}

private val GRADLE_BUILD_OWNER_FILES = listOf(
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
)

/** A workspace-contained directory admitted for temporary pre-open exclusion. */
@JvmInline
internal value class InstalledIndexExcludedDirectory private constructor(
    val path: Path,
) {
    companion object {
        fun admit(workspaceRoot: Path, candidate: Path): InstalledIndexExcludedDirectory? {
            val normalized = candidate.toAbsolutePath().normalize()
            return if (
                normalized != workspaceRoot &&
                normalized.startsWith(workspaceRoot) &&
                InstalledGeneratedDirectoryKind.identify(normalized) != null
            ) {
                InstalledIndexExcludedDirectory(normalized)
            } else {
                null
            }
        }
    }
}

/** Complete deterministic pre-open content/exclusion model for one exact workspace. */
internal class InstalledIndexExclusionPlan private constructor(
    val workspaceRoot: Path,
    val excludedDirectories: List<InstalledIndexExcludedDirectory>,
) {
    companion object {
        fun discover(workspaceRoot: Path): InstalledIndexExclusionPlanDiscovery {
            val canonicalRoot = workspaceRoot.toAbsolutePath().normalize()
            val excluded = mutableListOf<InstalledIndexExcludedDirectory>()
            return try {
                Files.walkFileTree(
                    canonicalRoot,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            val admitted = InstalledIndexExcludedDirectory.admit(
                                canonicalRoot,
                                directory,
                            )
                            return if (admitted == null) {
                                FileVisitResult.CONTINUE
                            } else {
                                excluded += admitted
                                FileVisitResult.SKIP_SUBTREE
                            }
                        }

                        override fun visitFile(
                            file: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            if (attributes.isSymbolicLink) {
                                InstalledIndexExcludedDirectory.admit(canonicalRoot, file)
                                    ?.let(excluded::add)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exception: IOException,
                        ): FileVisitResult = throw exception

                        override fun postVisitDirectory(
                            directory: Path,
                            exception: IOException?,
                        ): FileVisitResult = if (exception == null) {
                            FileVisitResult.CONTINUE
                        } else {
                            throw exception
                        }
                    },
                )
                InstalledIndexExclusionPlanDiscovery.Discovered(
                    InstalledIndexExclusionPlan(
                        canonicalRoot,
                        excluded.sortedBy { directory -> directory.path.toString() },
                    ),
                )
            } catch (_: IOException) {
                InstalledIndexExclusionPlanDiscovery.Rejected(
                    InstalledIndexExclusionPlanFailure.DISCOVERY_FAILED,
                )
            } catch (_: SecurityException) {
                InstalledIndexExclusionPlanDiscovery.Rejected(
                    InstalledIndexExclusionPlanFailure.DISCOVERY_FAILED,
                )
            }
        }
    }
}

internal enum class InstalledIndexExclusionPlanFailure {
    DISCOVERY_FAILED,
}

internal sealed interface InstalledIndexExclusionPlanDiscovery {
    data class Discovered(
        val plan: InstalledIndexExclusionPlan,
    ) : InstalledIndexExclusionPlanDiscovery

    data class Rejected(
        val failure: InstalledIndexExclusionPlanFailure,
    ) : InstalledIndexExclusionPlanDiscovery
}

/** Runtime-owned generated project configuration installed before IntelliJ project open. */
internal class InstalledIndexBootstrap private constructor(
    val moduleName: String,
    val excludedDirectoryCount: Int,
    private val workspaceRootUrl: String,
    private val excludedDirectoryPaths: List<Path>,
    private val excludedDirectoryUrls: Set<String>,
) : InstalledIndexBootstrapBinder {
    companion object {
        fun prepare(
            projectStore: Path,
            plan: InstalledIndexExclusionPlan,
        ): InstalledIndexBootstrapPreparation = try {
            val ideaDirectory = Files.createDirectory(projectStore.resolve(IDEA_DIRECTORY_NAME))
            writeModulesXml(ideaDirectory.resolve(MODULES_FILE_NAME))
            writeModuleXml(ideaDirectory.resolve(MODULE_FILE_NAME), plan)
            InstalledIndexBootstrapPreparation.Prepared(
                InstalledIndexBootstrap(
                    moduleName = INSTALLED_INDEX_BOOTSTRAP_MODULE_NAME,
                    excludedDirectoryCount = plan.excludedDirectories.size,
                    workspaceRootUrl = plan.workspaceRoot.intellijFileUrl(),
                    excludedDirectoryPaths = plan.excludedDirectories.map { directory ->
                        directory.path
                    },
                    excludedDirectoryUrls = plan.excludedDirectories
                        .mapTo(linkedSetOf()) { directory ->
                            directory.path.intellijFileUrl()
                        },
                ),
            )
        } catch (_: IOException) {
            InstalledIndexBootstrapPreparation.Rejected(
                InstalledIndexBootstrapFailure.CONFIGURATION_WRITE_FAILED,
            )
        } catch (_: SecurityException) {
            InstalledIndexBootstrapPreparation.Rejected(
                InstalledIndexBootstrapFailure.CONFIGURATION_WRITE_FAILED,
            )
        } catch (_: XMLStreamException) {
            InstalledIndexBootstrapPreparation.Rejected(
                InstalledIndexBootstrapFailure.CONFIGURATION_WRITE_FAILED,
            )
        }

        private fun writeModulesXml(destination: Path) {
            writeXml(destination) {
                element("project", "version" to "4") {
                    element("component", "name" to "ProjectModuleManager") {
                        element("modules") {
                            emptyElement(
                                "module",
                                "fileurl" to "file://\$PROJECT_DIR\$/.idea/$MODULE_FILE_NAME",
                                "filepath" to "\$PROJECT_DIR\$/.idea/$MODULE_FILE_NAME",
                            )
                        }
                    }
                }
            }
        }

        private fun writeModuleXml(
            destination: Path,
            plan: InstalledIndexExclusionPlan,
        ) {
            writeXml(destination) {
                element("module", "type" to "JAVA_MODULE", "version" to "4") {
                    element(
                        "component",
                        "name" to "NewModuleRootManager",
                        "inherit-compiler-output" to "true",
                    ) {
                        emptyElement("exclude-output")
                        element("content", "url" to plan.workspaceRoot.intellijFileUrl()) {
                            plan.excludedDirectories.forEach { excluded ->
                                emptyElement(
                                    "excludeFolder",
                                    "url" to excluded.path.intellijFileUrl(),
                                )
                            }
                        }
                        emptyElement("orderEntry", "type" to "inheritedJdk")
                        emptyElement(
                            "orderEntry",
                            "type" to "sourceFolder",
                            "forTests" to "false",
                        )
                    }
                }
            }
        }
    }

    /** Binds the exact plan before project services and their root policies are initialized. */
    override fun bind(project: Project): Boolean = try {
        if (project.isDisposed) {
            false
        } else {
            project.putUserData(
                INSTALLED_INDEX_EXCLUSIONS,
                InstalledProjectIndexExclusions(
                    paths = excludedDirectoryPaths,
                    urls = excludedDirectoryUrls.toList(),
                ),
            )
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Proof transition: `Project -> InstalledIndexBootstrapActivation`.
     *
     * Active proves the generated module, exact workspace content root, and every planned
     * exclusion were loaded from the runtime-owned XML before Gradle import. Rejected closes
     * missing identity, weakened roots, and platform observation failures.
     */
    fun activate(project: Project): InstalledIndexBootstrapActivation = try {
        ReadAction.computeBlocking<InstalledIndexBootstrapActivation, RuntimeException> {
            val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
                ?: return@computeBlocking InstalledIndexBootstrapActivation.Rejected(
                    InstalledIndexBootstrapActivationFailure.MODULE_UNAVAILABLE,
                )
            val roots = ModuleRootManager.getInstance(module)
            val installedPolicyCount = DirectoryIndexExcludePolicy.EP_NAME
                .getExtensions(project)
                .count { policy -> policy is InstalledIndexExclusionPolicy }
            if (installedPolicyCount != 1 ||
                project.getUserData(INSTALLED_INDEX_EXCLUSIONS)?.urls?.toSet() !=
                excludedDirectoryUrls
            ) {
                InstalledIndexBootstrapActivation.Rejected(
                    InstalledIndexBootstrapActivationFailure.EXCLUSION_POLICY_MISMATCH,
                )
            } else if (workspaceRootUrl !in roots.contentRootUrls) {
                InstalledIndexBootstrapActivation.Rejected(
                    InstalledIndexBootstrapActivationFailure.CONTENT_ROOT_MISMATCH,
                )
            } else if (!roots.excludeRootUrls.toSet().containsAll(excludedDirectoryUrls)) {
                InstalledIndexBootstrapActivation.Rejected(
                    InstalledIndexBootstrapActivationFailure.EXCLUSION_ROOTS_MISMATCH,
                )
            } else {
                InstalledIndexBootstrapActivation.Active(
                    InstalledActiveIndexBootstrap(module, excludedDirectoryPaths),
                )
            }
        }
    } catch (_: RuntimeException) {
        InstalledIndexBootstrapActivation.Rejected(
            InstalledIndexBootstrapActivationFailure.PLATFORM_OBSERVATION_FAILED,
        )
    }
}

internal fun interface InstalledIndexBootstrapBinder {
    fun bind(project: Project): Boolean
}

internal enum class InstalledIndexBootstrapFailure {
    CONFIGURATION_WRITE_FAILED,
}

internal sealed interface InstalledIndexBootstrapPreparation {
    data class Prepared(
        val bootstrap: InstalledIndexBootstrap,
    ) : InstalledIndexBootstrapPreparation

    data class Rejected(
        val failure: InstalledIndexBootstrapFailure,
    ) : InstalledIndexBootstrapPreparation
}

internal enum class InstalledIndexBootstrapActivationFailure {
    MODULE_UNAVAILABLE,
    EXCLUSION_POLICY_MISMATCH,
    CONTENT_ROOT_MISMATCH,
    EXCLUSION_ROOTS_MISMATCH,
    PLATFORM_OBSERVATION_FAILED,
}

internal sealed interface InstalledIndexBootstrapActivation {
    data class Active(
        val bootstrap: InstalledActiveIndexBootstrap,
    ) : InstalledIndexBootstrapActivation

    data class Rejected(
        val failure: InstalledIndexBootstrapActivationFailure,
    ) : InstalledIndexBootstrapActivation
}

/** Loaded pre-open exclusion capability that may be retired only after Gradle import completes. */
internal class InstalledActiveIndexBootstrap(
    private val module: Module,
    private val excludedDirectoryPaths: List<Path>,
) {
    /**
     * Proof transition: `InstalledActiveIndexBootstrap + Project ->
     * InstalledIndexBootstrapRetirement`.
     *
     * Retired proves the temporary module can no longer mask source roots from the imported Gradle
     * model. The Gradle import is permitted to replace it before explicit retirement.
     */
    fun retire(project: Project): InstalledIndexBootstrapRetirement = try {
        WriteAction.computeAndWait<InstalledIndexBootstrapRetirement, RuntimeException> {
            val model = ModuleManager.getInstance(project).getModifiableModel()
            if (module.isDisposed) {
                model.dispose()
                InstalledIndexBootstrapRetirement.Retired(
                    InstalledIndexBootstrapRetirementAuthority.GRADLE_IMPORT,
                )
            } else if (model.modules.none { candidate -> candidate === module }) {
                model.dispose()
                InstalledIndexBootstrapRetirement.Rejected(
                    InstalledIndexBootstrapRetirementFailure.MODULE_IDENTITY_LOST,
                )
            } else {
                try {
                    model.disposeModule(module)
                    model.commit()
                    InstalledIndexBootstrapRetirement.Retired(
                        InstalledIndexBootstrapRetirementAuthority.RUNTIME,
                    )
                } catch (failure: RuntimeException) {
                    model.dispose()
                    throw failure
                }
            }
        }
    } catch (_: RuntimeException) {
        InstalledIndexBootstrapRetirement.Rejected(
            InstalledIndexBootstrapRetirementFailure.PLATFORM_MUTATION_FAILED,
        )
    }

    /**
     * Proves the persistent policy excludes every planned root after import, never masks a subtree
     * of an imported source root, and admits imported generated source roots below an exclusion.
     */
    fun verifyImportedModel(project: Project): InstalledIndexExclusionVerification = try {
        ReadAction.computeBlocking<InstalledIndexExclusionVerification, RuntimeException> {
            val importedModules = ModuleManager.getInstance(project).modules
                .filter { candidate -> !candidate.isDisposed && candidate !== module }
            if (importedModules.isEmpty()) {
                return@computeBlocking InstalledIndexExclusionVerification.Rejected(
                    InstalledIndexExclusionVerificationFailure.IMPORTED_MODULES_UNAVAILABLE,
                )
            }
            val index = ProjectFileIndex.getInstance(project)
            val localFileSystem = LocalFileSystem.getInstance()
            val excludedRoots = excludedDirectoryPaths.map { path ->
                localFileSystem.findFileByNioFile(path)
                    ?: return@computeBlocking InstalledIndexExclusionVerification.Rejected(
                        InstalledIndexExclusionVerificationFailure.EXCLUSION_ROOT_UNAVAILABLE,
                    )
            }
            if (excludedRoots.any { root -> !index.isExcluded(root) }) {
                return@computeBlocking InstalledIndexExclusionVerification.Rejected(
                    InstalledIndexExclusionVerificationFailure.EXCLUSION_NOT_PRESERVED,
                )
            }
            val importedSourceRoots = importedModules
                .flatMap { importedModule ->
                    ModuleRootManager.getInstance(importedModule).sourceRoots.asList()
                }
                .map { sourceRoot ->
                    val sourcePath = VfsUtilCore.virtualToIoFile(sourceRoot)
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                    sourceRoot to sourcePath
                }
            if (
                importedSourceRoots.any { (_, sourcePath) ->
                    excludedDirectoryPaths.any { excludedPath -> excludedPath.startsWith(sourcePath) }
                }
            ) {
                return@computeBlocking InstalledIndexExclusionVerification.Rejected(
                    InstalledIndexExclusionVerificationFailure.SOURCE_ROOT_NOT_ADMITTED,
                )
            }
            val sourceRootsBelowExclusions = importedSourceRoots
                .filter { (_, sourcePath) -> excludedDirectoryPaths.any(sourcePath::startsWith) }
                .map { (sourceRoot, _) -> sourceRoot }
            if (sourceRootsBelowExclusions.any { sourceRoot -> !index.isInSourceContent(sourceRoot) }) {
                InstalledIndexExclusionVerification.Rejected(
                    InstalledIndexExclusionVerificationFailure.SOURCE_ROOT_NOT_ADMITTED,
                )
            } else {
                InstalledIndexExclusionVerification.Verified(
                    generatedSourceRootCount = sourceRootsBelowExclusions.size,
                )
            }
        }
    } catch (_: RuntimeException) {
        InstalledIndexExclusionVerification.Rejected(
            InstalledIndexExclusionVerificationFailure.PLATFORM_OBSERVATION_FAILED,
        )
    }
}

/** Persistent project-level owner for the exact pre-open exclusion plan. */
class InstalledIndexExclusionPolicy(
    private val project: Project,
) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> =
        project.getUserData(INSTALLED_INDEX_EXCLUSIONS)?.urls?.toTypedArray() ?: emptyArray()
}

private data class InstalledProjectIndexExclusions(
    val paths: List<Path>,
    val urls: List<String>,
)

private val INSTALLED_INDEX_EXCLUSIONS =
    Key.create<InstalledProjectIndexExclusions>("kast.installed.index.exclusions")

internal enum class InstalledIndexExclusionVerificationFailure {
    IMPORTED_MODULES_UNAVAILABLE,
    EXCLUSION_ROOT_UNAVAILABLE,
    EXCLUSION_NOT_PRESERVED,
    SOURCE_ROOT_NOT_ADMITTED,
    PLATFORM_OBSERVATION_FAILED,
}

internal sealed interface InstalledIndexExclusionVerification {
    data class Verified(
        val generatedSourceRootCount: Int,
    ) : InstalledIndexExclusionVerification

    data class Rejected(
        val failure: InstalledIndexExclusionVerificationFailure,
    ) : InstalledIndexExclusionVerification
}

internal enum class InstalledIndexBootstrapRetirementAuthority {
    GRADLE_IMPORT,
    RUNTIME,
}

internal enum class InstalledIndexBootstrapRetirementFailure {
    MODULE_IDENTITY_LOST,
    PLATFORM_MUTATION_FAILED,
}

internal sealed interface InstalledIndexBootstrapRetirement {
    data class Retired(
        val authority: InstalledIndexBootstrapRetirementAuthority,
    ) : InstalledIndexBootstrapRetirement

    data class Rejected(
        val failure: InstalledIndexBootstrapRetirementFailure,
    ) : InstalledIndexBootstrapRetirement
}

private fun Path.intellijFileUrl(): String = VfsUtilCore.pathToUrl(toString()).removeSuffix("/")

private fun writeXml(
    destination: Path,
    content: XMLStreamWriter.() -> Unit,
) {
    Files.newOutputStream(destination).use { output ->
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, Charsets.UTF_8.name())
        try {
            xml.writeStartDocument(Charsets.UTF_8.name(), "1.0")
            xml.content()
            xml.writeEndDocument()
            xml.flush()
        } finally {
            xml.close()
        }
    }
}

private fun XMLStreamWriter.element(
    name: String,
    vararg attributes: Pair<String, String>,
    content: XMLStreamWriter.() -> Unit,
) {
    writeStartElement(name)
    attributes.forEach { (attribute, value) -> writeAttribute(attribute, value) }
    content()
    writeEndElement()
}

private fun XMLStreamWriter.emptyElement(
    name: String,
    vararg attributes: Pair<String, String>,
) {
    writeEmptyElement(name)
    attributes.forEach { (attribute, value) -> writeAttribute(attribute, value) }
}
