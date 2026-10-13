@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryNanosecondsDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryOrderDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryProgressDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryStopDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryUniverseDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Omitted page measurements mean that this presentation does not supply them. */
@Serializable
@ConsistentCopyVisibility
internal data class QueryDiscoveryObservationCliDocument
private constructor(
    val universe: QueryDiscoveryUniverseDocument,
    val ordering: QueryDiscoveryOrderDocument,
    val progress: QueryDiscoveryProgressDocument,
    @SerialName("observed_stops") val observedStops: BoundedProtocolList<QueryDiscoveryStopDocument>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("examined_work_units")
    val examinedWorkUnits: QueryDiscoveryCountDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("inventory_files")
    val inventoryFiles: QueryDiscoveryCountDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("reacquired_files")
    val reacquiredFiles: QueryDiscoveryCountDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("examined_leaves")
    val examinedLeaves: QueryDiscoveryCountDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("inventory_nanoseconds")
    val inventoryNanoseconds: QueryDiscoveryNanosecondsDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("declaration_scan_nanoseconds")
    val declarationScanNanoseconds: QueryDiscoveryNanosecondsDocument? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("projection_nanoseconds")
    val projectionNanoseconds: QueryDiscoveryNanosecondsDocument? = null,
) {
    fun compact(): QueryDiscoveryObservationCliDocument =
        copy(
            examinedWorkUnits = null,
            inventoryFiles = null,
            reacquiredFiles = null,
            examinedLeaves = null,
            inventoryNanoseconds = null,
            declarationScanNanoseconds = null,
            projectionNanoseconds = null,
        )

    companion object {
        fun from(value: QueryDiscoveryObservationDocument): QueryDiscoveryObservationCliDocument =
            QueryDiscoveryObservationCliDocument(
                value.universe,
                value.ordering,
                value.progress,
                value.observedStops,
                value.examinedWorkUnits,
                value.inventoryFiles,
                value.reacquiredFiles,
                value.examinedLeaves,
                value.inventoryNanoseconds,
                value.declarationScanNanoseconds,
                value.projectionNanoseconds,
            )
    }
}
