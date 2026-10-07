package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Explicit zero observations distinguish current capabilities from absent older-runtime evidence. */
internal fun initialHostedReadCounters(): LinkedHashMap<Pair<IntellijReadCounter, IntellijReadContributor>, Long> =
    linkedMapOf<Pair<IntellijReadCounter, IntellijReadContributor>, Long>().apply {
        // Explicit zeros prove page observation capability even when a workload never enters that provider.
        this[IntellijReadCounter.NATIVE_DISCOVERY_PAGES to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.NATIVE_RELATION_PAGES to IntellijReadContributor.NONE] = 0L
        // Retained value evidence reads must prove that no compiler provider was replayed.
        this[IntellijReadCounter.VALUE_PRODUCER_SEED_READS to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.VALUE_MODEL_REVALIDATIONS to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.VALUE_MODEL_SITE_REVALIDATIONS_REJECTED to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.PEER_SITE_READS_STARTED to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.PEER_SITE_READS_COMPLETED to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.PEER_SITE_READS_REJECTED to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.VALUE_FLOW_READS to IntellijReadContributor.NONE] = 0L
        // Both alternatives must remain observable when comparing locator retention across reads.
        this[IntellijReadCounter.REVALIDATION_LOCATORS_RETAINED to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.REVALIDATION_LOCATORS_REJECTED to IntellijReadContributor.NONE] = 0L
        this[IntellijReadCounter.EPOCH_READ_PREEMPTIONS to IntellijReadContributor.NONE] = 0L
        // Zero is observed only by this vocabulary's runtime; absent old-runtime counters remain unmeasured.
        listOf(
                IntellijReadCounter.CALLBACK_BODY_SCANS,
                IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED,
                IntellijReadCounter.CALLBACK_BODY_SCANS_INCOMPLETE,
                IntellijReadCounter.CALLBACK_SUMMARY_HITS,
                IntellijReadCounter.CALLBACK_SUMMARY_MISSES,
                IntellijReadCounter.CALLBACK_SUMMARY_REJECTIONS,
                IntellijReadCounter.CALLBACK_SUMMARIES_RETAINED,
                IntellijReadCounter.CALLBACK_SUMMARY_RETENTION_REJECTIONS,
                IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED,
                IntellijReadCounter.QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS,
                IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED,
            )
            .forEach { this[it to IntellijReadContributor.NONE] = 0L }
    }
