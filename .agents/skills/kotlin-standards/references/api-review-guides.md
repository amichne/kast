# API Review Guides

Use before finalizing public or shared APIs.

- Who is the caller, and what behavior are they expressing?
- Can an invalid state or invalid call order be represented?
- Are expected failures typed and testable?
- Do names repeat package context or expose implementation detail?
- Is visibility as narrow as it can be?
- Are mutable collections, `Any`, unchecked casts, `!!`, boolean traps, or
  nullable flags exposed?
- Do tests prove behavior through the public API?

Fix the smallest API shape that removes the highest-risk invalid state.
