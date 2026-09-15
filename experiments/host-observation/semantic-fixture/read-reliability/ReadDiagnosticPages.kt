package reliability.diagnostic

@Deprecated("Diagnostic paging fixture")
fun diagnosticLegacyValue(): Int = 1

fun diagnosticRepeatedWarnings(): Int {
    val first = diagnosticLegacyValue()
    val second = diagnosticLegacyValue()
    val third = diagnosticLegacyValue()
    return first + second + third
}
