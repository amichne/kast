package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.output.cliOutputRejection
import io.github.amichne.kast.distribution.output.renderCliOutput
import io.github.amichne.kast.kernel.Refinement
import kotlin.system.exitProcess
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal class ManagementOutput(val format: CliOutputFormat = CliOutputFormat.JSON) {
    fun structured(json: Boolean): Boolean = json || format == CliOutputFormat.TOON

    fun publish(document: String) {
        when (val rendered = renderCliOutput(document, format)) {
            is Refinement.Refined -> println(rendered.value)
            is Refinement.Rejected -> {
                println(cliOutputRejection(rendered.failure, format))
                exitProcess(OUTPUT_ENCODING_EXIT)
            }
        }
    }

    fun message(text: String) {
        when (format) {
            CliOutputFormat.JSON -> println(text)
            CliOutputFormat.TOON -> publish(managementJson.encodeToString(ManagementMessage(text)))
        }
    }

    fun rejected(text: String) {
        if (format == CliOutputFormat.TOON) publish(managementJson.encodeToString(ManagementOutputRejection(text)))
    }
}

@Serializable private data class ManagementMessage(val message: String, @Required val type: String = "complete")

@Serializable private data class ManagementOutputRejection(val message: String, @Required val type: String = "rejected")

private const val OUTPUT_ENCODING_EXIT = 6
