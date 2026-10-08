# Kast agent tools

This package bundles the Kast query skill with the MCP configuration for the
managed Kast installation. Install Kast and restart IntelliJ IDEA to load the
matched Host plugin before using its semantic tools. Queries prepare the exact
repository automatically; start the agent from that repository or worktree.

Preparation can save existing editor buffers and refresh the imported project.
MCP annotations include these effects, even for semantic read tools.

Tool failures set `isError: true`. MCP transport rejections use
`type: "MCP_REJECTED"` with a finite `error.code`, or
`type: "BOUNDARY_REJECTED"` with the original CLI failure in `document`.
Both have `status: "rejected"` and satisfy the tool's advertised output schema.
Canonical semantic documents retain their own outcome and qualification fields.

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
