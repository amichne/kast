package semanticowners
class Target

fun entry() {
    (fun() {
        (fun() {
            kotlin.lazy { Target() }
        })()
    })()
}
