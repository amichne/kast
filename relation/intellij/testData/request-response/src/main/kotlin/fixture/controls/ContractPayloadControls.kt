@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package fixture.controls

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class ContractPayload

fun contractPayload(): ContractPayload = ContractPayload()

fun keepContractResult(block: () -> ContractPayload): ContractPayload {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return block()
}

fun discardContractResult(block: () -> ContractPayload): ContractPayload {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    block()
    return ContractPayload()
}

fun keptContractPayload(): ContractPayload {
    val output = keepContractResult { contractPayload() }
    return /*<output>*/output/*</output>*/
}

fun discardedContractPayload(): ContractPayload {
    val output = discardContractResult { contractPayload() }
    return /*<output>*/output/*</output>*/
}

fun contract(block: () -> ContractPayload, marker: Int): ContractPayload {
    block()
    return ContractPayload()
}

fun discardedLookalikePayload(): ContractPayload {
    val output = contract({ contractPayload() }, 1)
    return /*<output>*/output/*</output>*/
}
