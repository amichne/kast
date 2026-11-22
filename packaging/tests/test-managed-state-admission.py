"""Typed rejections from installation state capture and admission, and their outcome mapping."""

import contextlib
import errno
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import managed_installation as install
import managed_product_installation as activation
import managed_update_contract as contract
import managed_update_journal as journal
import product_state_removal

PROFILE = "0123456789abcdef"
WORKSPACE = "a" * 64


def rejected(action) -> tuple[contract.StateRejection, dict[str, object]]:
    """The raised typed rejection and the one observation its check emitted."""
    observations = io.StringIO()
    with contextlib.redirect_stderr(observations):
        try:
            action()
        except install.StateRejected as problem:
            lines = observations.getvalue().splitlines()
            rejections = [json.loads(line) for line in lines if '"outcome":"REJECTED"' in line]
            assert len(rejections) == 1, lines
            return problem.rejection, rejections[0]
    raise AssertionError("StateRejected not raised")


class StateAdmissionTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve() / "kast"
        self.control = self.root / "installation"
        self.state = self.control / "state"
        self.state.mkdir(parents=True, mode=0o700)
        for directory in (self.root, self.control):
            directory.chmod(0o700)

    def broker_profile(self) -> Path:
        profile = self.state / "broker" / PROFILE
        profile.mkdir(parents=True, mode=0o700)
        (self.state / "broker").chmod(0o700)
        for name in ("semantic-service.plist", "semantic-service.log", "service-start.lock"):
            _ = (profile / name).write_bytes(b"")
        return profile

    def assert_rejection(
        self,
        action,
        component: contract.StateComponent,
        reason: contract.Failure,
        entries: list[str],
    ) -> None:
        rejection, observed = rejected(action)
        self.assertEqual(contract.StateRejection(reason, component, tuple(entries)), rejection)
        self.assertEqual(
            {
                "reason": reason.value,
                "component": component.value,
                "entries": entries,
                "outcome": "REJECTED",
            },
            observed,
        )

    def test_coordinator_state_after_retirement_names_state_broker(self) -> None:
        # The #1311 scenario: the retired coordinator left its broker profile behind.
        _ = self.broker_profile()
        self.assert_rejection(
            lambda: activation.verify_replacement_state(self.control, live_coordinator=False),
            contract.StateComponent.INSTALLATION_STATE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["state/broker"],
        )

    def test_removal_capture_names_state_broker(self) -> None:
        _ = self.broker_profile()
        self.assert_rejection(
            lambda: product_state_removal.admit(self.control),
            contract.StateComponent.INSTALLATION_STATE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["state/broker"],
        )

    def test_live_coordinator_profile_is_admitted(self) -> None:
        _ = self.broker_profile()
        with contextlib.redirect_stderr(io.StringIO()):
            activation.verify_replacement_state(self.control, live_coordinator=True)

    def test_unknown_coordinator_profile_is_named(self) -> None:
        _ = self.broker_profile()
        (self.state / "broker/not-a-profile").mkdir(mode=0o700)
        self.assert_rejection(
            lambda: activation.admit_live_semantic_broker(self.state / "broker"),
            contract.StateComponent.SEMANTIC_COORDINATOR_STATE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["state/broker/not-a-profile"],
        )

    def test_unknown_coordinator_profile_file_is_named(self) -> None:
        profile = self.broker_profile()
        _ = (profile / "heap.hprof").write_bytes(b"")
        self.assert_rejection(
            lambda: activation.admit_live_semantic_broker(self.state / "broker"),
            contract.StateComponent.SEMANTIC_COORDINATOR_STATE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            [f"state/broker/{PROFILE}/heap.hprof"],
        )

    def test_coordinator_profile_limit_is_typed(self) -> None:
        broker = self.state / "broker"
        broker.mkdir(mode=0o700)
        for index in range(activation.MAXIMUM_SEMANTIC_PROFILES + 1):
            (broker / f"{index:016x}").mkdir(mode=0o700)
        self.assert_rejection(
            lambda: activation.admit_live_semantic_broker(broker),
            contract.StateComponent.SEMANTIC_COORDINATOR_STATE,
            contract.Failure.STATE_ENTRY_LIMIT_EXCEEDED,
            ["state/broker"],
        )

    def test_unknown_installation_entry_is_named(self) -> None:
        _ = (self.control / "runtime").write_bytes(b"")
        self.assert_rejection(
            lambda: activation.verify_layout(self.control),
            contract.StateComponent.INSTALLATION_LAYOUT,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["runtime"],
        )

    def test_unknown_configuration_entry_is_named(self) -> None:
        configuration = self.control / "config"
        configuration.mkdir(mode=0o700)
        _ = (configuration / "environment").write_bytes(b"")
        _ = (configuration / "secrets").write_bytes(b"")
        self.assert_rejection(
            lambda: activation.verify_client_configuration(self.control),
            contract.StateComponent.CLIENT_CONFIGURATION,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["config/secrets"],
        )

    def test_missing_configuration_environment_is_named(self) -> None:
        (self.control / "config").mkdir(mode=0o700)
        self.assert_rejection(
            lambda: activation.verify_client_configuration(self.control),
            contract.StateComponent.CLIENT_CONFIGURATION,
            contract.Failure.MISSING_STATE_ENTRY,
            ["config/environment"],
        )
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(
                {Path("config")},
                activation.verify_client_configuration(self.control, partial=True),
            )

    def test_unknown_workspace_configuration_directory_is_named(self) -> None:
        workspaces = self.control / "config/workspaces"
        workspaces.mkdir(parents=True, mode=0o700)
        (workspaces / "project").mkdir(mode=0o700)
        self.assert_rejection(
            lambda: list(activation.workspace_overlays(self.control / "config")),
            contract.StateComponent.WORKSPACE_CONFIGURATION,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["config/workspaces/project"],
        )

    def test_unknown_workspace_configuration_file_is_named(self) -> None:
        workspace = self.control / "config/workspaces" / WORKSPACE
        workspace.mkdir(parents=True, mode=0o700)
        (self.control / "config/workspaces").chmod(0o700)
        _ = (workspace / "notes").write_bytes(b"")
        rejection, _ = rejected(lambda: list(activation.workspace_overlays(self.control / "config")))
        self.assertEqual(contract.Failure.UNOWNED_STATE_ENTRY, rejection.reason)
        self.assertEqual(contract.StateComponent.WORKSPACE_CONFIGURATION, rejection.component)
        # A long entry is bounded rather than reported whole.
        expected = f"config/workspaces/{WORKSPACE}/notes"[: contract.MAXIMUM_REPORTED_NAME_CHARACTERS]
        self.assertEqual((expected,), rejection.entries)

    def test_unknown_workspace_store_entry_is_named(self) -> None:
        store = self.root.parent / "kast-workspaces"
        store.mkdir(mode=0o700)
        (store / "profiles").mkdir(mode=0o700)
        self.assert_rejection(
            lambda: activation.require_empty_workspaces(self.root),
            contract.StateComponent.WORKSPACE_STORE,
            contract.Failure.UNOWNED_STATE_ENTRY,
            ["profiles"],
        )

    def test_unretired_workspace_container_is_named(self) -> None:
        containers = self.root.parent / "kast-workspaces/containers"
        containers.mkdir(parents=True, mode=0o700)
        (containers / "workspace-1").mkdir(mode=0o700)
        self.assert_rejection(
            lambda: activation.require_empty_workspaces(self.root),
            contract.StateComponent.WORKSPACE_STORE,
            contract.Failure.UNRETIRED_WORKSPACE_ENTRY,
            ["containers/workspace-1"],
        )

    def test_captured_tree_names_its_non_regular_entry(self) -> None:
        os.mkfifo(self.state / "pipe")
        with self.assertRaises(journal.Rejected) as raised:
            _ = install.RECOVERY.managed_tree(self.control)
        self.assertEqual(journal.Failure.OWNERSHIP, raised.exception.failure)
        self.assertEqual("state/pipe", raised.exception.entry)


