package io.github.amichne.kast.relation.contract

internal fun deferredExecutionEdges(execution: CallbackDeferredExecution): List<StaticCallbackEdge> =
    when (execution) {
        CallbackDeferredExecution.Declaration,
        is CallbackDeferredExecution.Accessor,
        is CallbackDeferredExecution.Initializer -> emptyList()
        is CallbackDeferredExecution.Invoked ->
            execution.route.steps.map { step ->
                val target = StaticCallbackNode.Anonymous(step.body)
                when (val owner = step.binding.owner) {
                    is RelationCallableBody.Named ->
                        StaticCallbackEdge.DirectInvoke(StaticCallbackNode.Named(owner), target, step.binding)
                    is RelationCallableBody.Anonymous ->
                        StaticCallbackEdge.NestedDirectInvoke(StaticCallbackNode.Anonymous(owner), target, step.binding)
                }
            }
    }

internal fun StringBuilder.deferredExecution(execution: CallbackDeferredExecution) {
    when (execution) {
        CallbackDeferredExecution.Declaration -> field("DECLARATION")
        is CallbackDeferredExecution.Accessor -> {
            field(execution.kind.name)
            occurrence(execution.occurrence)
        }
        is CallbackDeferredExecution.Initializer -> {
            field("INITIALIZER")
            occurrence(execution.occurrence)
        }
        is CallbackDeferredExecution.Invoked -> {
            field("INVOKED")
            for (step in execution.route.steps) {
                body(step.binding.owner)
                occurrence(step.binding.occurrence)
                body(step.body)
            }
        }
    }
}
