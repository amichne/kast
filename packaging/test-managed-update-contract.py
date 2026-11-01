"""Independent cases for Host strategies and captured process identities."""

from __future__ import annotations

import json
import unittest
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from typing import cast

import managed_release as release
import managed_update_contract as contract


@dataclass(frozen=True)
class CompatibilityFixture:
    hostedContract: release.HostedContract
    ideBuild: str = "262.1"
    kotlinPluginBuild: str = "262.1-IJ"
    hostedPluginVersion: str = "1.2.3"


@dataclass(frozen=True)
class InspectionFixture:
    host: str
    home: str
    build: str
    compatibility: CompatibilityFixture
    runtimeIdentity: contract.RuntimeIdentity | None
    projects: tuple[object, ...] = ()
    protocol: int = 1
    background: str = "best_effort"
    capabilities: tuple[str, ...] = (
        "INSPECT",
        "OPEN",
        "PRESENT",
        "SYNC",
        "CONFIGURE_SYNC",
        "RELEASE",
        "CLOSE",
        "STATUS",
        "EXACT_USER_CLOSE",
        "HOST_SETTLEMENT",
        "HOST_RUNTIME_IDENTITY",
        "HOST_NORMAL_STOP",
    )
    type: str = "inspected"


@dataclass(frozen=True)
class LegacyProfileFixture:
    ideaHome: str
    profile: str
    process: contract.ProcessIdentity


@dataclass(frozen=True)
class BridgeFixture:
    processes: tuple[contract.ProcessIdentity, ...]
    result: InspectionFixture | None
    type: str = "INSPECTED"
    legacyProfile: LegacyProfileFixture | None = None


@dataclass(frozen=True)
class SettlementReplyFixture:
    host: str
    transaction: str
    runtimeIdentity: contract.RuntimeIdentity
    type: str = "host_settled"


