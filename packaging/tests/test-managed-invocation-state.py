import contextlib
import io
import json
import os
import socket
import tempfile
import unittest
from pathlib import Path

import product_state_removal as state


def rejection(run: Path, *, live_coordinator: bool) -> dict[str, object]:
    """The single structured rejection that admission prints before it raises."""
    observations = io.StringIO()
    with contextlib.redirect_stderr(observations), _raises(ValueError):
        _ = state.empty_invocation_directories(run.parent.parent, live_coordinator=live_coordinator)
    return json.loads(observations.getvalue())


@contextlib.contextmanager
def _raises(kind: type[BaseException]):
    try:
        yield
    except kind:
        return
    raise AssertionError(f"{kind.__name__} not raised")


class InvocationStateEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        # Unix socket paths are short on macOS; keep the fixture root near the filesystem root.
        self.root = Path(temporary.name).resolve()
        self.run_directory = self.root / "installation/state/run"
        self.run_directory.mkdir(parents=True, mode=0o700)

    def test_unexpected_entries_are_named_in_the_rejection(self) -> None:
        (self.run_directory / "one-shot").mkdir(mode=0o700)
        _ = (self.run_directory / "u.sock.stale").write_bytes(b"")
        (self.run_directory / "alpha").mkdir(mode=0o700)
        self.assertEqual(
            {
                "reason": "UNKNOWN_DIRECTORY",
                "entries": ["alpha", "u.sock.stale"],
                "outcome": "REJECTED",
                "component": "kast-invocation-state",
            },
            rejection(self.run_directory, live_coordinator=False),
        )

    def test_coordinator_entries_without_a_live_coordinator_are_named(self) -> None:
        _ = (self.run_directory / "c.sock.lock").write_bytes(b"")
        self.assertEqual(
            ["c.sock.lock"], rejection(self.run_directory, live_coordinator=False)["entries"]
        )

    def test_a_non_empty_coordinator_lock_names_the_lock(self) -> None:
        lock = self.run_directory / "c.sock.lock"
        _ = lock.write_bytes(b"4242")
        os.chmod(lock, 0o600)
        self.assertEqual(
            {
                "reason": "UNKNOWN_DIRECTORY",
                "entries": ["c.sock.lock"],
                "outcome": "REJECTED",
                "component": "kast-invocation-state",
            },
            rejection(self.run_directory, live_coordinator=True),
        )

    def test_a_coordinator_socket_that_is_not_a_socket_names_the_socket(self) -> None:
        _ = (self.run_directory / "c.sock").write_bytes(b"")
        self.assertEqual(
            ["c.sock"], rejection(self.run_directory, live_coordinator=True)["entries"]
        )

    def test_a_live_coordinator_socket_and_empty_lock_are_admitted(self) -> None:
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        try:
            listener.bind(str(self.run_directory / "c.sock"))
        except OSError as problem:
            self.skipTest(f"socket path unavailable: {problem}")
        lock = self.run_directory / "c.sock.lock"
        _ = lock.write_bytes(b"")
        os.chmod(lock, 0o600)
        self.assertEqual(
            ((self.run_directory, state.journal.Identity.observe(self.run_directory)),),
            state.empty_invocation_directories(self.root / "installation", live_coordinator=True),
        )

    def test_reported_entries_are_sorted_and_bounded(self) -> None:
        names = [f"entry-{index:02d}-" + "x" * 80 for index in range(12)]
        reported = state.reported_entries(reversed(names))
        self.assertEqual(state.MAXIMUM_REPORTED_ENTRIES, len(reported))
        self.assertTrue(
            all(len(name) == state.MAXIMUM_REPORTED_NAME_CHARACTERS for name in reported)
        )
        self.assertEqual(sorted(reported), list(reported))


if __name__ == "__main__":
    _ = unittest.main()
