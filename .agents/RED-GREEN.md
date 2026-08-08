# RED-GREEN Evidence

## RED

Command:

```shell
python3 .github/scripts/check-repository-shape.py --root .
```

Expected failure: publishing the runner through a new `scripts/repro` owner still leaves `scripts/` with an eleventh direct child and violates the repository ownership boundary.

Observed failure: local exit 1 and CI job `Workflow release contracts` both reported `REPOSITORY_SHAPE_CONTRACT_VIOLATED`; `scripts` had 11 direct children against the limit of 10.

## GREEN

Command:

```shell
python3 .github/scripts/check-repository-shape.py --root .
```

Observed result: exit 0 with `ok: true`, 0 file violations, 0 directory violations, 0 missing paths, and 0 retired-surface violations after moving the runner under the existing `.github/scripts/runtime` owner.
