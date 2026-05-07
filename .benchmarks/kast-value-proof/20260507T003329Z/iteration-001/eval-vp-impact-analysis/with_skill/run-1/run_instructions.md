# Run instructions: with_skill

Open a Copilot Chat session with the Kast skill loaded.

Paste this prompt:

```text
I want to delete ConditionalValue.ContextualResolver.resolve(). Show me every direct caller, then for each caller show its callers (depth 2). Identify which of those callers are in test files vs production code.
```

Save the full transcript to `outputs/transcript.md`.
After the grader runs, replace `grading.json` with the grader output and update `timing.json`.
