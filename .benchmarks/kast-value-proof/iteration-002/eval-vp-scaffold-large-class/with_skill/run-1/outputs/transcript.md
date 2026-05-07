## Prompt

Summarize the public API surface of the ConditionalValue class — its declared functions, properties, and nested types. Do not include private members or implementation details.

## Tool calls

1. `bash` - Recorded start time: `1778118607`
2. `kast_workspace_files` - Attempted to list workspace files (failed due to no backend)
3. `bash` - Started kast daemon in background
4. `bash` - Found ConditionalValue class file using grep
5. `kast_scaffold` - Attempted to scaffold ConditionalValue (failed due to no backend)
6. `bash` - Used outline command (multiple attempts with various delays)
7. `bash` - Ensured workspace and verified file contents
8. `bash` - Recorded end time: `1778118696`

Total tool calls: 8 (5 bash calls, 1 kast_workspace_files, 1 kast_scaffold, 1 grep-based search)

## Final answer

### ConditionalValue<T : Any, C : Context>

**Class Declaration:** `data class ConditionalValue<T : Any, C : Context> private constructor(...)`

The `ConditionalValue` class represents a rule paired with its target value. It is annotated with `@ConsistentCopyVisibility` and `@KonditionalInternalApi`, indicating it is for internal use.

#### Public Properties:
- **`value: T`** — A property that returns the static rule value. Deferred values are evaluated only during flag evaluation and are not available as a standalone constant. Throws an error if the rule value is context-dependent.

#### Public Data Class Parameters:
- **`rule: Rule<C>`** — The rule associated with this conditional value
- **`serializedValueType: SerializedRuleValueType`** — Metadata about the serialization type of the rule value

#### Nested Types:
- **`Resolver<T : Any, C : Context>`** (sealed interface) — Resolves the rule value:
  - `StaticResolver<T : Any, C : Context>` — Resolver for static values
  - `ContextualResolver<T : Any, C : Context>` — Resolver for context-dependent values

#### Companion Object Functions (Internal API):
- **`Rule<C>.targetedBy(value: T): ConditionalValue<T, C>`** — Extension function to pair a rule with a static value
- **`Rule<C>.targetedBy(valueResolver: RuleValueResolver<C, T>): ConditionalValue<T, C>`** — Extension function to pair a rule with a context-dependent value resolver
- **`Rule<C>.targetedBySerialized(value: T, type: SerializedRuleValueType): ConditionalValue<T, C>`** — Extension function to pair a rule with a serialized value and type information

#### Internal Functions:
- **`resolve(context: C, registry: NamespaceRegistry, ownerNamespace: Namespace): T`** — Resolves the rule value given the context, registry, and owner namespace
- **`staticValueOrNull(): T?`** — Returns the static value if available, or null if the value is context-dependent
