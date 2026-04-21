
### Act 1 — GrepAct

**Goal**: make the volume of noise visceral. Stream the count live, then categorize it.

**Layout**:

```terminaloutput

┌─────────────────────────────────────────────────────┐
│  Act 1 of 3 — Text Search                           │
│  grep -rn "execute" --include="*.kt"                │
└─────────────────────────────────────────────────────┘

  Scanning... ████████████████████░░░░  38 hits

  ┌──────────────────┬───────┬──────────────────────────────┐
  │ Category         │ Count │ Example                      │
  ├──────────────────┼───────┼──────────────────────────────┤
  │ String literals  │    12 │ "execute this command"       │
  │ Comments         │     9 │ // TODO: execute after init  │
  │ Unrelated scope  │     8 │ SqlRunner.execute(): Unit    │
  │ Possible matches │    19 │                              │
  └──────────────────┴───────┴──────────────────────────────┘

  38 grep hits. No type information. No scope. Just noise.
```

**Implementation notes**:
- Use `terminal.animation {}` for the streaming counter — tick it every 50ms with a fake
  progress fill as lines arrive from the grep process stdout stream
- The progress bar fills based on `hitsSeenSoFar / estimatedTotal` where estimatedTotal
  starts at `resolvedRefs.size * noiseRatio` (known from selection) — feels responsive
- After streaming completes, render the category table via `terminal.println(table {})`
- Pause 1200ms before clearing to let the "38 hits" number land
- The bottom line is rendered in `brightRed` — it's the setup for the punchline

---

### Act 2 — ResolutionAct

**Goal**: surgical contrast. Fewer rows, more information per row.

**Layout**:

```terminaloutput
┌─────────────────────────────────────────────────────┐
│  Act 2 of 3 — Symbol Resolution                     │
│  kast resolve "execute" → WorkflowEngine.execute    │
└─────────────────────────────────────────────────────┘

  Declared in: core/src/main/kotlin/WorkflowEngine.kt:42
  Type:        suspend (context: ExecutionContext) → Result<Unit>

  ┌────────────────────────────────┬──────┬───────┬────────────────────┬──────────────┐
  │ File                           │ Line │ Kind  │ Resolved Type      │ Module       │
  ├────────────────────────────────┼──────┼───────┼────────────────────┼──────────────┤
  │ orchestration/Scheduler.kt     │   87 │ call  │ WorkflowEngine     │ :orchestrate │
  │ orchestration/Scheduler.kt     │  143 │ call  │ WorkflowEngine     │ :orchestrate │
  │ api/WorkflowResource.kt        │   31 │ call  │ WorkflowEngine     │ :api         │
  │ test/WorkflowEngineTest.kt     │   19 │ call  │ WorkflowEngine     │ :core        │
  │ test/WorkflowEngineTest.kt     │   67 │ call  │ WorkflowEngine     │ :core        │
  │ integration/PipelineRunner.kt  │  204 │ call  │ WorkflowEngine     │ :integration │
  └────────────────────────────────┴──────┴───────┴────────────────────┴──────────────┘

  ──────────────────────────────────────────────────────────────────
  38 text matches  →  6 actual references to WorkflowEngine.execute
  Noise eliminated: 84%
  ──────────────────────────────────────────────────────────────────
```

**Implementation notes**:
- Render the table immediately (no streaming — the instant appearance is itself part of the effect)
- The declaration header line uses `brightCyan` for the fqn
- Module column values are colored by a rotating palette from a fixed set of 6 `TextColors`
  (stable per module name, not per row — use `moduleName.hashCode() % palette.size`)
- The delta summary block uses `brightGreen` for the reference count and percentage
- If `rippleEnabled`, append below the summary: `  [Enter] → explore caller graph`
  in `gray`

---

### Act 3 — RippleAct

**Goal**: show the graph is live and navigable, not a static list.

**Layout**:

```terminaloutput
┌─────────────────────────────────────────────────────┐
│  Act 3 of 3 — Caller Graph (depth 2)                │
└─────────────────────────────────────────────────────┘

  WorkflowEngine.execute                       [:core]
  ├── Scheduler.scheduleNext()                 [:orchestration]
  │   ├── PipelineCoordinator.start()          [:integration]
  │   └── RetryPolicy.attempt()                [:orchestration]
  ├── WorkflowResource.POST /workflows/run     [:api]
  │   └── AuthMiddleware.withContext()         [:api]
  └── PipelineRunner.executePipeline()         [:integration]
      └── BatchProcessor.processBatch()        [:integration]

  4 modules. 8 symbols reachable in 2 hops.
  Every edge is a compiler-verified call site.
```

**Implementation notes**:
- `RippleTraverser` does BFS via K2 `findCallers(fqn)` for each node up to `depth`
- Render via recursive `printTree()` — build the prefix string (`├──`, `│   `, `└──`)
  manually; no library needed for this
- Module label `[:module]` right-aligned to terminal width using `terminal.info.width`
- Module names colored with the same stable palette from Act 2 (visual continuity)
- Root node in `brightCyan`, depth-1 nodes in `brightYellow`, depth-2+ nodes in default
- Summary line at bottom: `brightGreen` for counts, `gray` for the last sentence
- After rendering, hold — don't exit. Print `  kast demo --symbol <fqn> --depth 3` in
  gray as a hint that they can go deeper
