package io.github.amichne.kast.parity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

internal data class BackendParityConfig(
    val standaloneSocket: Path,
    val intellijSocket: Path,
    val usageFile: Path? = null,
    val usageOffset: Int? = null,
    val brokenFile: Path? = null,
)

internal object BackendParityConfigFixture {
    private const val configFileName = "config.toml"
    private const val paritySection = "parity"
    private const val standaloneSocketKey = "standalone-socket"
    private const val intellijSocketKey = "intellij-socket"
    private const val usageFileKey = "usage-file"
    private const val usageOffsetKey = "usage-offset"
    private const val brokenFileKey = "broken-file"
    private val usageSource = """
        package parity.fixture

        class Greeter {
            fun greeting(): String = "hello"
        }
    """.trimIndent() + "\n"
    private val brokenSource = """
        package parity.fixture

        val broken =
    """.trimIndent() + "\n"

    fun defaultConfig(configHome: Path): BackendParityConfig = BackendParityConfig(
        standaloneSocket = configHome.resolve("sockets").resolve("standalone.sock"),
        intellijSocket = configHome.resolve("sockets").resolve("intellij.sock"),
        usageFile = configHome.resolve("fixtures").resolve("Usage.kt"),
        usageOffset = usageSource.indexOf("greeting"),
        brokenFile = configHome.resolve("fixtures").resolve("Broken.kt"),
    )

    fun materialize(
        configHome: Path,
        sourceConfigHome: Path,
    ): Path {
        val sourceConfig = loadOrNull(sourceConfigHome)
        val config = sourceConfig ?: defaultConfig(configHome).also(::writeDefaultSourceFiles)
        return write(configHome, config)
    }

    fun write(
        configHome: Path,
        config: BackendParityConfig = defaultConfig(configHome),
    ): Path {
        Files.createDirectories(configHome)
        val configFile = configHome.resolve(configFileName)
        Files.writeString(configFile, config.toToml())
        return configFile
    }

    fun load(configHome: Path): BackendParityConfig =
        loadOrNull(configHome) ?: error("Missing [$paritySection] section in ${configHome.resolve(configFileName)}")

    fun loadOrNull(configHome: Path): BackendParityConfig? {
        val configFile = configHome.resolve(configFileName)
        if (!Files.isRegularFile(configFile)) return null

        val values = parseParitySection(Files.readString(configFile))
        if (values.isEmpty()) return null

        val standaloneSocket = values.requiredPath(standaloneSocketKey, configHome)
        val intellijSocket = values.requiredPath(intellijSocketKey, configHome)
        return BackendParityConfig(
            standaloneSocket = standaloneSocket,
            intellijSocket = intellijSocket,
            usageFile = values.optionalPath(usageFileKey, configHome),
            usageOffset = values[usageOffsetKey]?.toIntOrNull()
                          ?: values[usageOffsetKey]?.let { error("[$paritySection].$usageOffsetKey must be an integer") },
            brokenFile = values.optionalPath(brokenFileKey, configHome),
        )
    }

    private fun writeDefaultSourceFiles(config: BackendParityConfig) {
        config.usageFile?.let { file ->
            Files.createDirectories(file.parent)
            Files.writeString(file, usageSource)
        }
        config.brokenFile?.let { file ->
            Files.createDirectories(file.parent)
            Files.writeString(file, brokenSource)
        }
    }

    private fun BackendParityConfig.toToml(): String = buildString {
        appendLine("[$paritySection]")
        appendLine("$standaloneSocketKey = ${standaloneSocket.toString().tomlString()}")
        appendLine("$intellijSocketKey = ${intellijSocket.toString().tomlString()}")
        usageFile?.let { appendLine("$usageFileKey = ${it.toString().tomlString()}") }
        usageOffset?.let { appendLine("$usageOffsetKey = $it") }
        brokenFile?.let { appendLine("$brokenFileKey = ${it.toString().tomlString()}") }
    }

    private fun parseParitySection(content: String): Map<String, String> {
        var inParity = false
        return buildMap {
            content.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    when {
                        line.startsWith("[") && line.endsWith("]") ->
                            inParity = line.removePrefix("[").removeSuffix("]") == paritySection
                        inParity -> {
                            val parts = line.split("=", limit = 2)
                            require(parts.size == 2) { "Invalid [$paritySection] config line: $line" }
                            put(parts[0].trim(), parts[1].trim().parseTomlValue())
                        }
                    }
                }
        }
    }

    private fun Map<String, String>.requiredPath(
        key: String,
        configHome: Path,
    ): Path =
        optionalPath(key, configHome) ?: error("Missing [$paritySection].$key in ${configHome.resolve(configFileName)}")

    private fun Map<String, String>.optionalPath(
        key: String,
        configHome: Path,
    ): Path? =
        this[key]?.let { value ->
            Path(value).let { path ->
                if (path.isAbsolute) path.normalize() else configHome.resolve(path).normalize()
            }
        }

    private fun String.tomlString(): String = buildString {
        append('"')
        this@tomlString.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun String.parseTomlValue(): String {
        if (!startsWith('"')) return this
        require(endsWith('"')) { "Unterminated TOML string: $this" }
        val body = substring(1, lastIndex)
        return buildString {
            var escaping = false
            body.forEach { char ->
                if (escaping) {
                    append(
                        when (char) {
                            '\\' -> '\\'
                            '"' -> '"'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> error("Unsupported TOML escape: $char")
                        },
                    )
                    escaping = false
                } else if (char == '\\') {
                    escaping = true
                } else {
                    append(char)
                }
            }
            require(!escaping) { "Unterminated TOML escape: $this" }
        }
    }
}
