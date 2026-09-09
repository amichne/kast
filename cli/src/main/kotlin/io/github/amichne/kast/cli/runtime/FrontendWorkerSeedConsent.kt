package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** Keeps the existing measured disclosure and terminal consent boundary in the requesting CLI. */
internal object FrontendWorkerSeedConsent : WorkerSeedConsentAuthority {
    override suspend fun request(disclosure: WorkerSeedDisclosure): WorkerSeedConsent = runInterruptible(Dispatchers.IO) {
        val size = IndexSeedEstimatedBytes.from(disclosure.estimatedBytes) ?: return@runInterruptible WorkerSeedConsent.ABSENT
        val local = IndexSeedDisclosure.fixed(disclosure.categories.map { category -> when (category) {
            WorkerSeedCategory.GLOBAL_VFS -> IndexSeedCategory.GLOBAL_VFS
            WorkerSeedCategory.GLOBAL_INDEXES -> IndexSeedCategory.GLOBAL_INDEXES
            WorkerSeedCategory.PROJECT_MODEL -> IndexSeedCategory.PROJECT_MODEL
            WorkerSeedCategory.CLASSPATH_METADATA -> IndexSeedCategory.CLASSPATH_METADATA
        } }.toSet(), size)
        when (ConsoleIndexSeedConsentProvider.request(local)) {
            IndexSeedConsent.GRANTED -> WorkerSeedConsent.GRANTED
            IndexSeedConsent.ABSENT -> WorkerSeedConsent.ABSENT
        }
    }
}

internal fun WorkerSeedConsentAuthority.seedProvider(): IndexSeedConsentProvider = IndexSeedConsentProvider { disclosure ->
    val selected = WorkerSeedDisclosure.admit(disclosure.categories.map { category -> when (category) {
        IndexSeedCategory.GLOBAL_VFS -> WorkerSeedCategory.GLOBAL_VFS
        IndexSeedCategory.GLOBAL_INDEXES -> WorkerSeedCategory.GLOBAL_INDEXES
        IndexSeedCategory.PROJECT_MODEL -> WorkerSeedCategory.PROJECT_MODEL
        IndexSeedCategory.CLASSPATH_METADATA -> WorkerSeedCategory.CLASSPATH_METADATA
    } }.toSet(), disclosure.estimatedBytes.value)
    when (selected) {
        is Refinement.Rejected -> IndexSeedConsent.ABSENT
        is Refinement.Refined -> when (runBlocking { request(selected.value) }) {
            WorkerSeedConsent.GRANTED -> IndexSeedConsent.GRANTED
            WorkerSeedConsent.ABSENT -> IndexSeedConsent.ABSENT
        }
    }
}
