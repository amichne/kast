# Independent simulated-reader review

This review read the revised README, overview, installation guide, first-query tutorial, explanation, reference overview, and example questions.
The reviewer used the intended reader profile: an experienced Kotlin engineer with no prior Kast knowledge.
This agent review does not measure human comprehension.

| Reader question | Answer found in the revised pages | Result |
| --- | --- | --- |
| What does Kast do? | The README and overview identify compiler-resolved source relationships, diagnostics, and targeted changes. | Explicit. |
| Why use it beyond text search? | The customer example keeps the type constant while the source origin changes. The prose explains call identity and value paths. | Concrete comparison, with illustration status preserved. |
| What must I install? | The start page names Apple silicon, Kotlin Gradle, IDEA build, bundled Kotlin, JBR, Codex, and Python. | Explicit prerequisites. |
| What action comes next? | Install, restart the clients, select the worktree, then query a known function. | Ordered procedure. |
| How do I recognize success? | Compare the returned signature and location with known source and read completion. | Visible outcome, distinct from installation status. |
| Can I inspect an independent expected answer? | The tutorial supplies one direct caller, an unrelated function, and an absent-name query. | Positive and negative source controls. |
| Does a source route prove execution? | The explanation explicitly rejects runtime execution and path-feasibility claims. | The distinction remains explicit. |
| Does a full output page mean completion? | The explanation and results page separate previews, retained rejection evidence, and semantic completion. | The distinction remains explicit. |
| What happens after an uncertain write? | The explanation directs the reader to preserve recovery evidence and inspect source before another write. | Protected action order remains explicit. |
| Where can I find exact syntax? | The reference overview routes inputs to Query forms and outputs to Results. It gives installed-schema authority. | The reference has a distinct lookup purpose. |

## Corrections proposed to the central editor

- The README phrase `Each result keeps source locations` exceeds the result contract. Rejected results can lack a location, and explicit empty symbol fields omit it.
- Tutorial discovery knows the module directory. Its prompts need that constraint so other modules with the same package do not obscure the selected example.

The central editor received both findings before the final assessment.

## Generated-page route

The manifest now owns four reader introductions before shared implementation explanations.
The query pages distinguish public `terminalStep` syntax from the final internal TRACE stage.
The symbol page distinguishes public `READ_SOURCE` snapshot checks from the other source APIs in the shared material.
The evidence page routes first-time readers to the authored explanation and current result contract.
These introductions keep the canonical material and its provenance notices intact.

## Evidence limits

The review found the answers in source Markdown and MDX. A rendered-site review remains separate.
The small value example compiled and ran. The live Kast query established declaration discovery in the task workspace.
Neither result proves that a new user completed installation or that the tutorial's exact live caller query succeeded.

The independent review preceded the later tutorial run in `tutorial-live.json`.
That separate run completed discovery, caller, and absent-name queries on the existing installation.
It does not establish a new-user installation outcome.
