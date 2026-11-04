package io.github.amichne.kast.cli.publicshell

internal fun publicCliHelp(command: PublicCliCommandId?): String =
    if (command == null) {
        "Kast: compiler-grounded Kotlin queries.\n" +
            PublicCliCommandId.entries.joinToString("\n") { "  kast ${it.argv.joinToString(" ")}" } +
            "\nUse kast COMMAND --help for flags, or kast describe COMMAND for the contract summary.\n" +
            "Semantic commands emit one TOON document. Use --json for piping JSON; diagnostics use stderr."
    } else publicCliHelp(listOf(command))

internal fun publicCliHelp(commands: List<PublicCliCommandId>): String =
    commands.joinToString("\n\n") { selected ->
        "kast ${selected.argv.joinToString(" ")} " +
            flagsFor(selected).joinToString(" ") { flag ->
                listOf("[", flag.spelling, if (flag.takesValue) " VALUE" else "", "]").joinToString("")
            } +
            "\nContract: kast describe ${selected.id}" +
            "\nSchema page: kast describe ${selected.id} --section input --pointer '' --json"
    } +
        "\nUse --json for JSON stdout. Progress and diagnostics use stderr.\n" +
        "Schema pages default to 16 entries and 16384 bytes. Follow pointers and next_cursor for more.\n" +
        when {
            commands.singleOrNull() == PublicCliCommandId.DIAGNOSTIC_CHECK ->
                "Select a file or directory with --path; use --path . explicitly for the workspace root.\n" +
                    "Example: kast diagnostic check --path src/main/kotlin --max-diagnostics 100 --json\n" +
                    "--request FILE or --request - accepts the packaged diagnostic input. Preserve incomplete coverage."
            commands.any { it == PublicCliCommandId.QUERY || it == PublicCliCommandId.QUERY_PLAN } ->
                "--request FILE reads JSON from a file; --request - reads JSON from stdin.\n" +
                    "Example: kast query --request - --json < query.json\n" +
                    "--fields accepts unique values: ${CliField.entries.joinToString(",") { it.spelling }}; " +
                    "use with --output symbols.\n" +
                    "Bound presentation with --max-results and --max-returned-bytes; preserve qualifications.\n" +
                    "Use kast describe query --section examples --json for request documents."
            commands.any { it == PublicCliCommandId.RESULT_READ || it == PublicCliCommandId.RESULT_RESUME } ->
                "Use issued result references, both cursors, or an issued output continuation.\n" +
                    "One command reads one bounded page; it does not drain later pages or rerun the query."
            commands.singleOrNull() == PublicCliCommandId.DESCRIBE ->
                "Use --section input or output with --pointer '' for the root, or a returned JSON Pointer.\n" +
                    "Schema pages accept --cursor, --max-results 1..128, and --max-returned-bytes 1024..65536.\n" +
                    "Omit --pointer to export the full section: kast describe query --section input --json > input-schema.json"
            else -> "Use an issued source reference. Bound source text with --max-returned-bytes."
        }
