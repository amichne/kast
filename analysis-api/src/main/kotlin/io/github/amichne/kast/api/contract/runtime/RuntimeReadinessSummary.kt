package io.github.amichne.kast.api.contract

sealed interface RuntimeReadinessSummary {
    data object Ready : RuntimeReadinessSummary

    data object NotReady : RuntimeReadinessSummary

    companion object {
        /**
         * Proof transition: `RuntimeReadiness -> RuntimeReadinessSummary`.
         *
         * Collapses the eight typed lanes into a closed exhaustive summary;
         * callers retain the summary as proof instead of passing a Boolean.
         */
        fun derive(readiness: RuntimeReadiness): RuntimeReadinessSummary = if (
            readiness.runtimeLane is CurrentCapabilityLaneReadiness.Available &&
            readiness.modelLane is CurrentCapabilityLaneReadiness.Available &&
            readiness.workspaceFilesLane is CurrentCapabilityLaneReadiness.Available &&
            readiness.compilerLane is CurrentCapabilityLaneReadiness.Available &&
            readiness.sourceIndexLane is RetainedCapabilityLaneReadiness.Available &&
            readiness.referencesLane is RetainedCapabilityLaneReadiness.Available &&
            readiness.semanticGraphLane is RetainedCapabilityLaneReadiness.Available &&
            readiness.mutationLane is CurrentCapabilityLaneReadiness.Available
        ) {
            Ready
        } else {
            NotReady
        }
    }
}