class ManagedUpdateContractTest(unittest.TestCase):
    def reload_ack(
        self, loaded: contract.Loaded, transaction: str
    ) -> contract.ReloadAcknowledgment:
        request = contract.SettlementRequest(loaded, transaction, contract.SettlementAction.RELOAD)
        reply = json.loads(
            json.dumps(
                asdict(
                    SettlementReplyFixture(
                        loaded.identity.hostLifetime, transaction, loaded.identity
                    )
                )
            )
        )
        admitted = contract.admit_settlement_reply(request, reply)
        if not isinstance(admitted, contract.ReloadAcknowledgment):
            self.fail("matching reload acknowledgement rejected")
        self.assertIs(request, admitted.request)
        return admitted

    def setUp(self) -> None:
        self.process = contract.ProcessIdentity(
            123, 456, "/Applications/IDE.app/Contents/MacOS/idea"
        )
        self.build = contract.RuntimeBuild("1.2.3", "a" * 40, "b" * 40)
        self.runtime = contract.RuntimeIdentity(
            "/Applications/IDE.app/Contents",
            "/home/IDEA",
            123,
            456,
            "fixture-host",
            self.build,
            "ENABLED",
        )
        self.target = contract.Target(self.runtime.ideaHome, self.runtime.profile)
        self.hosted = release.HostedContract(
            "kast.ide-hosted.runtime.v3",
            "sha256:" + "c" * 64,
            "sha256:" + "d" * 64,
            (
                "workspace.lifecycle",
                "query.run",
                "source.read",
                "diagnostic.check",
                "change.plan",
                "change.apply",
                "change.recover",
            ),
        )
        self.loaded = contract.Loaded(
            self.process, self.runtime, self.hosted, contract.HostRefreshCapability.PREPARED
        )

    def test_strategy_needs_loaded_auto_reload_and_compatible_layout(self) -> None:
        self.assertEqual(
            contract.Strategy.STOPPED,
            contract.choose_strategy(contract.Stopped(self.target), False, False),
        )
        self.assertEqual(
            contract.Strategy.HOT_RELOAD, contract.choose_strategy(self.loaded, True, False)
        )
        for loaded, compatible in (
            (replace(self.loaded, identity=replace(self.runtime, autoReload="DISABLED")), True),
            (self.loaded, False),
        ):
            rejected = contract.choose_strategy(loaded, compatible, False)
            if not isinstance(rejected, contract.Rejected):
                self.fail("a running incompatible Host must reject without restart authorization")
            self.assertEqual(contract.Failure.RESTART_REQUIRED, rejected.reason)
            self.assertEqual(
                contract.Strategy.NORMAL_RESTART, contract.choose_strategy(loaded, compatible, True)
            )

    def test_old_host_capabilities_need_restart_even_with_loaded_auto_reload(self) -> None:
        prior = contract.parse_inspection(
            self.inspection(self.runtime), self.runtime.ideaHome, self.runtime.profile
        )
        if not isinstance(prior, contract.Loaded):
            self.fail("the actual prior capability set must remain a loaded Host")
        self.assertEqual(
            contract.Rejected(contract.Stage.PREPARATION, contract.Failure.RESTART_REQUIRED),
            contract.choose_strategy(prior, True, False),
        )
        self.assertEqual(
            contract.Strategy.NORMAL_RESTART, contract.choose_strategy(prior, True, True)
        )

    def test_new_refresh_capability_is_preserved_outside_runtime_identity(self) -> None:
        value = BridgeFixture(
            (self.process,),
            InspectionFixture(
                self.runtime.hostLifetime,
                self.runtime.ideaHome,
                "262.1",
                CompatibilityFixture(self.hosted),
                self.runtime,
                capabilities=("HOST_PREPARED_RELOAD_REFRESH",),
            ),
        )
        loaded = self.parse_fixture(value)
        self.assertEqual(self.loaded, loaded)
        if not isinstance(loaded, contract.Loaded):
            self.fail("new capability must preserve Loaded")
        self.assertIs(contract.HostRefreshCapability.PREPARED, loaded.refreshCapability)
        self.assertEqual(self.runtime, loaded.identity)
        self.assertIs(contract.Strategy.HOT_RELOAD, contract.choose_strategy(loaded, True, False))
        self.assertIs(
            contract.Strategy.NORMAL_RESTART, contract.choose_strategy(loaded, False, True)
        )
        held = contract.HeldSettlement(loaded, "transaction")
        self.assertEqual(
            loaded,
            contract.PreparedSettlement.from_reload_ack(
                self.reload_ack(held.host, held.transaction)
            ).host,
        )
        with self.assertRaises(ValueError):
            _ = self.reload_ack(
                replace(loaded, refreshCapability=contract.HostRefreshCapability.UNAVAILABLE),
                "transaction",
            )

    def test_refresh_transition_rejects_unknown_or_non_host_failures(self) -> None:
        for failure in (
            contract.Rejected(contract.Stage.HOST_REPLACEMENT, contract.Failure.CONCURRENT_CHANGE),
            contract.Rejected(
                contract.Stage.HOST_REPLACEMENT,
                contract.Failure.HOST_REJECTED,
                "CAPABILITY_MISMATCH",
            ),
            contract.Rejected(
                contract.Stage.HOST_REPLACEMENT, contract.Failure.HOST_REJECTED, "NEW_FAILURE"
            ),
            contract.Rejected(contract.Stage.HOST_REPLACEMENT, contract.Failure.HOST_REJECTED),
        ):
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                _ = contract.RefreshTransition(failure)

    def test_legacy_refresh_capability_never_grants_prepared_reload(self) -> None:
        legacy = replace(self.loaded, refreshCapability=contract.HostRefreshCapability.AVAILABLE)
        strategy = contract.choose_strategy(legacy, True, False)
        self.assertEqual(
            contract.Rejected(contract.Stage.PREPARATION, contract.Failure.RESTART_REQUIRED),
            strategy,
        )
        self.assertIs(
            contract.Strategy.NORMAL_RESTART, contract.choose_strategy(legacy, True, True)
        )
        with self.assertRaises(ValueError):
            _ = self.reload_ack(legacy, "held")

    def test_capability_and_transaction_alone_cannot_mint_prepared_settlement(self) -> None:
        factory = cast(Callable[..., object], contract.PreparedSettlement.from_reload_ack)
        with self.assertRaises(TypeError):
            _ = factory(self.loaded, "no-acknowledgement")
        reply = SettlementReplyFixture(
            self.loaded.identity.hostLifetime, "checked", self.loaded.identity
        )
        ordinary = contract.admit_settlement_reply(
            contract.SettlementRequest(self.loaded, "checked", contract.SettlementAction.ORDINARY),
            json.loads(json.dumps(asdict(reply))),
        )
        with self.assertRaises(TypeError):
            _ = factory(ordinary)
        request = contract.SettlementRequest(
            self.loaded, "checked", contract.SettlementAction.RELOAD
        )
        admitted = contract.admit_settlement_reply(request, json.loads(json.dumps(asdict(reply))))
        if not isinstance(admitted, contract.ReloadAcknowledgment):
            self.fail("matching reload ACK rejected")
        prepared = contract.PreparedSettlement.from_reload_ack(admitted)
        self.assertIs(admitted, prepared.acknowledgement)
        for changed in (
            replace(reply, host="wrong-host"),
            replace(reply, transaction="wrong-transaction"),
            replace(
                reply, runtimeIdentity=replace(self.loaded.identity, hostLifetime="wrong-runtime")
            ),
        ):
            with self.subTest(changed=changed):
                self.assertIsInstance(
                    contract.admit_settlement_reply(
                        request, json.loads(json.dumps(asdict(changed)))
                    ),
                    contract.Rejected,
                )

    def test_prepared_and_legacy_capability_facts_survive_decode_independently(self) -> None:
        for names, expected in (
            ((), contract.HostRefreshCapability.UNAVAILABLE),
            (("HOST_RELOAD_REFRESH",), contract.HostRefreshCapability.AVAILABLE),
            (("HOST_PREPARED_RELOAD_REFRESH",), contract.HostRefreshCapability.PREPARED),
            (
                ("HOST_RELOAD_REFRESH", "HOST_PREPARED_RELOAD_REFRESH"),
                contract.HostRefreshCapability.LEGACY_AND_PREPARED,
            ),
        ):
            with self.subTest(names=names):
                value = BridgeFixture(
                    (self.process,),
                    InspectionFixture(
                        self.runtime.hostLifetime,
                        self.runtime.ideaHome,
                        "262.1",
                        CompatibilityFixture(self.hosted),
                        self.runtime,
                        capabilities=names,
                    ),
                )
                loaded = self.parse_fixture(value)
                if not isinstance(loaded, contract.Loaded):
                    self.fail("capability fixture rejected")
                self.assertIs(expected, loaded.refreshCapability)
                self.assertEqual(self.runtime, loaded.identity)
                self.assertEqual(
                    expected.prepared,
                    contract.choose_strategy(loaded, True, False) == contract.Strategy.HOT_RELOAD,
                )
                if not expected.prepared:
                    with self.assertRaises(ValueError):
                        _ = self.reload_ack(loaded, "held")

    def test_selected_identity_and_contract_both_need_equality(self) -> None:
        identity = release.BuildIdentity("v1.2.3", "1.2.3", "a" * 40, "b" * 40)
        self.assertTrue(contract.matches_selected(self.loaded, identity, self.hosted))
        self.assertFalse(
            contract.matches_selected(
                self.loaded, replace(identity, buildRevision="e" * 40), self.hosted
            )
        )
        self.assertFalse(
            contract.matches_selected(
                self.loaded, identity, replace(self.hosted, wireSchemaDigest="sha256:" + "e" * 64)
            )
        )
        self.assertFalse(
            contract.matches_selected(contract.Stopped(self.target), identity, self.hosted)
        )

    def test_inspection_rejects_recycled_pid_mismatched_lifetime_and_profile(self) -> None:
        for runtime in (
            replace(self.runtime, processStartEpochMillis=999),
            replace(self.runtime, profile="/different"),
            replace(self.runtime, hostLifetime="different-host"),
        ):
            result = self.inspection(runtime)
            self.assertIsInstance(
                contract.parse_inspection(result, self.runtime.ideaHome, self.runtime.profile),
                contract.Rejected,
            )
        self.assertEqual(
            replace(self.loaded, refreshCapability=contract.HostRefreshCapability.UNAVAILABLE),
            contract.parse_inspection(
                self.inspection(self.runtime), self.runtime.ideaHome, self.runtime.profile
            ),
        )

    def test_multiple_instances_reject_instead_of_selecting_one(self) -> None:
        result = self.inspection(self.runtime, (self.process, replace(self.process, pid=124)))
        rejected = contract.parse_inspection(result, self.runtime.ideaHome, self.runtime.profile)
        if not isinstance(rejected, contract.Rejected):
            self.fail("multiple IDEA instances must reject")
        self.assertEqual(contract.Failure.TARGET_AMBIGUOUS, rejected.reason)

    def legacy_fixture(self) -> BridgeFixture:
        return BridgeFixture(
            (self.process,),
            InspectionFixture(
                "legacy-host",
                self.runtime.ideaHome,
                "262.1",
                CompatibilityFixture(self.hosted),
                None,
            ),
            legacyProfile=LegacyProfileFixture(
                self.runtime.ideaHome, self.runtime.profile, self.process
            ),
        )

    def parse_fixture(self, fixture: BridgeFixture) -> contract.Snapshot | contract.Rejected:
        return contract.parse_inspection(
            json.loads(json.dumps(asdict(fixture))), self.runtime.ideaHome, self.runtime.profile
        )

    def test_legacy_readmission_preserves_profile_only_for_the_same_process_and_host(self) -> None:
        attached = self.legacy_fixture()
        prior = self.parse_fixture(attached)
        if not isinstance(prior, contract.Legacy):
            self.fail("the attached legacy profile must admit a legacy snapshot")
        self.assertIsInstance(prior.host, contract.LegacyHost)
        bare = replace(attached, legacyProfile=None)
        self.assertEqual(
            contract.Rejected(contract.Stage.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN),
            self.parse_fixture(bare),
        )
        self.assertIs(
            prior,
            contract.readmit_inspection(json.loads(json.dumps(asdict(bare))), prior),
        )

    def test_legacy_readmission_rejects_changed_or_missing_captured_facts(self) -> None:
        attached = self.legacy_fixture()
        prior = self.parse_fixture(attached)
        if not isinstance(prior, contract.Legacy) or attached.result is None:
            self.fail("the attached legacy endpoint must admit a legacy snapshot")
        bare = replace(attached, legacyProfile=None)
        for changed in (
            replace(bare, processes=()),
            replace(bare, processes=(self.process, replace(self.process, pid=124))),
            replace(bare, processes=(replace(self.process, pid=124),)),
            replace(bare, processes=(replace(self.process, processStartEpochMillis=457),)),
            replace(bare, processes=(replace(self.process, executable="/different/idea"),)),
            replace(bare, result=None),
            replace(bare, result=replace(attached.result, host="different-host")),
            replace(bare, result=replace(attached.result, home="/different/home")),
            replace(bare, result=replace(attached.result, build="262.2")),
            replace(bare, result=replace(attached.result, runtimeIdentity=self.runtime)),
            replace(
                bare,
                result=replace(
                    attached.result,
                    compatibility=replace(
                        attached.result.compatibility, hostedPluginVersion="9.0.0"
                    ),
                ),
            ),
            replace(
                bare,
                result=replace(
                    attached.result,
                    compatibility=replace(
                        attached.result.compatibility, kotlinPluginBuild="changed-kotlin"
                    ),
                ),
            ),
            replace(
                bare,
                result=replace(
                    attached.result,
                    compatibility=replace(
                        attached.result.compatibility,
                        hostedContract=replace(self.hosted, wireSchemaDigest="sha256:" + "f" * 64),
                    ),
                ),
            ),
            attached,
        ):
            with self.subTest(changed=changed):
                self.assertEqual(
                    contract.Rejected(
                        contract.Stage.RE_ADMISSION, contract.Failure.CONCURRENT_CHANGE
                    ),
                    contract.readmit_inspection(json.loads(json.dumps(asdict(changed))), prior),
                )

    def test_legacy_without_endpoint_needs_fresh_attached_profile(self) -> None:
        attached = replace(self.legacy_fixture(), result=None)
        prior = self.parse_fixture(attached)
        if not isinstance(prior, contract.Legacy):
            self.fail("the attached profile must remain usable without an endpoint identity")
        self.assertEqual(contract.LegacyHostUnavailable.UNAVAILABLE, prior.host)
        self.assertIs(
            prior,
            contract.readmit_inspection(json.loads(json.dumps(asdict(attached))), prior),
        )
        self.assertEqual(
            contract.Rejected(contract.Stage.PREPARATION, contract.Failure.OWNERSHIP_UNPROVEN),
            contract.readmit_inspection(
                json.loads(json.dumps(asdict(replace(attached, legacyProfile=None)))), prior
            ),
        )

    def test_legacy_profile_cannot_admit_an_endpoint_from_another_home(self) -> None:
        attached = self.legacy_fixture()
        if attached.result is None:
            self.fail("the fixture needs an inspected endpoint")
        self.assertIsInstance(
            self.parse_fixture(
                replace(attached, result=replace(attached.result, home="/different/home"))
            ),
            contract.Rejected,
        )

    def test_profile_options_preserve_unrelated_bytes_and_have_one_property(self) -> None:
        before = b"-Xmx4g\r\n-Dunrelated=value\n-Didea.auto.reload.plugins=false\n# user comment\n"
        after = contract.profile_options(before)
        self.assertEqual(
            b"-Xmx4g\r\n-Dunrelated=value\n# user comment\n-Didea.auto.reload.plugins=true\n", after
        )
        self.assertEqual(after, contract.profile_options(after))

    def test_terminal_constructors_reject_missing_and_contradictory_evidence(self) -> None:
        identity = release.BuildIdentity("v1.2.3", "1.2.3", "a" * 40, "b" * 40)
        files = contract.InstalledFiles(
            identity, "c" * 64, "d" * 64, self.hosted, target=self.target
        )
        with self.assertRaises(ValueError):
            _ = contract.Activated(files, (), ())
        with self.assertRaises(ValueError):
            _ = contract.Activated(
                replace(files, identity=replace(identity, buildRevision="f" * 40)),
                (self.loaded,),
                (),
            )
        with self.assertRaises(ValueError):
            _ = contract.InstalledFiles(
                identity, "invalid", "d" * 64, self.hosted, target=self.target
            )
        with self.assertRaises(ValueError):
            _ = contract.Loaded(
                replace(self.process, processStartEpochMillis=999), self.runtime, self.hosted
            )
        with self.assertRaises(ValueError):
            _ = contract.Stopped(self.target, (self.process,))
        self.assertEqual(
            "INSTALLED_NOT_RUNNING",
            contract.InstalledNotRunning(files, contract.Stopped(self.target), ()).type,
        )

    def test_terminal_evidence_cannot_move_between_idea_installations_or_profiles(self) -> None:
        identity = release.BuildIdentity("v1.2.3", "1.2.3", "a" * 40, "b" * 40)
        files = contract.InstalledFiles(
            identity, "c" * 64, "d" * 64, self.hosted, target=self.target
        )
        foreign_targets = (
            replace(self.target, ideaHome="/Applications/Other.app/Contents"),
            replace(self.target, profile="/different/profile"),
        )
        for target in foreign_targets:
            with self.assertRaises(ValueError):
                _ = contract.Activated(replace(files, target=target), (self.loaded,), ())
            with self.assertRaises(ValueError):
                _ = contract.InstalledNotRunning(files, contract.Stopped(target), ())

    def inspection(
        self,
        runtime: contract.RuntimeIdentity,
        processes: tuple[contract.ProcessIdentity, ...] | None = None,
    ) -> object:
        result = InspectionFixture(
            "fixture-host",
            self.runtime.ideaHome,
            "262.1",
            CompatibilityFixture(self.hosted),
            runtime,
        )
        return json.loads(json.dumps(asdict(BridgeFixture(processes or (self.process,), result))))


if __name__ == "__main__":
    _ = unittest.main()
