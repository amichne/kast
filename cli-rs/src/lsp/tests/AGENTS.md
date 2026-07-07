# LSP Test Instructions

This directory owns included LSP unit-test parts.

Keep fake RPC support in `support.rs`. Group tests by protocol framing,
initialization/routes, read operations, rename, hierarchy, and failure modes.
Add new tests to the part that names the behavior under test.

Production code lives in the parent LSP module. These files are included
inside the `#[cfg(test)]` module declared by `../tests.rs`.
