package io.github.amichne.kast.distribution.contract

import kotlinx.serialization.Serializable

/** The existing replacement journal binds staged Control bytes before retirement. */
@Serializable
data class ManagedControlReservation(
    val transaction: String,
    val staged: String,
    val controlSha256: String,
    val version: String,
    val manifestSha256: String,
) {
    init {
        require(Regex("[0-9a-f-]{36}").matches(transaction))
        require(listOf(controlSha256, manifestSha256).all { Regex("[0-9a-f]{64}").matches(it) })
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
    }
}
