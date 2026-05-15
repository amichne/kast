# Module Depth

Use when shaping packages, files, or APIs so callers learn less and get more.

- Prefer deep modules: small interface, rich implementation, one place that owns the workflow.
- Keep orchestration inside the module. Callers should provide intent and consume outcomes, not coordinate
  parse/validate/normalize/execute steps themselves.
- Apply the deletion test: if deleting the module only moves the same complexity into every caller, the module was
  earning its keep. If complexity vanishes, it was probably a pass-through.
- Treat the interface as the test surface. Tests should cross the same seam that callers use.
- Prefer private or `internal` seams for implementation flexibility. Do not force internal collaboration choices into
  the public interface.
- One adapter is often a hypothetical seam. Two adapters, usually production and test, are a much stronger reason to
  keep the seam public.
- Split a module when the extracted piece has independent leverage, ownership, or lifecycle. Do not split purely to make
  files smaller or unit tests easier.
