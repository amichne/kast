package diagnostic.identity

open class OverrideParent {
    open fun overrideTarget(value: Int): Int = value
}

class OverrideChild : OverrideParent() {
    override fun overrideTarget(value: Int): Int = super.overrideTarget(value)
}

fun directOverrideProbe(): Int = OverrideChild().overrideTarget(1)
