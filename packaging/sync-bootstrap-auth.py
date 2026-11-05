#!/usr/bin/env python3
"""Project the reviewed stdlib authenticator into the standalone public script."""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
START = "# BEGIN GENERATED BOOTSTRAP RELEASE AUTH\n"
END = "# END GENERATED BOOTSTRAP RELEASE AUTH\n"


def main() -> None:
    path = ROOT / "install.sh"
    script = path.read_text()
    source = (ROOT / "packaging/bootstrap_release_auth.py").read_text()
    block = (
        START
        + "bootstrap_release_auth() {\n  python3 - \"$@\" <<'KAST_BOOTSTRAP_RELEASE_AUTH'\n"
        + source
        + "KAST_BOOTSTRAP_RELEASE_AUTH\n}\n"
        + END
    )
    if START in script:
        prefix, _, rest = script.partition(START)
        _, marker, suffix = rest.partition(END)
        if not marker or START in rest or END in suffix:
            raise SystemExit("bootstrap auth projection markers rejected")
        script = prefix + block + suffix
    else:
        anchor = "resolve_developer_latest() {\n"
        if script.count(anchor) != 1:
            raise SystemExit("bootstrap auth projection anchor rejected")
        script = script.replace(anchor, block + "\n" + anchor)
    _ = path.write_text(script)


if __name__ == "__main__":
    main()
