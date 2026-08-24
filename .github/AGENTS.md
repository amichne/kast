# GitHub integration guide

This tree owns repository automation.

CI admits only the Kotlin/Gradle product surface. The repository-contract job
owns the repository-shape gate. The Kotlin job owns architecture verification
and the Gradle test graph. PR #633 reuses that Kotlin job for GATE-060. The
lightweight `pr633-merge-candidate` job verifies GATE-060, then
`pr633-exact-head-ci` materializes GATE-070 from that artifact without
rebuilding, packaging, installing, or rerunning the lifecycle.

Do not add a second build, packaging, installation, or release authority.
Release and documentation automation are introduced only by their owning
clean-slate delivery tasks.

`workflows/docs.yml` owns the public-site build and GitHub Pages deployment. It
must publish only the artifact produced by `docs/build_public_site.py`.

## Hook and script ownership

- Keep workflow policy in `workflows/`, executable repository checks in
  `scripts/`, and agent-only source filters in `instructions/`.
- Commands used by local hooks and workflows must resolve paths from the
  canonical repository root.
- A script that enforces a repository invariant must expose that invariant
  through its exit status and print the evidence that caused a failure.
- Preserve macOS and Linux compatibility unless a narrower guide names one
  supported host.

Run the narrowest changed script locally, followed by:

```console
python3 .github/scripts/check-repository-shape.py --root .
```
