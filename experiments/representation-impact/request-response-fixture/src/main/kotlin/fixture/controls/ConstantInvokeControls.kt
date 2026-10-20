package fixture.controls

class ConstantInvocation {
    operator fun invoke(value: String): String = "constant"
}

object ConstantInvokeControls {
    fun apply(source: String): String {
        val operation = ConstantInvocation()
        return operation(source)
    }
}
