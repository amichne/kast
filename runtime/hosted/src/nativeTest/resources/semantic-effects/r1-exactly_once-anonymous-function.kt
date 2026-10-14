package consumer

fun exactlyOnceAnonymousFunction() {
    effects.exactlyOnce(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}
