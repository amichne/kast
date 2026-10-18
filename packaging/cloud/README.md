# Linux evaluation environment

This profile provisions an isolated Linux x86_64 runtime for Kast evaluation.
It does not install personal shell configuration or change credentials.
Runtime directories and evaluation receipts belong outside the repository.

```sh
python3 packaging/cloud/bootstrap.py --root /workspace/pi-eval --with-idea
source /workspace/pi-eval/activate.sh
```

Python 3.12+, curl, tar support, and Git are host prerequisites. The manifest pins
vendor archives and their published SHA-256 digests. npm's lockfile pins Pi and
its transitive packages. Bootstrap runs no npm lifecycle scripts and accepts no
IDE license. Repeat the same command to validate the cached archive digests and
reinstall the locked npm tree. Use a new runtime directory for a clean setup.

The Kast public installer currently supports macOS only. Its selected-IDE
admission requires macOS metadata and its lifecycle uses launchd. The Host's
application lifecycle also skips headless mode. A provisioned Linux IDE therefore
does not establish an installed, ready Kast product or end-to-end evaluation.

The authoritative dotfiles source is `amichne/dotfiles`; the legacy shell-config
repository is not part of this profile. Portable Pi resources must be selected
explicitly into the isolated agent directory. Keep authentication, models,
sessions, extension registrations, and machine settings outside that copy.

IntelliJ IDEA has a unified distribution since 2025.3; core Java and Kotlin
functionality remains free. The old Community Edition name is still present in
Kast's Maven SDK coordinates. Distribution identity, Kotlin plugin identity, and
bundled JBR must be checked against the current project contracts independently.
Do not replace JBR or rewrite distribution metadata to pass admission.

- [JetBrains installation and runtime requirements](https://www.jetbrains.com/help/idea/installation-guide.html)
- [Cloud environment publication](https://learn.chatgpt.com/docs/environments/cloud-environments)

Changes inside one cloud task do not update the saved environment. An environment
owner must set its install command to the bootstrap above, test the setup, and
republish it. Selecting or modifying a task container is not publication.
