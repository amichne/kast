package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.distribution.output.JsonProjectionDocument
import io.github.amichne.kast.kernel.Refinement

/** Successfully syntax-checked embedded jq program; each bounded stage runs in an owned worker process. */
internal class PublicJqEngine
private constructor(
    private val options: PublicJqOptions,
    private val executor: PublicJqExecutor,
) {
    fun project(prepared: PublicPreparedOutput): Refinement<PublicPreparedOutput, PublicJqRejection> {
        if (prepared.exit != PublicShellExit.COMPLETE || prepared.content != PublicOutputContent.DOCUMENT)
            return Refinement.Refined(prepared)
        val request = request(PublicJqStage.FILTER, prepared.text)
        val captured =
            when (val result = executor.execute(request)) {
                is Refinement.Rejected -> return Refinement.Rejected(result.failure.copy(recovery = prepared.recovery))
                is Refinement.Refined -> result.value
            }
        if (captured.childExit != 0)
            return Refinement.Rejected(workerRejection(captured, request).copy(recovery = prepared.recovery))
        if (JsonProjectionDocument.parse(captured.stdout.trim()) is Refinement.Rejected)
            return Refinement.Rejected(
                jqRejection(PublicJqFailure.OUTPUT_INVALID, PublicJqStage.FILTER, options.limits)
                    .copy(recovery = prepared.recovery)
            )
        return Refinement.Refined(
            prepared.copy(
                text = captured.stdout.trim(),
                content = PublicOutputContent.Projection(options.limits, prepared.recovery),
            )
        )
    }

    private fun request(stage: PublicJqStage, input: String): PublicJqProcessRequest =
        PublicJqProcessRequest(stage, options.workerArgument, input, options.limits)

    companion object {
        fun prepare(
            options: PublicJqOptions,
            executor: PublicJqExecutor = PublicJqWorkers.process,
        ): Refinement<PublicJqEngine, PublicJqRejection> {
            val engine = PublicJqEngine(options, executor)
            val request = engine.request(PublicJqStage.COMPILE, "")
            return when (val observed = executor.execute(request)) {
                is Refinement.Rejected -> observed
                is Refinement.Refined ->
                    if (observed.value.childExit != 0) Refinement.Rejected(workerRejection(observed.value, request))
                    else if (observed.value.stdout.isNotEmpty())
                        Refinement.Rejected(
                            jqRejection(PublicJqFailure.OUTPUT_INVALID, PublicJqStage.COMPILE, options.limits)
                        )
                    else Refinement.Refined(engine)
            }
        }
    }
}

private fun workerRejection(captured: PublicJqCapture, request: PublicJqProcessRequest): PublicJqRejection {
    val cause =
        PublicJqWorkerExit.entries.find { it.code == captured.childExit }?.failure ?: PublicJqFailure.WORKER_FAILED
    return jqRejection(cause, request.stage, request.limits, captured.childExit)
}
