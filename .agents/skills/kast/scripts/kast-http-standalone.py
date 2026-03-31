#!/usr/bin/env python3
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from kast_transport import main


if __name__ == "__main__":
    raise SystemExit(main(["invoke", "--transport=http-standalone", *sys.argv[1:]]))
