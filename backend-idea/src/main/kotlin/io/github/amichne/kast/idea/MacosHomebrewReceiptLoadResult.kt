package io.github.amichne.kast.idea

internal sealed interface MacosHomebrewReceiptLoadResult {
    data class Loaded(val receipt: MacosHomebrewInstallReceipt) : MacosHomebrewReceiptLoadResult

    data class Rejected(
        val failure: MacosHomebrewReceiptFailure,
        val message: String,
    ) : MacosHomebrewReceiptLoadResult
}
