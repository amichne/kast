package io.github.amichne.kast.idea.edit

import com.intellij.openapi.vcs.VcsShowConfirmationOption

internal class VcsConfirmationOverride(
    private val option: VcsShowConfirmationOption,
    private val suppressedValue: VcsShowConfirmationOption.Value,
) {
    private val previousValue: VcsShowConfirmationOption.Value = option.value

    fun apply() {
        option.value = suppressedValue
    }

    fun restore() {
        option.value = previousValue
    }
}
