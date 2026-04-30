package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FqName
import io.github.amichne.kast.api.contract.KotlinIdentifier
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PackageName
import io.github.amichne.kast.indexstore.FileIndexUpdate
import io.github.amichne.kast.indexstore.SourceIndexSnapshot
import io.github.amichne.kast.indexstore.SourceIndexWriter
import io.github.amichne.kast.indexstore.parseSourceFileIndex
import java.util.concurrent.ConcurrentHashMap

internal class MutableSourceIdentifierIndex(
    private val pathsByIdentifier: ConcurrentHashMap<KotlinIdentifier, MutableSet<NormalizedPath>>,
    private val identifiersByPath: ConcurrentHashMap<NormalizedPath, Set<KotlinIdentifier>>,
    private val moduleNameByPath: ConcurrentHashMap<NormalizedPath, ModuleName> = ConcurrentHashMap(),
    private val packageByPath: ConcurrentHashMap<NormalizedPath, PackageName> = ConcurrentHashMap(),
    private val importsByPath: ConcurrentHashMap<NormalizedPath, Set<FqName>> = ConcurrentHashMap(),
    private val wildcardImportPackagesByPath: ConcurrentHashMap<NormalizedPath, Set<PackageName>> = ConcurrentHashMap(),
    private val backingStore: SourceIndexWriter? = null,
) {
    fun candidatePathsFor(identifier: String): List<String> =
        pathsByIdentifier[KotlinIdentifier(identifier)]?.map { it.value }?.sorted().orEmpty()

    fun candidatePathsForModule(
        identifier: String,
        allowedModuleNames: Set<ModuleName>,
    ): List<String> {
        val rawCandidates = pathsByIdentifier[KotlinIdentifier(identifier)] ?: return emptyList()
        return filterPathsByAllowedModules(rawCandidates, allowedModuleNames)
            .map { it.value }
            .sorted()
    }

    /**
     * Returns file paths that contain [identifier] and are plausibly importing [targetFqName]:
     * same package, explicit import, wildcard import of the target's package, or an import of
     * any ancestor FQ name between [targetFqName] and [targetPackage] (e.g., importing a
     * containing class to access a companion member).
     */
    internal fun candidatePathsForFqName(
        identifier: String,
        targetPackage: String,
        targetFqName: String,
        allowedModuleNames: Set<ModuleName>? = null,
    ): List<String> {
        val id = KotlinIdentifier(identifier)
        val pkg = PackageName(targetPackage)
        val fqn = FqName(targetFqName)
        val rawCandidates = pathsByIdentifier[id] ?: return emptyList()
        val ancestorFqNames = ancestorFqNamesOf(targetFqName, targetPackage)
        val ancestorWildcardPackages = ancestorFqNames.mapTo(mutableSetOf()) { PackageName(it.value) }
        return rawCandidates
            .filter { path ->
                packageByPath[path] == pkg ||
                importsByPath[path]?.contains(fqn) == true ||
                wildcardImportPackagesByPath[path]?.contains(pkg) == true ||
                (ancestorFqNames.isNotEmpty() && importsByPath[path]?.any { it in ancestorFqNames } == true) ||
                (ancestorWildcardPackages.isNotEmpty() && wildcardImportPackagesByPath[path]?.any { it in ancestorWildcardPackages } == true)
            }
            .let { candidates ->
                allowedModuleNames?.let { moduleNames -> filterPathsByAllowedModules(candidates, moduleNames) } ?: candidates
            }
            .map { it.value }
            .sorted()
    }

    fun toSerializableMap(): Map<String, List<String>> = pathsByIdentifier.entries
        .asSequence()
        .sortedBy { it.key.value }
        .associate { (identifier, paths) ->
            identifier.value to paths.map { it.value }.sorted()
        }

    fun toSerializableMetadata(): SourceIdentifierIndexMetadataSnapshot =
        SourceIdentifierIndexMetadataSnapshot(
            moduleNameByPath = moduleNameByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, moduleName) -> path.value to moduleName.value },
            packageByPath = packageByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, packageName) -> path.value to packageName.value },
            importsByPath = importsByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, imports) -> path.value to imports.map { it.value }.sorted() },
            wildcardImportPackagesByPath = wildcardImportPackagesByPath.entries
                .asSequence()
                .sortedBy { it.key.value }
                .associate { (path, packages) -> path.value to packages.map { it.value }.sorted() },
        )

    fun updateFile(
        normalizedPath: String,
        newContent: String,
        moduleName: ModuleName? = null,
    ) {
        val path = NormalizedPath.ofNormalized(normalizedPath)
        val fileIndex = parseSourceFileIndex(
            path = normalizedPath,
            content = newContent,
            moduleName = moduleName?.value,
        )
        val identifiers = fileIndex.identifiers.mapTo(mutableSetOf()) { KotlinIdentifier(it) }
        replaceIdentifiers(normalizedPath = path, identifiers = identifiers)
        replaceFileMetadata(path, fileIndex, moduleName)

        backingStore?.let { store ->
            runCatching {
                store.saveFileIndex(
                    fileIndex,
                )
            }
        }
    }

    fun removeFile(normalizedPath: String) {
        val path = NormalizedPath.ofNormalized(normalizedPath)
        replaceIdentifiers(normalizedPath = path, identifiers = emptySet())
        moduleNameByPath.remove(path)
        packageByPath.remove(path)
        importsByPath.remove(path)
        wildcardImportPackagesByPath.remove(path)
        backingStore?.let { store -> runCatching { store.removeFile(normalizedPath) } }
    }

    fun knownPaths(): Set<String> = identifiersByPath.keys.mapTo(mutableSetOf()) { it.value }

    internal fun identifiersForPath(path: NormalizedPath): Set<KotlinIdentifier> =
        identifiersByPath[path].orEmpty()

    internal fun packageNameForPath(path: NormalizedPath): PackageName? = packageByPath[path]

    internal fun moduleNameForPath(path: NormalizedPath): ModuleName? = moduleNameByPath[path]

    internal fun importsForPath(path: NormalizedPath): Set<FqName> = importsByPath[path].orEmpty()

    internal fun wildcardImportsForPath(path: NormalizedPath): Set<PackageName> =
        wildcardImportPackagesByPath[path].orEmpty()

    internal fun extractFileMetadata(
        normalizedPath: String,
        content: String,
        moduleName: ModuleName? = null,
    ) {
        replaceFileMetadata(
            normalizedPath = NormalizedPath.ofNormalized(normalizedPath),
            fileIndex = parseSourceFileIndex(
                path = normalizedPath,
                content = content,
                moduleName = moduleName?.value,
            ),
            moduleName = moduleName,
        )
    }

    private fun replaceFileMetadata(
        normalizedPath: NormalizedPath,
        fileIndex: FileIndexUpdate,
        moduleName: ModuleName?,
    ) {
        if (moduleName != null) {
            moduleNameByPath[normalizedPath] = moduleName
        } else {
            moduleNameByPath.remove(normalizedPath)
        }
        fileIndex.packageName
            ?.let { packageByPath[normalizedPath] = PackageName(it) }
        ?: packageByPath.remove(normalizedPath)

        val imports = fileIndex.imports.mapTo(mutableSetOf()) { FqName(it) }
        val wildcardPackages = fileIndex.wildcardImports.mapTo(mutableSetOf()) { PackageName(it) }

        if (imports.isNotEmpty()) importsByPath[normalizedPath] = imports
        else importsByPath.remove(normalizedPath)

        if (wildcardPackages.isNotEmpty()) wildcardImportPackagesByPath[normalizedPath] = wildcardPackages
        else wildcardImportPackagesByPath.remove(normalizedPath)
    }

    private fun replaceIdentifiers(
        normalizedPath: NormalizedPath,
        identifiers: Set<KotlinIdentifier>,
    ) {
        val previousIdentifiers = identifiersByPath.remove(normalizedPath).orEmpty()
        previousIdentifiers.forEach { identifier ->
            val paths = pathsByIdentifier[identifier] ?: return@forEach
            paths.remove(normalizedPath)
            if (paths.isEmpty()) {
                pathsByIdentifier.remove(identifier, paths)
            }
        }
        if (identifiers.isEmpty()) {
            return
        }

        identifiersByPath[normalizedPath] = identifiers
        identifiers.forEach { identifier ->
            pathsByIdentifier.computeIfAbsent(identifier) { ConcurrentHashMap.newKeySet() }
                .add(normalizedPath)
        }
    }

    private fun filterPathsByAllowedModules(
        candidates: Collection<NormalizedPath>,
        allowedModuleNames: Set<ModuleName>,
    ): Collection<NormalizedPath> {
        if (candidates.isEmpty()) {
            return emptyList()
        }
        if (candidates.any { path -> moduleNameByPath[path] == null }) {
            return candidates
        }
        return candidates.filter { path -> moduleNameByPath[path] in allowedModuleNames }
    }

    companion object {
        /**
         * Computes intermediate FQ name prefixes between [targetPackage] (exclusive)
         * and [targetFqName] (exclusive). For example, given
         * `targetFqName = "pkg.Foo.Companion.create"` and `targetPackage = "pkg"`,
         * returns `{FqName("pkg.Foo.Companion"), FqName("pkg.Foo")}`.
         */
        private fun ancestorFqNamesOf(targetFqName: String, targetPackage: String): Set<FqName> {
            if (targetFqName.length <= targetPackage.length + 1) return emptySet()
            return buildSet {
                var current = targetFqName
                while (true) {
                    val lastDot = current.lastIndexOf('.')
                    if (lastDot < 0 || lastDot <= targetPackage.length) break
                    current = current.substring(0, lastDot)
                    if (current.length > targetPackage.length) {
                        add(FqName(current))
                    }
                }
            }
        }

        fun fromCandidatePathsByIdentifier(
            candidatePathsByIdentifier: Map<String, List<String>>,
            moduleNameByPath: Map<String, String> = emptyMap(),
            packageByPath: Map<String, String> = emptyMap(),
            importsByPath: Map<String, List<String>> = emptyMap(),
            wildcardImportPackagesByPath: Map<String, List<String>> = emptyMap(),
            backingStore: SourceIndexWriter? = null,
        ): MutableSourceIdentifierIndex {
            val typedPathsByIdentifier = ConcurrentHashMap<KotlinIdentifier, MutableSet<NormalizedPath>>()
            val typedIdentifiersByPath = ConcurrentHashMap<NormalizedPath, Set<KotlinIdentifier>>()
            candidatePathsByIdentifier.forEach { (identifier, paths) ->
                typedPathsByIdentifier[KotlinIdentifier(identifier)] = paths.mapTo(ConcurrentHashMap.newKeySet()) {
                    NormalizedPath.ofNormalized(it)
                }
                paths.mapTo<String, NormalizedPath, ConcurrentHashMap.KeySetView<NormalizedPath, Boolean>>(
                    ConcurrentHashMap.newKeySet()
                ) { NormalizedPath.ofNormalized(it) }
                    .forEach { normalizedPath ->
                        typedIdentifiersByPath.compute(normalizedPath) { _, existingIdentifiers ->
                            (existingIdentifiers.orEmpty() + KotlinIdentifier(identifier))
                        }
                    }
            }
            return MutableSourceIdentifierIndex(
                pathsByIdentifier = typedPathsByIdentifier,
                identifiersByPath = typedIdentifiersByPath,
                moduleNameByPath = moduleNameByPath.entries.associateTo(ConcurrentHashMap()) { (path, moduleName) ->
                    NormalizedPath.ofNormalized(path) to ModuleName(moduleName)
                },
                packageByPath = packageByPath.entries.associateTo(ConcurrentHashMap()) { (path, pkg) ->
                    NormalizedPath.ofNormalized(path) to PackageName(pkg)
                },
                importsByPath = importsByPath.entries.associateTo(ConcurrentHashMap()) { (path, imports) ->
                    NormalizedPath.ofNormalized(path) to imports.mapTo(mutableSetOf()) { FqName(it) }
                },
                wildcardImportPackagesByPath = wildcardImportPackagesByPath.entries.associateTo(ConcurrentHashMap()) { (path, packages) ->
                    NormalizedPath.ofNormalized(path) to packages.mapTo(mutableSetOf()) { PackageName(it) }
                },
                backingStore = backingStore,
            )
        }

        fun fromSourceIndexSnapshot(
            snapshot: SourceIndexSnapshot,
            backingStore: SourceIndexWriter? = null,
        ): MutableSourceIdentifierIndex =
            fromCandidatePathsByIdentifier(
                candidatePathsByIdentifier = snapshot.candidatePathsByIdentifier,
                moduleNameByPath = snapshot.moduleNameByPath,
                packageByPath = snapshot.packageByPath,
                importsByPath = snapshot.importsByPath,
                wildcardImportPackagesByPath = snapshot.wildcardImportPackagesByPath,
                backingStore = backingStore,
            )
    }
}
