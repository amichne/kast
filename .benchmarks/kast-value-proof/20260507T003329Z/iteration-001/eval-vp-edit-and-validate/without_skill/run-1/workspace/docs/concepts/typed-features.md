# Typed features

A feature in Konditional is not just a key. It is a typed handle bound to a
value type, a context type, and a namespace type.

## The type parameters

The core feature model is `Feature<T, C, M>`.

- `T` is the value type callers receive from `evaluate(...)`.
- `C` is the context type the feature evaluates against.
- `M` is the owning namespace type.

That combination is why a feature property can behave like a local Kotlin API
instead of a string lookup.

## Supported value shapes

The current public model supports three practical groups of values.

| Shape | Declaration style | Typical use |
| --- | --- | --- |
| Primitive | `boolean`, `string`, `integer`, `double` | Simple on and off behavior or low-cardinality values |
| Enum | `enum<...>` | Domain states that callers should handle explicitly |
| Custom | `custom<T, ...>` where `T : Konstrained` | Structured values that need JSON round-tripping |

## Why enums and custom values matter

Enums and `Konstrained` values are what keep the library from collapsing into
boolean sprawl. If the domain really has three states, the feature can return
three states. If the value is structured, the feature can return a real data
class instead of a bag of untyped JSON.

## Next steps

Typed features only make sense when the context is modeled clearly. The next
page explains the standard mix-ins and the axis system that rules evaluate.

- [Read about context and axes](context-and-axes.md)

