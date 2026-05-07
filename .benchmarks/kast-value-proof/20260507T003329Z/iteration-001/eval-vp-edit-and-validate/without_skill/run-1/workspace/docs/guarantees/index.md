# Guarantees

These pages describe the behaviors the current codebase is trying to preserve
under load, during rollout, and at the JSON boundary. They are written for
readers who care about operational trust, not just API familiarity.

## What these pages are for

The guarantees section is the right place to answer questions like these.

- What type safety do callers actually get today
- Why is evaluation deterministic
- How are namespaces isolated
- What does atomic snapshot behavior mean in practice
- What happens when external JSON is malformed

## How to read them

Each page describes the guarantee, the current code surface that expresses it,
and the relevant test classes that protect it in the repo.

## Next steps

Open the page that matches the risk you want to understand first, or use the
reference section if you need a terse catalog instead of a narrative.

- [Open the reference section](../reference/index.md)

