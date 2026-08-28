# Admitted project-read execution owner

This package owns the sole live IDEA 262 execution boundary for KVP-021.

- `AdmittedProjectReadExecution` retains the admitted Project privately and invokes one bounded,
  repeatable computation only inside `ReadAction.computeCancellable`.
- Reject EDT, disposed, closed, and dumb state before entering the read and recheck all lifecycle
  state inside it. Never wait for smart mode or retry outside the platform read primitive.
- Propagate every `ProcessCanceledException`, including write preemption. The runtime executor owns
  permit terminalization before propagation.
- The computation and result types are internal. Never expose Project, PSI, VFS, callbacks, or an
  open generic execution method through the public workspace API.

Run the KVP-021 canonical selectors in `:runtime:ide-read` and the owning workspace module check.
