"""Private interpreter entry; the public shell verifies every executable input first."""

import sys
from pathlib import Path

sys.dont_write_bytecode = True
directory = Path(__file__).resolve().parent
sys.path[:0] = [str(directory), str(directory / "defusedxml.whl")]


def main() -> int:
    if sys.argv[1:2] == ["--remove-product"]:
        import managed_product_removal

        return managed_product_removal.main(sys.argv[2:])

    import managed_installation

    return managed_installation.entrypoint()


if __name__ == "__main__":
    raise SystemExit(main())
