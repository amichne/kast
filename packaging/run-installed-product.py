"""Run complete-product native acceptance with explicit qualified inputs.

The lifecycle probe owns disposable HOME admission, production activation through
an exact local ReleaseSource, upgrade from a different qualified baseline, native
queries, removal and foreign-state assertions. Missing artifacts reject; a staged
Control/desktop Host pair is not a complete-product acceptance input.
"""

import io
import json
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


def require(condition: bool, detail: str) -> None:
    if not condition:
        raise AssertionError("installed-product: " + detail)


def verify_plugin_archive(
    plugin: Path,
    host_version: str,
    provided_contract: dict[str, object],
    idea_release_line: str = "262",
) -> None:
    """Keep independent component artifact checks separate from native acceptance."""
    with zipfile.ZipFile(plugin) as archive:
        names = archive.namelist()
        require(
            bool(names) and all(name.startswith("kast-ide-hosted/") for name in names),
            "plugin layout",
        )
        require(len(names) == len(set(names)), "duplicate plugin entries")
        plugin_jar = "kast-ide-hosted/lib/kast-ide-hosted.jar"
        require(plugin_jar in names, "plugin jar")
        with zipfile.ZipFile(io.BytesIO(archive.read(plugin_jar))) as jar:
            descriptor = ET.fromstring(jar.read("META-INF/plugin.xml"))
            require(descriptor.findtext("version") == host_version, "embedded host version")
            compatibility = descriptor.find("idea-version")
            require(
                compatibility is not None
                and compatibility.get("since-build") == idea_release_line
                and compatibility.get("until-build") == idea_release_line + ".*",
                "embedded host release line",
            )
            require(
                json.loads(jar.read("hosted-contract.json")) == provided_contract,
                "embedded host contract",
            )


def main() -> int:
    # Reuse one native acceptance owner, including its required-input parser and
    # receipt publication rule. Never convert absent prerequisites into a skip.
    from product_lifecycle_probe import main as lifecycle_main

    return lifecycle_main()


if __name__ == "__main__":
    raise SystemExit(main())
