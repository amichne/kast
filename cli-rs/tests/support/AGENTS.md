# Test Support Instructions

This directory owns shared integration-test fixtures only.

Helpers here may create fake homes, bundles, archives, shell state, backend
descriptors, or fake package-manager commands. They must stay deterministic and
must not assert product behavior beyond fixture validity.

Do not put command-contract assertions here. Assertions about CLI behavior
belong in the integration test file for that command family.
