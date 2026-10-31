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


class DirectoryMoveOutcome(StrEnum):
    MOVED = "MOVED"
    PLATFORM_UNSUPPORTED = "PLATFORM_UNSUPPORTED"
    IO_FAILURE = "IO_FAILURE"


def move_directory_noreplace(source: Path, destination: Path) -> DirectoryMoveOutcome:
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform == "darwin":
        rename = library.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        result = rename(os.fsencode(source), os.fsencode(destination), 4)
    elif sys.platform.startswith("linux") and hasattr(library, "renameat2"):
        rename = library.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        rename.restype = ctypes.c_int
        result = rename(-100, os.fsencode(source), -100, os.fsencode(destination), 1)
    else:
        return DirectoryMoveOutcome.PLATFORM_UNSUPPORTED
    if result != 0:
        return DirectoryMoveOutcome.IO_FAILURE
    return DirectoryMoveOutcome.MOVED


def exchange_directories(staged: Path, installed: Path) -> DirectoryExchangeOutcome:
    if sys.platform != "darwin":
        return DirectoryExchangeOutcome.PLATFORM_UNSUPPORTED
    rename = ctypes.CDLL(None, use_errno=True).renamex_np
    rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(os.fsencode(staged), os.fsencode(installed), 2) != 0:
        return DirectoryExchangeOutcome.IO_FAILURE
    return DirectoryExchangeOutcome.EXCHANGED
