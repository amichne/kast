package io.github.amichne.kast.workspace.intellij.read.hosted

import kotlinx.serialization.Serializable

@Serializable internal class EmptyDetail

@Serializable internal data class CauseDetail(val cause: String)

@Serializable internal data class StageDetail(val stage: String)

@Serializable internal data class ParameterDetail(val cause: String, val parameter: String)

@Serializable internal data class BoundsDetail(val cause: String, val inner: String, val outer: String)

@Serializable internal data class CompatibilityDetail(val stage: String, val field: String)

@Serializable internal data class ObservationDetail(val cause: String, val stage: String)
