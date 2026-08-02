package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

abstract class DurableFileStageTestFixture {
    @TempDir
    lateinit var workspaceRoot: Path

    protected fun commitSources(
        store: SqliteSourceIndexStore,
        stage: FileIndexStage,
        paths: Collection<String>,
    ) {
        val workByPath = store.pendingFileStages(stage).associateBy { work -> work.path.rawPath }
        store.commitSourceBatch(
            paths.map { path ->
                SourceFileStageUpdate(
                    work = workByPath.getValue(path), scannedContentHash = workByPath.getValue(path).contentHash,
                    update = sourceUpdate(path),
                )
            },
        )
    }

    protected fun commitRelationships(store: SqliteSourceIndexStore, paths: Collection<String>) {
        val workByPath = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
            .associateBy { work -> work.path.rawPath }
        store.commitRelationshipBatch(
            paths.map { path ->
                RelationshipFileStageUpdate(
                    work = workByPath.getValue(path), scannedContentHash = workByPath.getValue(path).contentHash,
                    references = listOf(reference(path, "demo.${Path.of(path).fileName}")),
                    declarations = emptyList(),
                )
            },
        )
    }

    protected fun inventory(path: String, hash: FileContentHash, moduleName: String): FileInventoryEntry =
        inventory(workspaceRoot, path, hash, moduleName)

    protected fun inventory(
        root: Path,
        path: String,
        hash: FileContentHash,
        moduleName: String,
    ): FileInventoryEntry =
        fileInventoryEntry(
            workspaceRoot = root,
            path = path,
            lastModifiedMillis = 1,
            contentHash = hash,
            moduleName = moduleName,
            sourceSet = "main",
        )

    protected fun sourceUpdate(path: String): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = setOf(Path.of(path).fileName.toString().removeSuffix(".kt")),
            packageName = "demo",
            modulePath = ":app",
            sourceSet = "main",
            imports = emptySet(),
            wildcardImports = emptySet(),
            packageEvidence = IndexedPackageEvidence.ProvenNamed(
                IndexedPackageEvidence.CanonicalName.parse("demo"),
            ),
        )

    protected fun reference(path: String, target: String, targetPath: String? = null): SymbolReferenceRow =
        SymbolReferenceRow(
            sourcePath = path,
            sourceOffset = 1,
            targetFqName = target,
            targetPath = targetPath,
            targetOffset = targetPath?.let { 1 },
        )

    protected fun file(relative: String): String {
        val path = workspaceRoot.resolve(relative).toAbsolutePath().normalize()
        Files.createDirectories(path.parent)
        Files.writeString(path, "package demo")
        return workspaceSourceRawPath(workspaceRoot, path.toString())
    }

    protected fun versions(value: String): FileStageVersions =
        FileStageVersions(
            source = version(value),
            relationships = version(value),
            semanticGraph = version(value),
        )

    protected fun version(value: String): FileStageVersion = FileStageVersion.parse("test-$value")

    protected fun hash(character: Char): FileContentHash =
        FileContentHash.parse(character.toString().repeat(64))
}
