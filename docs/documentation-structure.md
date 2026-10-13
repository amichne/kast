# Documentation structure

Kast uses one Mintlify site at `kast.michne.com`. Its source is `docs/public`, with one `docs.json` and one deployment.

## Guide

Guide is the default tab. It contains five short pages:

- Why Kast?
- What you can do.
- Get started.
- Connect your agent.
- Troubleshoot Kast.

The guide explains value, capabilities, setup, and common problems. A reader can install Kast and use an agent without the Reference tab.

## Reference

Reference starts at `/reference`. Technical guides, contracts, schemas, and detailed recovery procedures use `/reference/...` paths.

The collapsed Internals group contains architecture and runtime details. The public guide links to Reference when a reader needs more detail.

The callable generator writes `docs/public/reference/callables.openapi.json`. Its schema owner stays unchanged. Do not edit the generated artifact directly.

Mintlify places generated callable pages under `/reference/callables`. The navigation config owns that route prefix.

## Routes and deployment

Keep existing reference URLs when their meaning stays unchanged. Redirect moved technical guides and old generated callable URLs to their new locations.

Raw JSON routes redirect to the repository files on `main`. After merge, examine the rendered site and its redirects.

The existing deployment reads `docs/public` from `amichne/kast`, branch `main`. The Guide and Reference tabs use that same deployment.

Use the [development guide](development.md#validate-documentation) for local preview commands. If deployment fails, restore the previous source revision.
