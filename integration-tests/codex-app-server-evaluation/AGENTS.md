# Codex App Server enterprise evaluation guide

This directory owns the operator-facing, pre-production evaluation path for Kast dynamic tools in
Codex App Server. It does not own a production Kast command, a hosted service, or a CI gate.

- Default to the shell-disabled, read-only dynamic path.
- Require an explicit operator flag before the comparison path can use shell access and
  `danger-full-access` in a separate App Server process.
- Validate one versioned request before installing, starting, or invoking either product.
- Fail closed unless the App Server command suppresses inherited MCP and the live transcript shows
  no inherited MCP startup or unexpected tool call.
- Write each run to a new output directory. Preserve the normalized request, command logs, product
  versions, App Server protocol logs, and final structured evidence.
- Never record credentials, tokens, or raw login-status output.
- Keep subprocess calls as argument arrays. Do not use a shell.

Run `python3 -m unittest discover -s integration-tests/codex-app-server-evaluation -p
'test_*.py'` after changing this directory.
