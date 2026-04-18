package io.github.amichne.kast.cli

internal class DemoCommandSupport {
    fun plan(options: DemoOptions): CliExternalProcess {
        throw CliFailure(
            code = "NOT_YET_IMPLEMENTED",
            message = "The native `kast demo` command is not yet implemented. " +
                "The legacy demo.sh shell script has been removed.",
        )
    }
}
