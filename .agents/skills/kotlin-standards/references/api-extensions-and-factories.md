# API Extensions And Factories

Use this for extension APIs, factories, parsers, and convenience construction.

- Keep tightly-owned extensions beside the owning type.
- Create extension files only for integration APIs, many unrelated receivers, or
  a separate package vocabulary.
- Do not use extensions to hide dependencies or side effects.
- Put construction factories in the companion when they define the type's
  contract.
- Use `parse`, `from`, or `of` consistently with local style.
- Return typed outcomes for ordinary invalid input.
- Use `inline reified` only when call-site type information is the point.
