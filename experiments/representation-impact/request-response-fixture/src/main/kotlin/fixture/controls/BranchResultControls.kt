package fixture.controls.branchresult

class BranchResultControls {
    fun readValue(): String = "tracked"

    fun constantValue(): String = "constant"

    fun never(): Nothing = throw IllegalStateException("abrupt")

    fun nullValue(): Nothing? = null

    fun normal(flag: Boolean): String = if (flag) readValue() else constantValue()

    fun abruptArm(flag: Boolean): String = if (flag) never() else constantValue()

    fun nullableArm(flag: Boolean): String? = if (flag) nullValue() else constantValue()

    fun unitCoercion(flag: Boolean) {
        if (flag) readValue() else constantValue()
    }

    fun discarded(flag: Boolean): String {
        if (flag) readValue() else constantValue()
        return "after"
    }
}
