package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
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
                    messages = listOf(
                        presentation("symbol.discover", KastObserverFixtures.symbolDiscovery, observerDirectory),
                        presentation("symbol.inspect", KastObserverFixtures.symbolInspection, observerDirectory),
                        presentation("source.read", KastObserverFixtures.sourceRead, observerDirectory),
                    ),
                ),
                ObserverSnapshotPage(
                    slug = "kast-observer-semantic-impact",
                    title = "Semantic query and impact rendering",
                    messages = listOf(
                        presentation("relation.read", KastObserverFixtures.semanticQuery, observerDirectory),
                        presentation("traversal.run", KastObserverFixtures.impactAnalysis, observerDirectory),
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
    ): String {
        val projected = KastObserverProjector.project(
            checkNotNull(KastOperationId.admit(operation)),
            KastInvocationOutput(
                document = Json.parseToJsonElement(document).jsonObject,
                success = true,
                observerDirectory = observerDirectory,
            ),
        )
        return checkNotNull((projected as? ObserverPresentation.Markdown)?.source?.value) {
            "Fixture for $operation did not produce observer Markdown."
        }
    }
}

private val snapshotJson = Json { prettyPrint = true }

@Serializable
private data class ObserverSnapshotManifest(
    val pages: List<ObserverSnapshotPage>,
)

@Serializable
private data class ObserverSnapshotPage(
    val slug: String,
    val title: String,
    val messages: List<String>,
)
