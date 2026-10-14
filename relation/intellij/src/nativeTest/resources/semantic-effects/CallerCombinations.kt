package consumer

fun exactlyOnceLambdaFunction() {
    effects.exactlyOnce(block = { proof.target() }, marker = 1)
}

fun exactlyOnceLambdaConstructor() {
    effects.exactlyOnce(block = { proof.Target() }, marker = 1)
}

fun exactlyOnceAnonymousFunction() {
    effects.exactlyOnce(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}

fun exactlyOnceAnonymousConstructor() {
    effects.exactlyOnce(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}

fun atMostOnceLambdaFunction() {
    effects.atMostOnce(block = { proof.target() }, marker = 1)
}

fun atMostOnceLambdaConstructor() {
    effects.atMostOnce(block = { proof.Target() }, marker = 1)
}

fun atMostOnceAnonymousFunction() {
    effects.atMostOnce(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}

fun atMostOnceAnonymousConstructor() {
    effects.atMostOnce(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}

fun atLeastOnceLambdaFunction() {
    effects.atLeastOnce(block = { proof.target() }, marker = 1)
}

fun atLeastOnceLambdaConstructor() {
    effects.atLeastOnce(block = { proof.Target() }, marker = 1)
}

fun atLeastOnceAnonymousFunction() {
    effects.atLeastOnce(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}

fun atLeastOnceAnonymousConstructor() {
    effects.atLeastOnce(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}

fun unknownLambdaFunction() {
    effects.unknown(block = { proof.target() }, marker = 1)
}

fun unknownLambdaConstructor() {
    effects.unknown(block = { proof.Target() }, marker = 1)
}

fun unknownAnonymousFunction() {
    effects.unknown(
        block =
            fun() {
                proof.target()
            },
        marker = 1,
    )
}

fun unknownAnonymousConstructor() {
    effects.unknown(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}

fun absentLambdaFunction() {
    effects.absent(block = { proof.target() }, marker = 1)
}

fun otherParameterLambdaFunction() {
    effects.otherParameter(block = { proof.target() }, marker = 1, declared = {})
}

fun sameNameStringLambdaFunction() {
    effects.exactlyOnce(block = { proof.target() }, marker = "control")
}
