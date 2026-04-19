package io.github.amichne.kast.cli

internal class SmokeCommandSupport {
    fun plan(options: SmokeOptions): CliExternalProcess {
        throw CliFailure(
            code = "NOT_YET_IMPLEMENTED",
            message = "The native `kast smoke` command is not yet implemented. " +
                "The legacy smoke.sh shell script has been removed.",
        )
    }
}
