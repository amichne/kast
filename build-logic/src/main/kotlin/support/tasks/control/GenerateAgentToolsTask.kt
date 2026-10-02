package support.tasks

import java.nio.file.Files
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Projects the single skill source into a versioned, self-contained marketplace. */
@CacheableTask
abstract class GenerateAgentToolsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val marketplaceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonicalQueryExamplesFile: RegularFileProperty

    @get:Input
    abstract val productVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = sourceDirectory.get().asFile
        val manifest = agentToolsJson.decodeFromString<AgentPluginManifest>(
            source.resolve(".codex-plugin/plugin.json").readText(),
        )
        val mcp = agentToolsJson.decodeFromString<AgentMcpDocument>(source.resolve(".mcp.json").readText())
        val marketplace = agentToolsJson.decodeFromString<AgentMarketplace>(marketplaceFile.get().asFile.readText())
        check(manifest.name == "kast" && manifest.skills == "./skills/" && manifest.mcpServers == "./.mcp.json") {
            "Kast plugin must compose the canonical skill and MCP entry"
        }
        check(mcp.mcpServers.kast.command == "/bin/bash" && mcp.mcpServers.kast.args == listOf(
            "-c", "exec \"\${HOME:?HOME is required}/.local/share/kast/installation/bin/kast-mcp-complete\"",
        )) { "Kast plugin must use the installed repository-bound MCP launcher" }
        check(marketplace.name == "kast" && marketplace.plugins.size == 1) { "expected one Kast marketplace entry" }
        val entry = marketplace.plugins.single()
        check(entry.name == "kast" && entry.source.path == "./agent-tools") { "marketplace source identity changed" }
        val version = productVersion.get()
        check(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(version)) {
            "agent plugin requires a semantic product version"
        }
        val paths = Files.walk(source.toPath()).use { it.limit(129).toList() }
        check(paths.size <= 128 && paths.all { !Files.isSymbolicLink(it) && (Files.isDirectory(it) || Files.isRegularFile(it)) }) {
            "agent plugin source must be bounded and regular"
        }
        check(paths.filter(Files::isRegularFile).sumOf(Files::size) <= 1_048_576) { "agent plugin source exceeds one MiB" }
        val required = listOf("skills/kast/SKILL.md", "skills/kast/references/query-examples.json",
            "skills/kast/references/connection.md", "skills/kast/references/query-patterns.md")
        check(required.all { source.resolve(it).isFile }) { "canonical skill and generated examples are required" }
        check(source.resolve("skills/kast/references/query-examples.json").readBytes()
            .contentEquals(canonicalQueryExamplesFile.get().asFile.readBytes())) { "skill examples must match the canonical query projection" }

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val plugin = output.resolve("plugins/kast")
        source.copyRecursively(plugin, overwrite = false)
        plugin.resolve(".codex-plugin/plugin.json").writeText(
            agentToolsJson.encodeToString(AgentPluginManifest.serializer(), manifest.copy(version = version)) + "\n",
        )
        val destination = output.resolve(".agents/plugins/marketplace.json")
        destination.parentFile.mkdirs()
        destination.writeText(agentToolsJson.encodeToString(
            AgentMarketplace.serializer(), marketplace.copy(plugins = listOf(
                entry.copy(source = entry.source.copy(path = "./plugins/kast")),
            )),
        ) + "\n")
    }
}

internal val agentToolsJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable
internal data class AgentPluginManifest(
    val name: String,
    val version: String,
    val description: String,
    val author: AgentAuthor,
    val homepage: String,
    val repository: String,
    val license: String,
    val skills: String,
    val mcpServers: String,
    @SerialName("interface") val display: AgentPluginDisplay,
)

@Serializable
internal data class AgentPluginDisplay(
    val displayName: String,
    val shortDescription: String,
    val longDescription: String,
    val developerName: String,
    val category: AgentPluginCategory,
    val capabilities: List<AgentCapability>,
    val defaultPrompt: String,
)

@Serializable
internal enum class AgentCapability { @SerialName("Read") READ, @SerialName("Write") WRITE }

@Serializable
internal data class AgentAuthor(val name: String)

@Serializable
internal data class AgentMcpDocument(val mcpServers: AgentMcpServers)

@Serializable
internal data class AgentMcpServers(val kast: AgentMcpServer)

@Serializable
internal data class AgentMcpServer(val command: String, val args: List<String>)

@Serializable
internal data class AgentMarketplace(
    val name: String,
    @SerialName("interface") val display: AgentMarketplaceDisplay,
    val plugins: List<AgentMarketplaceEntry>,
)

@Serializable
internal data class AgentMarketplaceDisplay(val displayName: String)

@Serializable
internal data class AgentMarketplaceEntry(
    val name: String,
    val source: AgentLocalSource,
    val policy: AgentPluginPolicy,
    val category: AgentPluginCategory,
)

@Serializable
internal data class AgentLocalSource(val source: AgentSourceType, val path: String)

@Serializable
internal enum class AgentSourceType { @SerialName("local") LOCAL }

@Serializable
internal data class AgentPluginPolicy(val installation: AgentInstallationPolicy, val authentication: AgentAuthenticationPolicy)

@Serializable
internal enum class AgentInstallationPolicy { AVAILABLE }

@Serializable
internal enum class AgentAuthenticationPolicy { ON_INSTALL }

@Serializable
internal enum class AgentPluginCategory { @SerialName("Coding") CODING }
