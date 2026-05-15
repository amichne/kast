# Hierarchy traversal

Hierarchy traversal is how Kast turns symbol identity into structural context.
This page covers both call hierarchies and type hierarchies.

## Summary

Call hierarchy explains who invokes or is invoked by a callable. Type hierarchy
explains supertypes and subtypes. Both features matter because they let agents
and humans move beyond a single symbol into the neighborhood that gives it
meaning.

## What the wiki currently believes

- Hierarchy traversal depends on a prior step that maps natural-language intent
  to a precise code entity.
- Call and type traversal share the goal of expanding context, but they rely on
  different semantic relations.
- Result bounding and resource management matter because hierarchy expansions can
  grow quickly.

## Evidence and sources

These pages support the current model of hierarchy features.

- [[sources/call-hierarchy-and-type-hierarchy-traversal]] - Provides the direct
  traversal details and mapping logic.
- [[sources/using-kast-from-an-llm-agent]] - Explains how an agent reaches the
  correct symbol before asking for a hierarchy.
- [[sources/cli-command-reference]] - Shows the exposed commands.
- [[sources/backend-standalone-analysis-engine]] - Places traversal in the
  backend subsystem map.

## Related pages

These pages connect traversal to the rest of the product.

- [[concepts/semantic-analysis-operations]]
- [[concepts/llm-agent-workflows]]
- [[entities/backend-standalone]]
- [[analyses/end-to-end-request-lifecycle]]

## Open questions

The current notes leave a few usage questions unanswered.

- What default bounds produce the best tradeoff between usefulness and output
  size for agent-driven workflows?
- How does traversal behave in especially deep or cyclic code graphs?
