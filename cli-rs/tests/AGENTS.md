# Rust Integration Test Instructions

This directory owns Rust integration tests for the CLI binary and packaged
resource contracts.

Keep each test file focused on one command family or contract surface. Shared
fixtures belong in `support/`; individual tests should read like executable
requirements for the surface named by the file.

Do not create another monolithic smoke file. If a test needs unrelated fixture
setup, move the fixture to `support` or split the assertion into the correct
contract file.
