# GitHub integration guide

This tree owns repository automation.

CI admits only the Kotlin/Gradle product surface. The repository-contract job
owns the no-Rust and repository-shape gates. The Kotlin job owns architecture
verification and the Gradle test graph.

Do not add a second build, packaging, installation, or release authority.
Release and documentation automation are introduced only by their owning
clean-slate delivery tasks.

Run the narrowest changed script locally, followed by:

```console
python3 .github/scripts/check-no-rust-product.py --root .
python3 .github/scripts/check-repository-shape.py --root .
```
