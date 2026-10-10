package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter

/** Explicit zero observations distinguish current capabilities from absent older-runtime evidence. */
internal fun initialHostedReadCounters(): LinkedHashMap<Pair<IntellijReadCounter, IntellijReadContributor>, Long> =
    linkedMapOf<Pair<IntellijReadCounter, IntellijReadContributor>, Long>().apply {
        listOf(
                IntellijReadCounter.DEPENDENCY_TREE_ENTRIES_VISITED,
                IntellijReadCounter.DEPENDENCY_SDK_PLANNED_FILES,
                IntellijReadCounter.DEPENDENCY_SDK_MINIMUM_HASH_READS,
                IntellijReadCounter.DEPENDENCY_SDK_FILE_PLANS_COMPLETED,
                IntellijReadCounter.DEPENDENCY_HASH_BYTES_READ,
                IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED,
                IntellijReadCounter.DEPENDENCY_HASH_MEMO_HITS,
                IntellijReadCounter.DEPENDENCY_TREE_MEMO_HITS,
                IntellijReadCounter.DECLARATION_PSI_NODES_VISITED,
                IntellijReadCounter.RELATION_SCOPE_FILES_ADMITTED,
                IntellijReadCounter.RELATION_SCOPE_FILES_EXCLUDED,
                IntellijReadCounter.RELATION_SCOPE_MODULES_ADMITTED,
                IntellijReadCounter.RELATION_SCOPE_MODULES_EXCLUDED,
                IntellijReadCounter.RELATION_SCOPE_MODULE_SOURCE_KINDS_ADMITTED,
                IntellijReadCounter.RELATION_SCOPE_MODULE_SOURCE_KINDS_EXCLUDED,
                IntellijReadCounter.RELATION_SCOPE_LIBRARY_SEARCH_ADMITTED,
                IntellijReadCounter.RELATION_SCOPE_LIBRARY_SEARCH_EXCLUDED,
            )
            .forEach { this[it to IntellijReadContributor.NONE] = 0L }
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
        callbackAndSemanticCounters().forEach { this[it to IntellijReadContributor.NONE] = 0L }
    }

private fun callbackAndSemanticCounters(): List<IntellijReadCounter> =
    listOf(
        IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_STARTED,
        IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_COMPLETED,
        IntellijReadCounter.CALLBACK_SUPPLIER_PARTITIONS_REJECTED,
        IntellijReadCounter.CALLBACK_SUPPLIER_CALLS_EXAMINED,
        IntellijReadCounter.CALLBACK_SUPPLIER_VALUES_CONFIRMED,
        IntellijReadCounter.CALLBACK_BODY_SCANS,
        IntellijReadCounter.CALLBACK_BODY_SCANS_COMPLETED,
        IntellijReadCounter.CALLBACK_BODY_SCANS_INCOMPLETE,
        IntellijReadCounter.CALLBACK_SUMMARY_HITS,
        IntellijReadCounter.CALLBACK_SUMMARY_MISSES,
        IntellijReadCounter.CALLBACK_SUMMARY_REJECTIONS,
        IntellijReadCounter.CALLBACK_SUMMARIES_RETAINED,
        IntellijReadCounter.CALLBACK_SUMMARY_RETENTION_REJECTIONS,
        IntellijReadCounter.NAMED_CALLBACK_REFERENCES_CONFIRMED,
        IntellijReadCounter.NAMED_CALLBACK_REFERENCES_REJECTED,
        IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_EXTRACTED,
        IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_REUSED,
        IntellijReadCounter.SEMANTIC_FACT_PARTITIONS_INVALIDATED,
        IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS,
        IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS,
        IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_PUBLISHED,
        IntellijReadCounter.SEMANTIC_FACT_GENERATIONS_REJECTED,
        IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_EXTRACTED,
        IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_REUSED,
        IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INVALIDATED,
        IntellijReadCounter.SEMANTIC_FACT_NAMED_PARTITIONS_INELIGIBLE,
        IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_EXTRACTED,
        IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED,
        IntellijReadCounter.SEMANTIC_FACT_SUPPLIER_INVENTORIES_INVALIDATED,
        IntellijReadCounter.CALLBACK_FORWARDING_FORMALS,
        IntellijReadCounter.CALLBACK_FORWARDING_EDGES,
        IntellijReadCounter.CALLBACK_FIXED_POINTS_COMPLETED,
        IntellijReadCounter.CALLBACK_FIXED_POINTS_REJECTED,
        IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED,
        IntellijReadCounter.QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS,
        IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED,
    )
