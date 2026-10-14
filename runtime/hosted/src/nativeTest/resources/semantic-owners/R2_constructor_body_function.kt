package semanticowners
fun target() {}

class Owner {
    constructor() {
        kotlin.lazy { target() }
    }
}
