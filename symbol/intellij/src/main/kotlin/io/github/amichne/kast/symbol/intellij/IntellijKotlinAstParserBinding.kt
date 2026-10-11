package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.ParserDefinition
import org.jetbrains.kotlin.parsing.KotlinParserDefinition

internal enum class KotlinAstParserVariant {
    BASE,
    IDE_K2,
}

/** Exact audited class identities only; an arbitrary KotlinParserDefinition subclass is not qualified. */
internal sealed interface IntellijKotlinAstParserBinding {
    data object Unsupported : IntellijKotlinAstParserBinding

    class Qualified
    private constructor(
        val definition: KotlinParserDefinition,
        val variant: KotlinAstParserVariant,
    ) : IntellijKotlinAstParserBinding {
        companion object {
            // Kotlin-internal API: resolve its public JVM identity without visibility suppressions.
            internal const val IDE_K2_CLASS =
                "org.jetbrains.kotlin.idea.base.analysisApiPlatform.IDEKotlinK2KotlinParserDefinition"

            fun inspect(definition: ParserDefinition?): IntellijKotlinAstParserBinding =
                try {
                    when {
                        definition !is KotlinParserDefinition -> Unsupported
                        definition.javaClass == KotlinParserDefinition::class.java ->
                            Qualified(definition, KotlinAstParserVariant.BASE)
                        definition.javaClass.name == IDE_K2_CLASS &&
                            definition.javaClass ==
                                Class.forName(IDE_K2_CLASS, false, KotlinParserDefinition::class.java.classLoader) ->
                            Qualified(definition, KotlinAstParserVariant.IDE_K2)
                        else -> Unsupported
                    }
                } catch (_: ReflectiveOperationException) {
                    Unsupported
                } catch (_: SecurityException) {
                    Unsupported
                }
        }
    }
}
