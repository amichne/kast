# CLI Definition Instructions

This directory owns Clap argument and command definitions.

Keep command families split by the public surface they define: root commands,
agent, developer runtime, inspect, metrics, demo, release, package, generate,
machine, install, shared enums, conversions, and help/version helpers.

Argument types describe operator input. Execution belongs in the command
modules.
