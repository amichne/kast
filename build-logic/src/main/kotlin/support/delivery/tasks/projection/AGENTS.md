# Delivery projection Gradle boundaries

This directory owns KVP-005's bounded build-policy effects. Generation writes exactly the five
declared projection artifacts. Verification admits two independent generations, compares the
checked-in bytes, and emits the generated proof document. The negative task mutates only in-memory
fixtures and writes only its build report.

No task in this directory may start a process, read Git metadata, walk the repository, or accept an
arbitrary path or command.
