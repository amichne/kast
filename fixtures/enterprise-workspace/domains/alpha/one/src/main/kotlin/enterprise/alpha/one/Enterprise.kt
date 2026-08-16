package enterprise.alpha.one

class EnterpriseNode

object EnterpriseRouter {
    fun enterpriseRouteOverload(value: Int): Int {
        return value
    }

    fun enterpriseRouteOverload(value: String): String {
        return value
    }
}

fun enterpriseRootOperation(): Int =
    enterpriseFirstOperation() + enterpriseSecondOperation()

fun enterpriseFirstOperation(): Int = enterpriseLeafOperation()

fun enterpriseSecondOperation(): Int = enterpriseLeafOperation()

fun enterpriseLeafOperation(): Int = 1
