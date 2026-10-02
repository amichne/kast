# Kast agent tools

This package bundles the Kast query skill with the MCP configuration for the
managed Kast installation. Install Kast and open your repository in IntelliJ IDEA
before using its semantic tools.

For a released installation, run:

```sh
kast plugin codex
```

Start a new Codex session from your repository, then invoke the `kast` skill.
The plugin loads the managed `kast-mcp-complete` launcher from
`$HOME/.local/share/kast/installation/bin`.

To install the source marketplace from this checkout, run from the repository
root:

```sh
codex plugin marketplace add .
codex plugin add kast@kast
```

To use the skill independently, install [`skills/kast`](skills/kast/SKILL.md) in
your harness's skill directory, such as `~/.agents/skills/kast` for Codex, and
connect the MCP server separately with `kast connect codex mcp`. The skill
contains common query patterns and executable request examples; installing it
alone does not register tools.

The source plugin version is `0.0.0`; release assembly supplies the release
version. The plugin format and marketplace are documented in the
[official OpenAI package guide](https://developers.openai.com/plugins/build/plugins).
