package fixture.controls

fun subtract(left: Int, right: Int): Int = left - right
fun explicitSubtract(left: Int, right: Int): Int = left.minus(right)

fun add(left: Int, right: Int): Int = left + right
fun multiply(left: Int, right: Int): Int = left * right
fun minimum(left: Int, right: Int): Int = kotlin.math.min(left, right)
fun maximum(left: Int, right: Int): Int = kotlin.math.max(a = left, b = right)
