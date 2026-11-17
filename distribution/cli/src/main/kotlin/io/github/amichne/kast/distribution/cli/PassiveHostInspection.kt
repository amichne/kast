package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Serializable

@Serializable
internal enum class PassiveHostStep {
    GENERATION,
    SELECTION,
    DESCRIPTOR_READ,
    DESCRIPTOR_DECODE,
    DESCRIPTOR_ADMISSION,
    PROCESS_LOOKUP,
    PROCESS_IDENTITY,
    DESCRIPTOR_RECHECK,
    COMPLETE,
}

@Serializable
internal enum class PassiveDescriptorState {
    UNEXAMINED,
    UNAVAILABLE,
    REJECTED,
    ADMITTED,
    STABILITY_UNPROVEN,
}

@Serializable
internal enum class PassiveProcessState {
    UNEXAMINED,
    UNAVAILABLE,
    IDENTITY_MISMATCH,
    MATCHED,
}

@Serializable
internal enum class PassiveProbeState {
    UNEXAMINED
}

@Serializable
internal data class PassiveHostStages(
    val stage: PassiveHostStep,
    val descriptor: PassiveDescriptorState,
    val process: PassiveProcessState,
    val transport: PassiveProbeState = PassiveProbeState.UNEXAMINED,
    val semanticReadiness: PassiveProbeState = PassiveProbeState.UNEXAMINED,
) {
    companion object {
        fun at(stage: PassiveHostStep): PassiveHostStages =
            when (stage) {
                PassiveHostStep.GENERATION,
                PassiveHostStep.SELECTION ->
                    PassiveHostStages(stage, PassiveDescriptorState.UNEXAMINED, PassiveProcessState.UNEXAMINED)
                PassiveHostStep.DESCRIPTOR_READ ->
                    PassiveHostStages(stage, PassiveDescriptorState.UNAVAILABLE, PassiveProcessState.UNEXAMINED)
                PassiveHostStep.DESCRIPTOR_DECODE,
                PassiveHostStep.DESCRIPTOR_ADMISSION ->
                    PassiveHostStages(stage, PassiveDescriptorState.REJECTED, PassiveProcessState.UNEXAMINED)
                PassiveHostStep.PROCESS_LOOKUP ->
                    PassiveHostStages(stage, PassiveDescriptorState.ADMITTED, PassiveProcessState.UNAVAILABLE)
                PassiveHostStep.PROCESS_IDENTITY ->
                    PassiveHostStages(stage, PassiveDescriptorState.ADMITTED, PassiveProcessState.IDENTITY_MISMATCH)
                PassiveHostStep.DESCRIPTOR_RECHECK ->
                    PassiveHostStages(stage, PassiveDescriptorState.STABILITY_UNPROVEN, PassiveProcessState.MATCHED)
                PassiveHostStep.COMPLETE ->
                    PassiveHostStages(stage, PassiveDescriptorState.ADMITTED, PassiveProcessState.MATCHED)
            }
    }
}

internal sealed interface PassiveHostInspection {
    val observation: Observation<LoadedApplicationHost>
    val stage: PassiveHostStep

    class Loaded(host: LoadedApplicationHost) : PassiveHostInspection {
        override val observation = Observation.verified(host)
        override val stage = PassiveHostStep.COMPLETE
    }

    class Rejected(reason: ApplicationHostFailure, override val stage: PassiveHostStep) : PassiveHostInspection {
        override val observation = Observation.unavailable<LoadedApplicationHost>(reason.name)
    }
}
