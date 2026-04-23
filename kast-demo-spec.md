# `kast demo` — Implementation Spec



## Rendering Specification

### Shared Setup

```kotlin
val terminal = Terminal()
// All three acts use the same Terminal instance
// TextColors used throughout: brightCyan, brightYellow, brightGreen, brightRed, gray
```

---

### Act 1 — GrepAct

**Goal**: make the volume of noise visceral. Stream the count live, then categorize it.

**Layout**:
```
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


### Act 2 — ResolutionAct

```
┌─────────────────────────────────────────────────────┐
│  Act 2 of 3 — Symbol Resolution                     │
│  kast resolve "execute" → WorkflowEngine.execute    │
└─────────────────────────────────────────────────────┘

  Declared in: core/src/main/kotlin/WorkflowEngine.kt:42
  Type:        suspend (context: ExecutionContext) → Result<Unit>

  ┌────────────────────────────────┬──────┬───────┬────────────────────┬──────────────┐
  │ File                           │ Line │ Kind  │ Resolved Type      │ Module       │
  ├────────────────────────────────┼──────┼───────┼────────────────────┼──────────────┤
  │ orchestration/Scheduler.kt     │   87 │ call  │ WorkflowEngine     │ :orchestration│
  │ orchestration/Scheduler.kt     │  143 │ call  │ WorkflowEngine     │ :orchestration│
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

### Act 3 — RippleAct

```
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


---
---

## CLI Contract

```
kast demo [OPTIONS]

Options:
  -s, --symbol TEXT         Fully-qualified symbol name to demo (skips auto-selection)
  --min-refs INT            Minimum resolved reference count to qualify a symbol [default: 5]
  --noise-ratio FLOAT       Minimum ratio of grep hits to resolved refs [default: 2.0]
  --depth INT               BFS depth for ripple traversal in Act 3 [default: 2]
  --no-ripple               Skip Act 3 entirely
  -h, --help                Show this message and exit

Exit codes:
  0   Demo ran successfully
  1   No qualifying symbol found (increase --min-refs or --noise-ratio, or pass --symbol)
  2   K2 index unavailable (run `kast index` first)
```
