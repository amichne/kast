package io.github.amichne.kast.distribution.managed.network

import io.github.amichne.kast.distribution.contract.network.NetworkConfiguration
import io.github.amichne.kast.distribution.contract.network.NetworkConfigurationFailure
import io.github.amichne.kast.distribution.contract.network.NetworkProperty
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Reads Gradle's user-over-project property authority without changing either file. */
object GradleNetworkConfiguration {
    fun read(
        root: Path,
        gradleUserHome: Path,
        gradleHome: Path? = null,
    ): Refinement<NetworkConfiguration, NetworkConfigurationFailure> {
        return try {
            val effective = Properties()
            for (file in
                listOfNotNull(
                    gradleHome?.resolve("gradle.properties"),
                    root.resolve("gradle.properties"),
                    gradleUserHome.resolve("gradle.properties"),
                )) {
                if (Files.exists(file)) {
                    if (!Files.isRegularFile(file) || Files.size(file) > 1_048_576) {
                        return Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
                    }
                    Files.newInputStream(file).use(effective::load)
                }
            }
            val arguments =
                when (val parsed = jvmProperties(effective.getProperty("org.gradle.jvmargs", ""))) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return parsed
                }
            val system =
                NetworkProperty.entries
                    .mapNotNull { property ->
                        effective.getProperty("systemProp.${property.key}")?.let { property.key to it }
                    }
                    .toMap()
            NetworkConfiguration.parse(arguments + system)
        } catch (_: java.io.IOException) {
            Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
        } catch (_: IllegalArgumentException) {
            Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
        } catch (_: SecurityException) {
            Refinement.Rejected(NetworkConfigurationFailure.CONFIGURATION_UNAVAILABLE)
        }
    }

    /** Tokenizes the quoted JVM-argument boundary; only closed network properties leave it. */
    fun jvmProperties(raw: String): Refinement<Map<String, String>, NetworkConfigurationFailure> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (character in raw) {
            when {
                escaped -> {
                    token.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                quote != null && character == quote -> quote = null
                quote != null -> token.append(character)
                character == '\'' || character == '"' -> quote = character
                character.isWhitespace() ->
                    if (token.isNotEmpty()) {
                        tokens += token.toString()
                        token.clear()
                    }
                else -> token.append(character)
            }
        }
        if (quote != null || escaped) return Refinement.Rejected(NetworkConfigurationFailure.MALFORMED_JVM_ARGUMENTS)
        if (token.isNotEmpty()) tokens += token.toString()
        return Refinement.Refined(fromArguments(tokens))
    }

    fun fromArguments(arguments: List<String>): Map<String, String> = buildMap {
        for (argument in arguments) {
            if (!argument.startsWith("-D")) continue
            val property = argument.removePrefix("-D").substringBefore('=')
            if (NetworkProperty.entries.any { it.key == property }) {
                put(property, argument.substringAfter('=', ""))
            }
        }
    }
}
