package semanticowners
fun target() {}

var supply: Int = 0
    set(value) {
        kotlin.lazy { target() }
        field = value
    }
