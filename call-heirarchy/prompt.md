Create a callHeirarchy implementation, with parameters for depths, max total calls, timeout, and anything else that may
be a suitable boundary.

We should seek to persist some amount of this content, tying it to the git SHA, though I can’t think of a sane way to do
so at first glance. Open to ideas

This should leverage our other building blocks, and should be constrained according to the below:

* Root semantics: the root node represents the selected declaration itself
* depth semantics: depth = 1 means root + one edge , such that we always have at least 1 node to n the depth=0 set. We
  also can say it is only possible to have a node set > 1 if depth > 0
* Deduplication semantics: repeated calls from the same caller to the same callee appear multiple times (as we are
  referential on the basis of the call site, not the symbol itself)
* Cycle handling: recursive/self-recursive and mutually recursive call graphs are truncated so the tree stays finite and
  deterministic. How you manage this is flexible, but you must be exceptionally transparent with your chosen approach. I
  will reject anything I cannot understand
* Ordering: define a stable sort order for children so results are deterministic across runs.

Uplift all existing “profiling” print statements to be properly implemented open-telemetry solutions, no hacky print
statements. I want to be able to enable or disable, define what scope and detail, as well as the location of the
output .
