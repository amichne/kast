package diagnostic.identity

class SecondaryConstructorTarget private constructor(val value: Int) {
    constructor() : this(0)
}

fun secondaryConstructorProbe(): SecondaryConstructorTarget = SecondaryConstructorTarget()
