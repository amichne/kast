package io.github.amichne.kast.cli.publicshell

/** Bounded guidance never repeats request payloads, paths, or enterprise identifiers. */
internal fun publicCliArgumentDiagnostic(failure: PublicCliArgumentFailure): String =
    "kast: ${failure.name}. " +
        when (failure) {
            PublicCliArgumentFailure.INVALID_COMMAND ->
                "Use kast --help, kast source --help, or kast result --help for supported commands."
            PublicCliArgumentFailure.UNKNOWN_OPTION -> "Use kast query --help for supported flags."
            PublicCliArgumentFailure.MISSING_VALUE ->
                "Supply each necessary flag value. Use kast --help for command syntax."
            PublicCliArgumentFailure.DUPLICATE_OPTION -> "Supply each nonrepeatable flag once."
            PublicCliArgumentFailure.INVALID_VALUE -> "Use kast describe query for accepted flag values."
            PublicCliArgumentFailure.MIXED_INPUT_MODES ->
                "Use --request FILE or --request - without semantic flags or --verbose."
            PublicCliArgumentFailure.INVALID_SOURCE -> "Select one source: --name, --ref, or --file with --offset."
            PublicCliArgumentFailure.IRRELEVANT_OPTION -> "Use kast describe for flags supported by each command."
            PublicCliArgumentFailure.INVALID_FIELDS ->
                "Use unique, comma-separated --fields values: ${CliField.entries.joinToString(",") { it.spelling }}."
            PublicCliArgumentFailure.INVALID_SCHEMA_SELECTION ->
                "Use --section input or output with --pointer (empty for root), --cursor >= 0, " +
                    "--max-results 1..128, and --max-returned-bytes 1024..65536. Pointers use JSON Pointer syntax."
            PublicCliArgumentFailure.FIELDS_WITH_OCCURRENCES ->
                "Use --fields with --output symbols. Remove --fields for --output occurrences."
        }

internal fun publicCliInputDiagnostic(failure: PublicInputReadFailure): String =
    "kast: " +
        when (failure) {
            PublicInputReadFailure.IO ->
                "Request input read failed. Use --request FILE for a readable JSON file or --request - for JSON on stdin."
            PublicInputReadFailure.INVALID ->
                "Request input is invalid. Supply one UTF-8 JSON argument document with a request field."
            PublicInputReadFailure.TOO_LARGE -> "Request input exceeds the protocol size limit."
        }
