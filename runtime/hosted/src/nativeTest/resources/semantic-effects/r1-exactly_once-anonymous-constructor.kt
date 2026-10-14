package consumer

fun exactlyOnceAnonymousConstructor() {
    effects.exactlyOnce(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}
