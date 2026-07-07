# Test Support Instructions

This directory owns shared integration-test fixtures only.

Helpers here may create fake homes, bundles, archives, shell state, backend
descriptors, or fake package-manager commands. They must stay deterministic and
assert fixture validity.

Assertions about CLI behavior belong in the integration test file for that
command family.
