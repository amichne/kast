#!/usr/bin/env -S uv run --locked --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = ["pydantic==2.12.3", "jsonschema==4.25.1", "anyio==4.12.1", "mcp==1.26.0"]
# [tool.uv]
# no-build = true
# required-version = ">=0.12.9"
# [[tool.uv.index]]
# name = "pypi"
# url = "https://pypi.org/simple"
# default = true
# ///
"""Shared task MCP entry and native-adapter bridge over the installed binary RPC."""

import sys


def main() -> int:
    sys.dont_write_bytecode = True
    from service import main as serve

    return serve()


if __name__ == "__main__":
    raise SystemExit(main())
