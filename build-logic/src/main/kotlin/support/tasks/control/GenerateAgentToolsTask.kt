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

/** Projects canonical CLI guidance and its explicit MCP composition into a versioned marketplace. */
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
        val mcp = agentToolsJson.decodeFromString<AgentMcpDocument>(source.resolve("mcp.json").readText())
        val marketplace = agentToolsJson.decodeFromString<AgentMarketplace>(marketplaceFile.get().asFile.readText())
        check(manifest.name == "kast" && manifest.hooks == null && manifest.mcpServers == null && manifest.skills == "./skills/") {
            "The default Kast plugin must select CLI guidance without hooks or MCP"
        }
        val copilot = agentToolsJson.decodeFromString<CopilotPluginManifest>(source.resolve(".github/plugin/plugin.json").readText())
        check(copilot.name == "kast" && copilot.hooks == null && copilot.mcpServers == null && copilot.skills == "./skills/") {
            "The default Copilot plugin must select CLI guidance without hooks or MCP"
        }
        check(mcp.mcpServers.kast.command == "/bin/bash" && mcp.mcpServers.kast.args == listOf(
            "-c", "PATH=\"\${HOME}/.local/bin:\${PATH}\" exec uv run --locked --script \"\${PLUGIN_ROOT:-\${COPILOT_PLUGIN_ROOT:-\${HOME}/.local/share/kast/installation/share/kast/agent-tools/compositions/mcp/plugins/kast}}/skills/kast/scripts/server.py\"",
        )) { "Kast plugin must use the installed repository-bound MCP launcher" }
        check(mcp.mcpServers.kast.toolTimeoutSeconds == 3780 && mcp.mcpServers.kast.timeoutMillis == 3_780_000) {
            "MCP host deadlines must cover the admitted task service allowance"
        }
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
        check(paths.none { isPrivateDistributionArtifact(source.toPath().relativize(it)) }) {
            "agent plugin source contains a private environment or Python cache"
        }
        check(paths.filter(Files::isRegularFile).sumOf(Files::size) <= 1_048_576) { "agent plugin source exceeds one MiB" }
        val required = listOf("references/onboarding.md", "references/query-schema.json", "references/query-examples.json",
            "skills/kast/SKILL.md", "skills/kast/public-cli.md", "skills/kast/scripts/server.py", "skills/kast/scripts/server.py.lock",
            "skills/kast/scripts/service.py", "skills/kast/scripts/tasks.py",
            "skills/kast/scripts/access.py", "skills/kast/scripts/access.py.lock", "skills/kast/scripts/transport.py")
        check(required.all { source.resolve(it).isFile }) { "shared session context and generated references are required" }
        check(source.resolve("references/query-examples.json").readBytes()
            .contentEquals(canonicalQueryExamplesFile.get().asFile.readBytes())) { "examples must match the canonical query projection" }

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        for (withMcp in listOf(false, true)) {
            val composition = if (withMcp) output.resolve("compositions/mcp") else output
            val plugin = composition.resolve("plugins/kast")
            source.copyRecursively(plugin, overwrite = false)
            plugin.resolve("hooks").deleteRecursively()
            plugin.resolve("mcp.json").delete()
            if (withMcp) source.resolve("mcp.json").copyTo(plugin.resolve(".mcp.json"))
            if (!withMcp) {
                plugin.resolve(".mcp.json").delete()
                plugin.resolve("skills/kast/scripts").deleteRecursively()
            }
            plugin.resolve(".codex-plugin/plugin.json").writeText(
                agentToolsJson.encodeToString(AgentPluginManifest.serializer(), manifest.copy(version = version,
                    mcpServers = if (withMcp) "./.mcp.json" else null)) + "\n",
            )
            plugin.resolve(".github/plugin/plugin.json").writeText(
                agentToolsJson.encodeToString(CopilotPluginManifest.serializer(), copilot.copy(version = version,
                    mcpServers = if (withMcp) "./.mcp.json" else null)) + "\n",
            )
            val destination = composition.resolve(".agents/plugins/marketplace.json")
            destination.parentFile.mkdirs()
            destination.writeText(agentToolsJson.encodeToString(
                AgentMarketplace.serializer(), marketplace.copy(plugins = listOf(
                    entry.copy(source = entry.source.copy(path = "./plugins/kast")),
                )),
            ) + "\n")
            val copilotMarketplace = composition.resolve(".github/plugin/marketplace.json")
            copilotMarketplace.parentFile.mkdirs()
            copilotMarketplace.writeText(agentToolsJson.encodeToString(CopilotMarketplace.serializer(),
                CopilotMarketplace("kast", AgentAuthor("Amichne"), listOf(CopilotMarketplaceEntry("kast", "./plugins/kast")))) + "\n")
        }
    }
}

internal val agentToolsJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }

@Serializable
internal data class AgentPluginManifest(
    val name: String,
    val version: String,
    val description: String,
    val author: AgentAuthor,
    val homepage: String,
    val repository: String,
    val license: String,
    val hooks: String? = null,
    val mcpServers: String? = null,
    val skills: String,
    @SerialName("interface") val display: AgentPluginDisplay,
)

@Serializable
internal data class CopilotPluginManifest(
    val name: String,
    val version: String,
    val description: String,
    val hooks: String? = null,
    val mcpServers: String? = null,
    val skills: String,
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
internal data class CopilotMarketplace(val name: String, val owner: AgentAuthor, val plugins: List<CopilotMarketplaceEntry>)

@Serializable
internal data class CopilotMarketplaceEntry(val name: String, val source: String)

@Serializable
internal data class AgentMcpDocument(val mcpServers: AgentMcpServers)

@Serializable
internal data class AgentMcpServers(val kast: AgentMcpServer)

@Serializable
internal data class AgentMcpServer(
    val command: String,
    val args: List<String>,
    @SerialName("tool_timeout_sec") val toolTimeoutSeconds: Int,
    @SerialName("timeout") val timeoutMillis: Int,
)

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