class RejectionMappingTest(unittest.TestCase):
    PREPARATION = contract.Stage.PREPARATION

    def test_a_state_rejection_keeps_its_component_and_entries(self) -> None:
        rejection = contract.StateRejection(
            contract.Failure.UNOWNED_STATE_ENTRY,
            contract.StateComponent.INSTALLATION_STATE,
            ("state/broker",),
        )
        self.assertEqual(
            '{"stage":"PREPARATION","reason":"UNOWNED_STATE_ENTRY","adapterCode":null,'
            '"state":{"component":"kast-installation-state","entries":["state/broker"]},'
            '"type":"REJECTED"}\n',
            install.encoded(install.rejection(install.StateRejected(rejection))).decode(),
        )

    def test_adapter_failures_keep_their_codes(self) -> None:
        lifecycle = install.LIFECYCLE
        cases = (
            (
                journal.Rejected(journal.Failure.RECEIPT),
                contract.Rejected(self.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN, "RECEIPT_REJECTED"),
            ),
            (
                journal.Rejected(journal.Failure.CONTROL_LIMIT),
                contract.Rejected(self.PREPARATION, contract.Failure.CONTROL_REJECTED, "CONTROL_LIMIT_EXCEEDED"),
            ),
            (
                journal.Rejected(journal.Failure.FILESYSTEM),
                contract.Rejected(self.PREPARATION, contract.Failure.FILESYSTEM_REJECTED, "FILESYSTEM_REJECTED"),
            ),
            (
                journal.Rejected(journal.Failure.OWNERSHIP, "state/run/c.sock"),
                contract.Rejected(
                    self.PREPARATION,
                    contract.Failure.UNOWNED_STATE_ENTRY,
                    "ANCHOR_OWNERSHIP_UNPROVEN",
                    contract.StateEntries(
                        contract.StateComponent.CONTROL_PAYLOAD_TREE, ("state/run/c.sock",)
                    ),
                ),
            ),
            (
                lifecycle.Rejected(lifecycle.Failure.STATE_REJECTED),
                contract.Rejected(self.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN, "STATE_REJECTED"),
            ),
            (
                lifecycle.Rejected(lifecycle.Failure.CONTROL_LIMIT_EXCEEDED),
                contract.Rejected(self.PREPARATION, contract.Failure.CONTROL_REJECTED, "CONTROL_LIMIT_EXCEEDED"),
            ),
            (
                OSError(errno.ENOSPC, "full"),
                contract.Rejected(self.PREPARATION, contract.Failure.STORAGE_EXHAUSTED),
            ),
            (
                OSError(errno.EIO, "io"),
                contract.Rejected(self.PREPARATION, contract.Failure.FILESYSTEM_REJECTED, "EIO"),
            ),
            (
                OSError("record owner rejected"),
                contract.Rejected(self.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN),
            ),
            (
                ValueError("product reference binding"),
                contract.Rejected(self.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN),
            ),
            (
                KeyboardInterrupt(),
                contract.Rejected(self.PREPARATION, contract.Failure.CANCELLED),
            ),
        )
        for problem, expected in cases:
            with self.subTest(problem=repr(problem)):
                self.assertEqual(expected, install.rejection(problem))

    def test_a_stage_rejection_is_preserved(self) -> None:
        failure = contract.Rejected(contract.Stage.FENCE, contract.Failure.CONCURRENT_CHANGE)
        self.assertIs(failure, install.rejection(install.StageRejected(failure)))

    def test_an_unexpected_exception_is_a_defect(self) -> None:
        for problem in (TypeError("x"), KeyError("y"), RuntimeError("z")):
            with self.subTest(problem=type(problem).__name__):
                self.assertEqual(
                    contract.Rejected(
                        self.PREPARATION,
                        contract.Failure.INSTALLATION_DEFECT,
                        type(problem).__name__,
                    ),
                    install.rejection(problem),
                )

    def test_an_assertion_is_never_an_outcome(self) -> None:
        violation = AssertionError("fixture violation")
        with self.assertRaises(AssertionError) as raised:
            _ = install.rejection(violation)
        self.assertIs(violation, raised.exception)

    def test_a_double_fixture_violation_escapes_activation(self) -> None:
        # A scripted double that rejects an unexpected call must fail the test, not
        # become INSTALLATION_DEFECT, even though activation catches every Exception.
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve() / "kast"
        root.mkdir(mode=0o700)
        violation = AssertionError("unexpected call to admit_prior")
        with (
            patch.object(activation, "admit_prior", side_effect=violation),
            contextlib.redirect_stderr(io.StringIO()),
            self.assertRaises(AssertionError) as raised,
        ):
            _ = activation.activate(root, root / "home", Mock())
        self.assertIs(violation, raised.exception)

    def test_entrypoint_lets_a_fixture_violation_escape(self) -> None:
        violation = AssertionError("unexpected release source call")
        source = Mock()
        source.selection.side_effect = violation
        with (
            patch.object(install.release, "resolve_selection", side_effect=violation),
            patch.object(install.platform, "system", return_value="Linux"),
            patch.object(install.platform, "machine", return_value="x86_64"),
            contextlib.redirect_stderr(io.StringIO()),
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaises(AssertionError) as raised,
        ):
            temporary = tempfile.TemporaryDirectory()
            self.addCleanup(temporary.cleanup)
            with patch.dict(os.environ, {"HOME": temporary.name}):
                _ = install.main(["--channel", "stable"], source=source)
        self.assertIs(violation, raised.exception)

    def test_activation_reports_an_unexpected_exception_as_a_defect(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve() / "kast"
        root.mkdir(mode=0o700)
        admitted = Mock()
        observations = io.StringIO()
        with (
            patch.object(activation, "admit_prior", side_effect=KeyError("unexpected")),
            contextlib.redirect_stderr(observations),
        ):
            result = activation.activate(root, root / "home", admitted)
        self.assertEqual(
            contract.Rejected(self.PREPARATION, contract.Failure.INSTALLATION_DEFECT, "KeyError"),
            result,
        )
        self.assertIn('"reason":"INSTALLATION_DEFECT"', observations.getvalue())
        self.assertNotIn("unexpected", observations.getvalue())

    def test_activation_reports_a_captured_tree_entry_once(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name).resolve() / "kast"
        root.mkdir(mode=0o700)
        observations = io.StringIO()
        with (
            patch.object(
                activation,
                "admit_prior",
                side_effect=journal.Rejected(journal.Failure.OWNERSHIP, "state/run/c.sock"),
            ),
            contextlib.redirect_stderr(observations),
        ):
            result = activation.activate(root, root / "home", Mock())
        assert isinstance(result, contract.Rejected)
        self.assertEqual(contract.Failure.UNOWNED_STATE_ENTRY, result.reason)
        observed = [
            json.loads(line)
            for line in observations.getvalue().splitlines()
            if '"component"' in line
        ]
        self.assertEqual(
            [
                {
                    "reason": "UNOWNED_STATE_ENTRY",
                    "component": "kast-control-payload-tree",
                    "entries": ["state/run/c.sock"],
                    "outcome": "REJECTED",
                }
            ],
            observed,
        )
        self.assertNotIn(str(root), observations.getvalue())


if __name__ == "__main__":
    _ = unittest.main()
