package io.github.amichne.kast.relation.intellij

internal class ClassConstructionCallerFixture(val marker: Int) {
    constructor() : this(0)

    fun member(): Int = marker

    companion object {
        operator fun invoke(marker: String): ClassConstructionCallerFixture =
            ClassConstructionCallerFixture(marker.length)
    }
}

internal class ClassConstructionCallerFixtureUses {
    fun primaryConstructor(): ClassConstructionCallerFixture = ClassConstructionCallerFixture(1)

    fun secondaryConstructor(): ClassConstructionCallerFixture = ClassConstructionCallerFixture()

    fun typeOnly(value: ClassConstructionCallerFixture): ClassConstructionCallerFixture = value

    fun member(value: ClassConstructionCallerFixture): Int = value.member()

    fun callableReference(): (Int) -> ClassConstructionCallerFixture = ::ClassConstructionCallerFixture

    fun invokeFunction(): ClassConstructionCallerFixture = ClassConstructionCallerFixture("not-a-constructor")
}

internal object ClassConstructionCallerCollisionScope {
    class ClassConstructionCallerFixture

    fun unrelatedSameName(): ClassConstructionCallerFixture = ClassConstructionCallerFixture()
}
