# pyright: strict
"""One native directory-exchange adapter shared by managed Host replacement and reload admission."""

from __future__ import annotations

import ctypes
import os
import sys
from enum import StrEnum
from pathlib import Path


class DirectoryExchangeOutcome(StrEnum):
    EXCHANGED = "EXCHANGED"
    PLATFORM_UNSUPPORTED = "PLATFORM_UNSUPPORTED"
    IO_FAILURE = "IO_FAILURE"


def exchange_directories(staged: Path, installed: Path) -> DirectoryExchangeOutcome:
    if sys.platform != "darwin":
        return DirectoryExchangeOutcome.PLATFORM_UNSUPPORTED
    rename = ctypes.CDLL(None, use_errno=True).renamex_np
    rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(os.fsencode(staged), os.fsencode(installed), 2) != 0:
        return DirectoryExchangeOutcome.IO_FAILURE
    return DirectoryExchangeOutcome.EXCHANGED
