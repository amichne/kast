# CLI Definition Instructions

This directory owns Clap argument and command definitions.

Keep command families split by the public surface they define: root commands,
agent, runtime/LSP, inspect/metrics/demo/RPC, release/package/generate,
machine/install, shared enums, conversions, and help/version helpers.

Do not encode runtime behavior in Clap DTOs. Argument types should describe
operator input only; execution belongs in the command modules.
