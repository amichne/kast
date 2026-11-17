package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.configuration.HostHeapPolicy
import io.github.amichne.kast.distribution.contract.configuration.HostMaxHeap
import io.github.amichne.kast.kernel.Refinement

internal enum class ManagedHeadlessHeapSource {
    EXPLICIT_KAST,
    PRIVATE_IDEA_PROFILE,
    BUNDLED_IDEA,
    KAST_FALLBACK,
}

internal class ManagedHeadlessHeap private constructor(val value: HostMaxHeap, val source: ManagedHeadlessHeapSource) {
    companion object {
        fun select(
            policy: HostHeapPolicy,
            custom: List<String>,
            bundled: List<String>,
        ): Refinement<ManagedHeadlessHeap, ManagedHeadlessLaunchFailure> {
            when (policy) {
                is HostHeapPolicy.Explicit ->
                    return Refinement.Refined(ManagedHeadlessHeap(policy.heap, ManagedHeadlessHeapSource.EXPLICIT_KAST))
                HostHeapPolicy.Inherit -> Unit
            }

            val candidates =
                listOf(
                    ManagedHeadlessHeapSource.PRIVATE_IDEA_PROFILE to custom,
                    ManagedHeadlessHeapSource.BUNDLED_IDEA to bundled,
                )
            for ((source, lines) in candidates) {
                val options = lines.map(String::trim).filter { it.startsWith("-Xmx") }
                if (options.isEmpty()) continue
                if (options.size != 1) return Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA)
                return when (val parsed = HostMaxHeap.parseVmOption(options.single())) {
                    is Refinement.Refined -> Refinement.Refined(ManagedHeadlessHeap(parsed.value, source))
                    is Refinement.Rejected -> Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_METADATA)
                }
            }
            return Refinement.Refined(ManagedHeadlessHeap(HostMaxHeap.DEFAULT, ManagedHeadlessHeapSource.KAST_FALLBACK))
        }
    }
}
