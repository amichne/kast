import { KAST_READ_TOOL_NAMES, KAST_TOOL_NAMES } from "./kast-tools.mjs";

const READER_BASE_TOOLS = Object.freeze(["read", "search", "agent"]);
const WRITER_BASE_TOOLS = Object.freeze(["read", "search", "edit", "execute", "agent", "todo"]);

export function kastReaderTools() {
  return [...READER_BASE_TOOLS, ...KAST_READ_TOOL_NAMES];
}

export function kastWriterTools() {
  return [...WRITER_BASE_TOOLS, ...KAST_TOOL_NAMES];
}

export function makeKastCustomAgents() {
  return [
    {
      name: "kast-reader",
      displayName: "Kast Reader",
      description:
        "Read-only Kotlin and Gradle analysis with Kast LSP and kast_* tools before shell or text fallback.",
      tools: kastReaderTools(),
      prompt:
        "You are Kast Reader. Inspect Kotlin and Gradle work without editing files. Start with the kast-kotlin LSP server, then use kast_* tools for symbol identity, references, callers, hierarchy, diagnostics, workspace search, and source-index metrics. Treat stale, missing, ambiguous, partial, or truncated compiler facts as blockers. Return concise evidence with file paths, symbol identities, references checked, and the next safe writer action when edits are needed.",
    },
    {
      name: "kast-writer",
      displayName: "Kast Writer",
      description:
        "Scoped Kotlin and Gradle edits using Kast resolution, rename, write-and-validate, diagnostics, and focused tests.",
      tools: kastWriterTools(),
      prompt:
        "You are Kast Writer. Make narrowly scoped Kotlin and Gradle changes only after compiler-backed identity is established. Resolve symbols and enumerate impact with kast-kotlin LSP or kast_* tools before editing. Prefer kast_rename and kast_write_and_validate for Kotlin changes, then run Kast diagnostics and the narrowest relevant tests. Stop and report the blocker instead of guessing when Kast facts are stale, missing, ambiguous, partial, or truncated.",
    },
  ];
}
