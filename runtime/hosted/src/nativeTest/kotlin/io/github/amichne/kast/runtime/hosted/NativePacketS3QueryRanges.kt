package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument

/** UTF-16 ranges refer only to the unchanged original packet files. */
internal object NativePacketS3QueryRanges {
    val adapt = range(ADAPT_START, ADAPT_END)
    val property = range(PROPERTY_START, PROPERTY_END)
    val binding = range(BINDING_START, ADAPT_END)
    val logRead = range(LOG_READ_START, LOG_READ_END)
    val logInvoke = range(LOG_INVOKE_START, LOG_INVOKE_END)
    val logBody = range(LOG_BODY_START, LOG_BODY_END)
    val logIt = range(LOG_IT_START, LOG_IT_END)
    val logHelper = range(LOG_IT_START, LOG_HELPER_END)
    val executeReturn = range(RETURN_START, RETURN_END)
    val successValue = range(SUCCESS_VALUE_START, SUCCESS_VALUE_END)
    val errorValue = range(ERROR_VALUE_START, ERROR_VALUE_END)
    val statusCode = range(SUCCESS_VALUE_START, STATUS_CODE_END)
    val errorCode = range(ERROR_VALUE_START, ERROR_CODE_END)

    private fun range(start: Int, end: Int) =
        ImpactSourceRangeDocument(packetQueryOffset(start), packetQueryOffset(end))

    private const val ADAPT_START = 761
    private const val ADAPT_END = 768
    private const val PROPERTY_START = 594
    private const val PROPERTY_END = 642
    private const val BINDING_START = 748
    private const val LOG_READ_START = 793
    private const val LOG_READ_END = 799
    private const val LOG_INVOKE_START = 789
    private const val LOG_INVOKE_END = 800
    private const val LOG_BODY_START = 925
    private const val LOG_BODY_END = 943
    private const val LOG_IT_START = 927
    private const val LOG_IT_END = 929
    private const val LOG_HELPER_END = 941
    private const val RETURN_START = 817
    private const val RETURN_END = 823
    private const val SUCCESS_VALUE_START = 1700
    private const val SUCCESS_VALUE_END = 1705
    private const val ERROR_VALUE_START = 1751
    private const val ERROR_VALUE_END = 1756
    private const val STATUS_CODE_END = 1716
    private const val ERROR_CODE_END = 1761
}
