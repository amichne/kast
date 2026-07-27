# Config Module Instructions

This directory owns configuration models, loading, path resolution, launch
projection, filesystem defaults, and workspace identity helpers.

Keep TOML DTOs separate from the normalized `KastConfig` model. Path resolution
must explain ownership and source for every reported path. Workspace identity
must prefer verifiable filesystem or Git facts over guessed strings.

Install-owned configuration conflicts surface through typed reports so repair
code can make explicit decisions.
