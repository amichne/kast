package conventions.jsoncontracts

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** File-scoped syntactic evidence, never compiler name or type resolution. */
@Serializable
sealed interface KotlinSymbolScan {
    @Serializable
    @SerialName("accepted")
    data class Accepted(val names: List<String>) : KotlinSymbolScan

    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: KnowledgeDocsFailureCode) : KotlinSymbolScan
}

@Serializable
data class KnowledgeSourceSymbols(val path: String, val result: KotlinSymbolScan)

/** Isolated parser boundary shared by the OKF gate and its temporary-source regression tests. */
object KnowledgeSymbolsMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val sources = KotlinDocumentationScanner().use { scanner ->
            arguments.map { source ->
                val path = Path.of(source)
                val result = try {
                    scanner.symbols(path.fileName.toString(), Files.readString(path))
                } catch (_: IOException) {
                    KotlinSymbolScan.Rejected(KnowledgeDocsFailureCode.UNREADABLE_SOURCE)
                }
                KnowledgeSourceSymbols(source, result)
            }
        }
        println(Json.encodeToString(sources))
    }
}
