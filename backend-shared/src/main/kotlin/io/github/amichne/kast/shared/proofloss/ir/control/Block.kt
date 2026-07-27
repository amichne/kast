package io.github.amichne.kast.shared.proofloss.ir

data class Block(val statements: List<Statement>) {
    constructor(vararg statements: Statement) : this(statements.toList())
}
