package diagnostic.identity

class TypeAliasTarget(val value: Int)

typealias DiagnosticAlias = TypeAliasTarget

fun typeAliasReferenceProbe(value: DiagnosticAlias): Int = value.value
