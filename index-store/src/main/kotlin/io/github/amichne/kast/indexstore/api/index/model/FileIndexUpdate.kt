package io.github.amichne.kast.indexstore.api.index

/**
 * Represents all identifier-index data for a single file.
 */
data class FileIndexUpdate(
    val path: String,
    val identifiers: Set<String>,
    val packageName: String?,
    val modulePath: String?,
    val sourceSet: String?,
    val imports: Set<String>,
    val wildcardImports: Set<String>,
    val gradleProjects: Set<BuildQualifiedGradleProjectIdentity> = emptySet(),
    val gradleSourceSets: Set<BuildQualifiedGradleSourceSetIdentity> = emptySet(),
    val packageEvidence: IndexedPackageEvidence = IndexedPackageEvidence.Unproven(
        IndexedPackageUnprovenReason.NOT_SCANNED,
    ),
) {
    init {
        require(gradleSourceSets.all { sourceSet -> sourceSet.project in gradleProjects }) {
            "Every Gradle source-set identity must retain its build-qualified project owner"
        }
    }
}
