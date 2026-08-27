# Hosted read composition owner

This package joins the exact retained Project, its already-captured detached Gradle model, the
existing IntelliJ symbol adapters, and the four nominal hosted routes.

- Prepare native/model authority before consuming an endpoint generation; activation alone may
  bind that generation to the exact four-port runtime.
- Locate only the single open exact-root Project at the request-local native adapter boundary.
  Never open, import, refresh, wait for, or fall back to another Project.
- Candidate and exact-selector authority is endpoint-scoped, typed, detached, and in-memory. It
  owns no persistence and retains no Project, VFS, PSI, scope, or compiler object.
- Model projection may consume only the admitted detached model. It must not inspect paths to infer
  ownership, walk the repository, hash sources, or reconstruct Gradle state.
- Keep workspace inspection, discovery, resolution, and description as four statically named
  ports; no registry or generic service locator is permitted.

Run the hosted production-composition selectors, `:runtime:ide-read:check`, and the architecture
gates after changing this package.
