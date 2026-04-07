# Kotlin API Design Guide

Use this file as a router when you know you need API-design guidance but the
subtopic is not clear yet. Prefer reading one targeted file below instead of
loading the whole API design corpus.

## Read this when

- `api-parameter-selection.md`: choose between plain parameters, named
  arguments, overloads, lambda flavours, and constructors versus builders.
- `api-builders-and-configuration.md`: design receiver lambdas, DSL builders,
  immutable configuration objects, and builder implementation details.
- `api-extensions-and-factories.md`: design extension-based APIs, framework
  integrations, factory naming, and reified convenience shortcuts.
- `api-surface-stability.md`: set visibility rules, opt-in tiers, and
  mutable/read-only exposure boundaries.
- `api-review-guides.md`: review an API with flowcharts and anti-pattern
  checklists.

## Loading rule

Start with the most specific file that matches the task. Only return to this
router when the request spans multiple API design concerns.
