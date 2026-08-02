package io.github.amichne.kast.idea

internal object NativeSemanticGraphSources {
    const val canonical = """
        package demo

        annotation class Marker

        sealed class Parent<T> {
            open fun inherited(value: T): T = value
        }

        class Box<T> @Marker constructor(val value: T) : Parent<T>() where T : Any {
            @Marker
            var label: String = "label"

            override fun inherited(value: T): T = value
            fun pick(value: String): String = value
            fun pick(value: Int): Int = value
        }

        class Constructed {
            constructor(value: String)
            constructor(value: Int)
        }

        fun construct(): Constructed = Constructed(1)
    """

    const val leftType = """
        package left

        class Foo
    """

    const val rightType = """
        package right

        class Foo
    """

    const val leftTypeConsumer = """
        package consumer

        import left.Foo

        val leftFoo: Foo? = null
    """

    const val rightTypeConsumer = """
        package consumer

        import right.Foo

        val rightFoo: Foo? = null
    """

    const val localProperty = """
        package demo

        fun useLocalProperty(): Int {
            val localValue = 1
            return localValue
        }
    """

    const val functionTypeParameter = """
        package demo

        typealias Resolver = (workspaceRoot: String) -> String
    """

    const val genericCallableReference = """
        package demo

        import java.util.concurrent.CompletableFuture

        data class Entry<Query, State>(val state: State)
        data class List<Element>(val size: Int) { fun isNotEmpty(): Boolean = size > 0 }
        data class Pair<First, Second>(val first: First)
        fun <Query, State> stateReference(): (Entry<Query, State>) -> State = Entry<Query, State>::state
        fun sizeReference(): (List<String>) -> Int = List<String>::size
        fun nonEmptyReference(): (List<String>) -> Boolean = List<String>::isNotEmpty
        fun firstReference(): (Pair<String, String?>) -> String = Pair<String, String?>::first
        fun <T> generatedFluentCall(value: T): T = CompletableFuture.completedFuture(value).thenApply { it }.join()
        fun laterTarget(): String = "ok"
        fun resilientCall(): String = laterTarget()
    """

    const val enum = """
        package demo

        enum class Mode(val value: Int) {
            VALUE(1),
        }
    """

    const val unresolvedCall = "package demo\nfun brokenCall() = missingCall()"
    const val unresolvedSupertype = "package demo\ninterface Broken : MissingBase"
    const val unresolvedType = "package demo\nval broken: MissingType? = null"
}
