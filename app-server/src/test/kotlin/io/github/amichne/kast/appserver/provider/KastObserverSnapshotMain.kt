package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.ObserverPresentation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/** Offline visual-fixture boundary. It projects static Kast documents and never starts Codex. */
internal object KastObserverSnapshotMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected one output manifest path." }
        val output = Path.of(arguments.single()).toAbsolutePath().normalize()
        val observerDirectory = checkNotNull(CanonicalBrokerDirectory.admit(Path.of(".").toRealPath()))
        val manifest = ObserverSnapshotManifest(
            pages = listOf(
                ObserverSnapshotPage(
                    slug = "kast-observer-symbol-source",
                    title = "Symbol and source rendering",
                    items = listOf(
                        presentation("symbol.discover", KastObserverFixtures.symbolDiscovery, observerDirectory),
                        presentation("symbol.inspect", KastObserverFixtures.symbolInspection, observerDirectory),
                        presentation("source.read", KastObserverFixtures.sourceRead, observerDirectory),
                    ),
                ),
                ObserverSnapshotPage(
                    slug = "kast-observer-semantic-impact",
                    title = "Semantic evidence rendering",
                    items = listOf(
                        presentation("relation.read", KastObserverFixtures.semanticQuery, observerDirectory),
                        presentation("traversal.run", KastObserverFixtures.impactAnalysis, observerDirectory),
                        presentation("diagnostic.check", KastObserverFixtures.diagnosticCheck, observerDirectory),
                    ),
                ),
                ObserverSnapshotPage(
                    slug = "kast-observer-change-lifecycle",
                    title = "Mutation and native diff rendering",
                    items = listOf(
                        presentation("change.plan", KastObserverFixtures.changePlan, observerDirectory),
                        presentation("change.apply", KastObserverFixtures.changeApply, observerDirectory),
                        presentation("change.recover", KastObserverFixtures.changeRecover, observerDirectory),
                    ),
                ),
            ),
        )
        Files.createDirectories(checkNotNull(output.parent))
        Files.writeString(
            output,
            snapshotJson.encodeToString(manifest) + "\n",
        )
    }

    private fun presentation(
        operation: String,
        document: String,
        observerDirectory: CanonicalBrokerDirectory,
    ): ObserverSnapshotItem {
        val projected = KastObserverProjector.project(
            checkNotNull(KastOperationId.admit(operation)),
            KastInvocationOutput(
                document = Json.parseToJsonElement(document).jsonObject,
                success = true,
                observerDirectory = observerDirectory,
            ),
        )
        return when (projected) {
            is ObserverPresentation.Markdown -> ObserverSnapshotItem(
                operation = operation,
                presentation = "markdown",
                markdown = projected.source.value,
            )
            is ObserverPresentation.FileChanges -> ObserverSnapshotItem(
                operation = operation,
                presentation = "file-changes",
                changes = projected.files.entries.map { change ->
                    ObserverSnapshotChange(
                        path = change.path.value,
                        kind = change.kind.name.lowercase(),
                        diff = change.diff.value,
                    )
                },
            )
            ObserverPresentation.None -> error("Fixture for $operation did not produce an observer presentation.")
        }
    }
}

private val snapshotJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
}

@Serializable
private data class ObserverSnapshotManifest(
    val pages: List<ObserverSnapshotPage>,
)

@Serializable
private data class ObserverSnapshotPage(
    val slug: String,
    val title: String,
    val items: List<ObserverSnapshotItem>,
)

@Serializable
private data class ObserverSnapshotItem(
    val operation: String,
    val presentation: String,
    val markdown: String? = null,
    val changes: List<ObserverSnapshotChange> = emptyList(),
)

@Serializable
private data class ObserverSnapshotChange(
    val path: String,
    val kind: String,
    val diff: String,
)
