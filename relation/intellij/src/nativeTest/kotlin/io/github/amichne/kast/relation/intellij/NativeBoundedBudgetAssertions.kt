package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ResourceBudget

internal fun ResourceBudget.nativeBounded(): ResourceBudget.Bounded =
    when (this) {
        is ResourceBudget.Bounded -> this
        ResourceBudget.RunnerControlled -> error("This native case needs a bounded grant")
    }
