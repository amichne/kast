# Rust Integration Test Instructions

This directory owns Rust integration tests for the CLI binary and packaged
resource contracts.

Keep each test file focused on one command family or contract surface. Shared
fixtures belong in `support/`; individual tests should read like executable
requirements for the surface named by the file.

New coverage belongs in the test file for the command family or contract it
proves. Shared setup belongs in `support/`.
