package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument

internal fun CliExit.present(detail: ToolOutputDetail): CliExit =
    when (this) {
        is CliExit.Complete -> copy(document = (document as? CanonicalJsonDocument)?.present(detail) ?: document)
        is CliExit.Qualified -> copy(document = document.present(detail))
        is CliExit.OperationRejected -> copy(document = document.present(detail))
        is CliExit.BoundaryRejected -> copy(document = document.present(detail))
        is CliExit.Delegated -> this
    }
