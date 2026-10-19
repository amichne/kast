"""Independent arithmetic fixtures for pure paired benchmark rules; no I/O."""

import json
import math
import random
import unittest
from collections.abc import Iterable
from dataclasses import FrozenInstanceError, asdict, replace
from typing import Literal, TypedDict, Unpack

import agent_efficiency_metrics as metrics

type JSON = None | bool | int | float | str | list[JSON] | dict[str, JSON]


def record(**changes: JSON) -> dict[str, JSON]:
    value: dict[str, JSON] = dict(
        task_id="a",
        family="selection",
        arm="control",
        repeat=0,
        correct=True,
        semantic_misselections=0,
        false_completeness=0,
        wall_seconds=10.0,
        cost_usd=10.0,
        input_tokens=10,
        output_tokens=4,
        cache_read_tokens=3,
        tool_calls=2,
    )
    value.update(changes)
    return value


def fixture() -> tuple[metrics.Trial, ...]:
    # Each task has a correct and an incorrect attempt in EACH arm. Incorrect
    # attempts cost three times as much as correct ones and must not disappear.
    # a: control exposure 40, treatment 8 -> 5x.
    # b: control exposure 80, treatment 12 -> 20/3x.
    # Pooled: 2/120 vs 2/20 -> 6x (not mean(5, 20/3)).
    trials: list[metrics.Trial] = []
    cases: tuple[tuple[str, str, tuple[float, float], tuple[float, float]], ...] = (
        ("a", "selection", (10.0, 30.0), (2.0, 6.0)),
        ("b", "completion", (20.0, 60.0), (3.0, 9.0)),
    )
    for task, family, control, treatment in cases:
        arms: tuple[tuple[metrics.Arm, tuple[float, float]], ...] = (
            ("control", control),
            ("treatment", treatment),
        )
        for arm, exposures in arms:
            for repeat, exposure in enumerate(exposures):
                trials.append(
                    metrics.Trial(
                        task_id=task,
                        family=family,
                        arm=arm,
                        repeat=repeat,
                        correct=repeat == 0,
                        wall_seconds=exposure,
                        cost_usd=exposure,
                        semantic_misselections=0,
                        false_completeness=0,
                        input_tokens=10,
                        output_tokens=4,
                        cache_read_tokens=3,
                        tool_calls=2,
                    )
                )
    return tuple(trials)


class BootstrapOptions(TypedDict, total=False):
    bootstrap_seed: object
    bootstrap_samples: object


class AccuracyEvidenceTest(unittest.TestCase):
    def test_two_equal_accuracy_tasks_cannot_establish_two_percent_noninferiority(self):
        result = compare()
        self.assertNotEqual("ACCEPT", result["type"])

    def test_exact_binomial_endpoints_and_independent_small_polynomials(self):
        for count, harmed, expected in (
            (1, 0, 0.95),
            (2, 0, 1 - math.sqrt(0.05)),
            (2, 1, math.sqrt(0.95)),
            (2, 2, 1.0),
        ):
            with self.subTest(count=count, harmed=harmed):
                trials = tuple(
                    replace(
                        t, task_id=f"case-{i}", repeat=0, correct=t.arm == "control" or i >= harmed
                    )
                    for i in range(count)
                    for t in fixture()
                    if t.task_id == "a" and t.repeat == 0
                )
                result = compare(
                    trials,
                    expected_task_ids=tuple(f"case-{i}" for i in range(count)),
                    bootstrap_samples=2,
                )
                self.assertAlmostEqual(expected, result["accuracy_evidence"]["upper_bound"])

    def test_zero_harm_needs_149_independent_tasks_for_this_conservative_bound(self):
        for count, expected in ((100, False), (148, False), (149, True)):
            with self.subTest(count=count):
                trials = tuple(
                    replace(t, task_id=f"case-{i}", repeat=0, correct=True)
                    for i in range(count)
                    for t in fixture()
                    if t.task_id == "a" and t.repeat == 0
                )
                result = compare(
                    trials,
                    expected_task_ids=tuple(f"case-{i}" for i in range(count)),
                    bootstrap_samples=2,
                )
                self.assertEqual(expected, result["gates"]["accuracy_noninferiority_95"])
                evidence = result["accuracy_evidence"]
                self.assertEqual(count, evidence["task_clusters"])
                self.assertAlmostEqual(1 - 0.05 ** (1 / count), evidence["upper_bound"])

    def test_repeats_do_not_manufacture_independent_accuracy_samples(self):
        trials = tuple(
            replace(t, repeat=t.repeat + repeat * 2) for repeat in range(100) for t in fixture()
        )
        result = compare(trials, bootstrap_samples=2)
        self.assertEqual(2, result["accuracy_evidence"]["task_clusters"])
        self.assertAlmostEqual(1 - math.sqrt(0.05), result["accuracy_evidence"]["upper_bound"])
        self.assertFalse(result["gates"]["accuracy_noninferiority_95"])

    def test_large_paired_fixture_can_pass_the_statistical_and_rate_gates(self):
        trials = tuple(
            metrics.Trial(
                task_id=f"case-{i}",
                family="selection",
                arm=arm,
                repeat=0,
                correct=True,
                semantic_misselections=0,
                false_completeness=0,
                wall_seconds=(10 + i % 3) if arm == "control" else (1 + (i % 2) / 10),
                cost_usd=(10 + i % 3) if arm == "control" else (1 + (i % 2) / 10),
                input_tokens=10,
                output_tokens=1,
                cache_read_tokens=0,
                tool_calls=1,
            )
            for i in range(149)
            for arm in ("control", "treatment")
        )
        result = compare(
            trials,
            expected_task_ids=tuple(f"case-{i}" for i in range(149)),
            bootstrap_samples=10000,
        )
        self.assertEqual("ACCEPT", result["type"])


def compare(
    trials: Iterable[metrics.Trial] | None = None,
    *,
    expected_task_ids: tuple[str, ...] = ("a", "b"),
    bootstrap_seed: int = 917,
    bootstrap_samples: int = 2000,
) -> metrics.ComparisonData:
    result = metrics.compare(
        fixture() if trials is None else trials,
        expected_task_ids=expected_task_ids,
        bootstrap_seed=bootstrap_seed,
        bootstrap_samples=bootstrap_samples,
    )
    if "control" not in result:
        raise AssertionError(result)
    return result


def available(value: metrics.AvailableData | metrics.InsufficientData) -> metrics.AvailableData:
    assert value["type"] == "AVAILABLE", value
    return value


def interval(value: metrics.IntervalData | metrics.InsufficientData) -> metrics.IntervalData:
    assert value["type"] == "AVAILABLE", value
    return value


def insufficient(
    value: metrics.AvailableData | metrics.IntervalData | metrics.InsufficientData,
) -> metrics.InsufficientData:
    assert value["type"] == "INSUFFICIENT_EVIDENCE", value
    return value


def known_cost(trial: metrics.Trial) -> float:
    assert trial.cost_usd is not None
    return trial.cost_usd


class TrialParsingTest(unittest.TestCase):
    def test_exact_boundary_preserves_fields_and_is_frozen_and_slotted(self):
        parsed = metrics.parse_trial(json.loads(json.dumps(record())))
        self.assertIsInstance(parsed, metrics.ParsedTrial)
        assert isinstance(parsed, metrics.ParsedTrial)
        self.assertEqual(record(), asdict(parsed.trial))
        self.assertFalse(hasattr(parsed.trial, "__dict__"))
        for name in record():
            with self.subTest(field=name), self.assertRaises(FrozenInstanceError):
                setattr(parsed.trial, name, None)
        self.assertEqual(record(), asdict(parsed.trial))

    def test_every_field_is_required_even_nullable_cost_and_unknown_fields_rejected(self):
        for name in record():
            incomplete = record()
            del incomplete[name]
            with self.subTest(name=name):
                self.assertEqual(
                    metrics.InvalidInput("INVALID_FIELDS"), metrics.parse_trial(incomplete)
                )
        invalid_values: tuple[JSON, ...] = (
            None,
            [],
            3,
            record(total_tokens=17),
            record(extra=False),
        )
        for value in invalid_values:
            with self.subTest(value=value):
                self.assertEqual(metrics.InvalidInput("INVALID_FIELDS"), metrics.parse_trial(value))
        self.assertIsInstance(metrics.parse_trial(record(cost_usd=None)), metrics.ParsedTrial)

    def test_finite_boundary_failure_variants(self):
        cases: tuple[tuple[str, JSON, metrics.InputReason], ...] = (
            ("task_id", "", "INVALID_IDENTITY"),
            ("family", " family", "INVALID_IDENTITY"),
            ("task_id", 2, "INVALID_IDENTITY"),
            ("arm", "unknown", "INVALID_ARM"),
            ("arm", None, "INVALID_ARM"),
            ("correct", 1, "INVALID_BOOLEAN"),
            ("correct", "false", "INVALID_BOOLEAN"),
            ("cache_read_tokens", 11, "CACHE_EXCEEDS_INPUT"),
        )
        for field, value, reason in cases:
            with self.subTest(field=field, value=value):
                self.assertEqual(
                    metrics.InvalidInput(reason, field),
                    metrics.parse_trial(record(**{field: value})),
                )

    def test_all_counts_reject_negative_boolean_fractional_and_nonfinite(self):
        for field in (
            "repeat",
            "semantic_misselections",
            "false_completeness",
            "input_tokens",
            "output_tokens",
            "cache_read_tokens",
            "tool_calls",
        ):
            for value in (-1, False, True, 0.0, 1.0, 1.5, "1", math.nan, math.inf):
                with self.subTest(field=field, value=value):
                    self.assertEqual(
                        metrics.InvalidInput("INVALID_QUANTITY", field),
                        metrics.parse_trial(record(**{field: value})),
                    )
            if field not in ("semantic_misselections", "false_completeness"):
                with self.subTest(field=field, value=None):
                    self.assertEqual(
                        metrics.InvalidInput("INVALID_QUANTITY", field),
                        metrics.parse_trial(record(**{field: None})),
                    )

    def test_null_audit_counts_remain_unknown_regardless_of_answer_correctness(self) -> None:
        for correct in (False, True):
            for semantic, completeness in ((None, 3), (2, None), (None, None)):
                with self.subTest(correct=correct, semantic=semantic, completeness=completeness):
                    value = record(
                        correct=correct,
                        semantic_misselections=semantic,
                        false_completeness=completeness,
                    )
                    parsed = metrics.parse_trial(json.loads(json.dumps(value)))
                    assert isinstance(parsed, metrics.ParsedTrial)
                    self.assertEqual(value, asdict(parsed.trial))
                    direct = replace(
                        fixture()[0],
                        correct=correct,
                        semantic_misselections=semantic,
                        false_completeness=completeness,
                    )
                    self.assertEqual(semantic, direct.semantic_misselections)
                    self.assertEqual(completeness, direct.false_completeness)

    def test_measurements_reject_negative_nonfinite_wrong_type_and_huge_integers(self):
        for field in ("wall_seconds", "cost_usd"):
            for value in (-0.1, math.nan, math.inf, -math.inf, True, "1", 10**400):
                with self.subTest(field=field, value=value):
                    self.assertEqual(
                        metrics.InvalidInput("INVALID_MEASUREMENT", field),
                        metrics.parse_trial(record(**{field: value})),
                    )
        self.assertEqual(
            metrics.InvalidInput("INVALID_MEASUREMENT", "wall_seconds"),
            metrics.parse_trial(record(wall_seconds=None)),
        )

    def test_cache_is_subset_not_additional_input(self):
        self.assertIsInstance(
            metrics.parse_trial(record(cache_read_tokens=10)), metrics.ParsedTrial
        )
        result = compare(bootstrap_samples=100)
        # Four trials each: 4 * (10 input + 4 output), not 4 * (10 + 4 + 3).
        self.assertEqual(56, result["control"]["totals"]["total_tokens"])
        self.assertEqual(12, result["control"]["totals"]["cache_read_tokens"])
        self.assertEqual(
            metrics.InvalidInput("CACHE_EXCEEDS_INPUT", "cache_read_tokens"),
            metrics.parse_trial(record(input_tokens=0, cache_read_tokens=1)),
        )

    def test_zero_counts_and_exact_large_integer_counts_preserve_information(self):
        self.assertIsInstance(
            metrics.parse_trial(
                record(
                    repeat=0,
                    input_tokens=0,
                    output_tokens=0,
                    cache_read_tokens=0,
                    tool_calls=0,
                    wall_seconds=0.0,
                    cost_usd=0.0,
                )
            ),
            metrics.ParsedTrial,
        )
        large = 2**100 + 1
        parsed = metrics.parse_trial(record(input_tokens=large, cache_read_tokens=large))
        self.assertIsInstance(parsed, metrics.ParsedTrial)
        assert isinstance(parsed, metrics.ParsedTrial)
        self.assertEqual(large, parsed.trial.input_tokens)
        self.assertEqual(large, parsed.trial.cache_read_tokens)

    def test_integer_measurements_are_not_coerced_or_unknown_cost_fabricated(self) -> None:
        large = 2**100 + 1
        parsed = metrics.parse_trial(record(wall_seconds=large, cost_usd=large))
        assert isinstance(parsed, metrics.ParsedTrial)
        self.assertIs(type(parsed.trial.wall_seconds), int)
        self.assertIs(type(parsed.trial.cost_usd), int)
        self.assertEqual(large, parsed.trial.wall_seconds)
        self.assertEqual(large, parsed.trial.cost_usd)
        unknown = metrics.parse_trial(record(cost_usd=None))
        assert isinstance(unknown, metrics.ParsedTrial)
        self.assertIsNone(unknown.trial.cost_usd)

    def test_large_token_totals_remain_exact_and_cache_is_not_added(self) -> None:
        large = 2**100 + 1
        trials = tuple(
            replace(t, input_tokens=large, output_tokens=large + 2, cache_read_tokens=large - 1)
            for t in fixture()
        )
        result = compare(trials, bootstrap_samples=100)
        for arm in ("control", "treatment"):
            totals = result[arm]["totals"]
            self.assertEqual(4 * large, totals["input_tokens"])
            self.assertEqual(4 * (large + 2), totals["output_tokens"])
            self.assertEqual(4 * (large - 1), totals["cache_read_tokens"])
            self.assertEqual(4 * (2 * large + 2), totals["total_tokens"])
        _ = json.dumps(result, allow_nan=False)

    def test_direct_construction_also_enforces_invariants(self):
        with self.assertRaisesRegex(ValueError, "INVALID_QUANTITY: repeat"):
            _ = replace(fixture()[0], repeat=-1)

    def test_direct_construction_rejects_invalid_audit_counts(self) -> None:
        for field in ("semantic_misselections", "false_completeness"):
            for value in (-1, False, True, 0.0, 1.0, 1.5, "1", math.nan, math.inf):
                with (
                    self.subTest(field=field, value=value),
                    self.assertRaisesRegex(ValueError, f"INVALID_QUANTITY: {field}"),
                ):
                    _ = replace(fixture()[0], **{field: value})


class PairedMetricsTest(unittest.TestCase):
    def test_hand_computed_totals_quantiles_and_failure_charging(self):
        result = compare()
        self.assertEqual("REJECT", result["type"])
        self.assertEqual(
            dict(
                trials=4,
                correct=2,
                incorrect=2,
                semantic_misselections=0,
                false_completeness=0,
                wall_seconds=120.0,
                cost_usd=120.0,
                known_cost_usd=120.0,
                missing_cost_trials=0,
                zero_cost_trials=0,
                input_tokens=40,
                output_tokens=16,
                cache_read_tokens=12,
                total_tokens=56,
                tool_calls=8,
            ),
            result["control"]["totals"],
        )
        c, t = result["control"], result["treatment"]
        self.assertEqual(0.5, c["accuracy"])
        self.assertEqual(0.5, t["accuracy"])
        self.assertEqual(25.0, c["median_wall_seconds"])
        self.assertAlmostEqual(51.0, c["p90_wall_seconds"])
        self.assertAlmostEqual(55.5, c["p95_wall_seconds"])
        self.assertEqual(4.5, t["median_wall_seconds"])
        self.assertAlmostEqual(8.1, t["p90_wall_seconds"])
        self.assertAlmostEqual(8.55, t["p95_wall_seconds"])
        self.assertEqual(60.0, available(c["correct_per_hour"])["value"])
        self.assertEqual(360.0, available(t["correct_per_hour"])["value"])
        self.assertEqual(1 / 60, available(c["correct_per_dollar"])["value"])
        self.assertEqual(0.1, available(t["correct_per_dollar"])["value"])
        for name in ("throughput", "efficiency"):
            self.assertEqual({"value": 6.0, "type": "AVAILABLE"}, result[name]["ratio"])
            # Resampling whole tasks: aa -> 5, ab/ba -> 6, bb -> 20/3.
            self.assertEqual(
                {"lower": 5.0, "upper": 20 / 3, "type": "AVAILABLE"}, result[name]["ci95"]
            )
        self.assertFalse(result["gates"]["accuracy_noninferiority_95"])
        self.assertTrue(
            all(
                value
                for name, value in result["gates"].items()
                if name != "accuracy_noninferiority_95"
            )
        )
        self.assertEqual(
            (917, 2000, 2),
            (result["bootstrap_seed"], result["bootstrap_samples"], result["task_clusters"]),
        )
        # JSON serialization must forbid NaN/Infinity, not just round trip them.
        _ = json.dumps(result, allow_nan=False)

    def test_family_summaries_preserve_exposures_and_accuracy(self):
        result = compare(bootstrap_samples=100)
        families = result["per_family"]
        self.assertEqual({"selection", "completion"}, set(families))
        for family, control_wall, treatment_wall in (
            ("selection", 40.0, 8.0),
            ("completion", 80.0, 12.0),
        ):
            self.assertTrue(families[family]["accuracy_gate"])
            self.assertEqual(0.5, families[family]["control"]["accuracy"])
            self.assertEqual(control_wall, families[family]["control"]["totals"]["wall_seconds"])
            self.assertEqual(
                treatment_wall, families[family]["treatment"]["totals"]["wall_seconds"]
            )

    def test_exact_fivefold_point_estimate_has_degenerate_interval_not_acceptance(self):
        trials = tuple(
            replace(t, wall_seconds=t.wall_seconds * 0.75, cost_usd=known_cost(t) * 0.75)
            if t.task_id == "b" and t.arm == "control"
            else t
            for t in fixture()
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        for name in ("throughput", "efficiency"):
            self.assertEqual(5.0, available(result[name]["ratio"])["value"])
            self.assertEqual(
                {"type": "INSUFFICIENT_EVIDENCE", "reason": "DEGENERATE_INTERVAL"},
                result[name]["ci95"],
            )

    def test_confidence_lower_bound_below_five_rejects_even_point_estimate_above_five(self):
        trials = tuple(
            replace(t, wall_seconds=t.wall_seconds * 1.25, cost_usd=known_cost(t) * 1.25)
            if t.task_id == "a" and t.arm == "treatment"
            else t
            for t in fixture()
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("REJECT", result["type"])
        self.assertGreater(available(result["throughput"]["ratio"])["value"], 5)
        self.assertEqual(4.0, interval(result["throughput"]["ci95"])["lower"])
        self.assertFalse(result["gates"]["throughput_lower_ci_at_least_5"])
        self.assertFalse(result["gates"]["efficiency_lower_ci_at_least_5"])

    def test_throughput_and_efficiency_must_independently_clear_the_threshold(self):
        cases: tuple[
            tuple[str, Literal["efficiency", "throughput"], Literal["efficiency", "throughput"]],
            ...,
        ] = (
            ("cost_usd", "efficiency", "throughput"),
            ("wall_seconds", "throughput", "efficiency"),
        )
        for field, failing, passing in cases:
            with self.subTest(field=field):
                trials = tuple(
                    (
                        replace(t, cost_usd=known_cost(t) * 2)
                        if field == "cost_usd"
                        else replace(t, wall_seconds=t.wall_seconds * 2)
                    )
                    if t.arm == "treatment"
                    else t
                    for t in fixture()
                )
                result = compare(trials, bootstrap_samples=100)
                self.assertEqual("REJECT", result["type"])
                self.assertEqual(3.0, available(result[failing]["ratio"])["value"])
                self.assertEqual(2.5, interval(result[failing]["ci95"])["lower"])
                self.assertEqual(6.0, available(result[passing]["ratio"])["value"])
                self.assertEqual(5.0, interval(result[passing]["ci95"])["lower"])

    def test_family_regression_cannot_hide_behind_improved_overall_accuracy(self):
        # Both control families: 1/3 correct. Treatment selection: 0/3;
        # treatment completion: 3/3. Overall improves 2/6 -> 3/6.
        # Extra repeat keeps the control rate defined in every task cluster.
        expanded = list(fixture())
        expanded.extend(replace(t, repeat=2, correct=False) for t in fixture() if t.repeat == 1)
        trials = tuple(
            replace(t, correct=t.task_id == "b") if t.arm == "treatment" else t for t in expanded
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("REJECT", result["type"])
        self.assertTrue(result["gates"]["accuracy_overall"])
        self.assertFalse(result["gates"]["accuracy_per_family"])
        self.assertFalse(result["per_family"]["selection"]["accuracy_gate"])
        self.assertTrue(result["per_family"]["completion"]["accuracy_gate"])

    def test_overall_accuracy_regression_rejects(self):
        trials = tuple(
            replace(t, correct=False) if t.arm == "treatment" and t.task_id == "a" else t
            for t in fixture()
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("REJECT", result["type"])
        self.assertFalse(result["gates"]["accuracy_overall"])

    def test_additional_semantic_misselections_or_false_completeness_reject(self):
        for field in ("semantic_misselections", "false_completeness"):
            with self.subTest(field=field):
                trials = list(fixture())
                trials[2] = replace(trials[2], **{field: 1})
                result = compare(trials, bootstrap_samples=100)
                self.assertEqual("REJECT", result["type"])
                self.assertFalse(result["gates"][field])
                self.assertEqual(1, result["treatment"]["totals"][field])

    def test_existing_semantic_failures_remain_counted_and_no_increase_is_allowed(self):
        trials = tuple(
            replace(t, semantic_misselections=2, false_completeness=1) for t in fixture()
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("REJECT", result["type"])
        self.assertEqual(8, result["control"]["totals"]["semantic_misselections"])
        self.assertEqual(4, result["treatment"]["totals"]["false_completeness"])

    def test_partially_audited_groups_preserve_unknown_without_losing_known_totals(self) -> None:
        audited = tuple(
            replace(t, semantic_misselections=2, false_completeness=3) for t in fixture()
        )
        baseline = compare(audited, bootstrap_samples=100)
        for arm in ("control", "treatment"):
            for field in ("semantic_misselections", "false_completeness"):
                for repeat in (0, 1):
                    with self.subTest(arm=arm, field=field, repeat=repeat):
                        trials = tuple(
                            replace(t, **{field: None})
                            if t.arm == arm and t.task_id == "a" and t.repeat == repeat
                            else t
                            for t in audited
                        )
                        result = compare(trials, bootstrap_samples=100)
                        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
                        for reported_arm in ("control", "treatment"):
                            expected = baseline[reported_arm]["totals"]
                            self.assertEqual(
                                {**expected, field: None} if reported_arm == arm else expected,
                                result[reported_arm]["totals"],
                            )
                            for family in ("selection", "completion"):
                                expected_family = baseline["per_family"][family][reported_arm]
                                actual_family = result["per_family"][family][reported_arm]
                                self.assertEqual(
                                    {
                                        **expected_family,
                                        "totals": {**expected_family["totals"], field: None},
                                    }
                                    if reported_arm == arm and family == "selection"
                                    else expected_family,
                                    actual_family,
                                )
                        _ = json.dumps(result, allow_nan=False)

    def test_each_unknown_audit_blocks_acceptance_with_known_costs(self) -> None:
        baseline = compare(bootstrap_samples=100)
        self.assertEqual("REJECT", baseline["type"])
        for arm in ("control", "treatment"):
            for field in ("semantic_misselections", "false_completeness"):
                with self.subTest(arm=arm, field=field):
                    trials = tuple(
                        replace(t, **{field: None})
                        if t.arm == arm and t.task_id == "a" and t.repeat == 1
                        else t
                        for t in fixture()
                    )
                    result = compare(trials, bootstrap_samples=100)
                    self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
                    self.assertEqual({**baseline["gates"], field: False}, result["gates"])
                    self.assertTrue(result["gates"]["known_nonzero_cost"])
                    self.assertEqual(baseline["throughput"], result["throughput"])
                    self.assertEqual(baseline["efficiency"], result["efficiency"])
                    for reported_arm in ("control", "treatment"):
                        for metric in ("accuracy", "correct_per_hour", "correct_per_dollar"):
                            self.assertEqual(
                                baseline[reported_arm][metric], result[reported_arm][metric]
                            )

    def test_available_throughput_without_any_audit_even_when_cost_is_unknown(self) -> None:
        for missing_cost in (False, True):
            with self.subTest(missing_cost=missing_cost):
                trials = tuple(replace(t, cost_usd=None) if missing_cost else t for t in fixture())
                baseline = compare(trials, bootstrap_samples=100)
                result = compare(
                    tuple(
                        replace(t, semantic_misselections=None, false_completeness=None)
                        for t in trials
                    ),
                    bootstrap_samples=100,
                )
                self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
                self.assertFalse(result["gates"]["semantic_misselections"])
                self.assertFalse(result["gates"]["false_completeness"])
                self.assertEqual(baseline["throughput"], result["throughput"])
                self.assertEqual(6.0, available(result["throughput"]["ratio"])["value"])
                self.assertEqual(5.0, interval(result["throughput"]["ci95"])["lower"])
                self.assertEqual(baseline["efficiency"], result["efficiency"])
                for arm in ("control", "treatment"):
                    self.assertEqual(
                        {
                            **baseline[arm],
                            "totals": {
                                **baseline[arm]["totals"],
                                "semantic_misselections": None,
                                "false_completeness": None,
                            },
                        },
                        result[arm],
                    )
                _ = json.dumps(result, allow_nan=False)

    def test_missing_audit_is_insufficient_even_when_a_known_quality_gate_fails(self) -> None:
        trials = list(fixture())
        trials[2] = replace(trials[2], semantic_misselections=1, false_completeness=None)
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        self.assertFalse(result["gates"]["semantic_misselections"])
        self.assertFalse(result["gates"]["false_completeness"])
        self.assertEqual(1, result["treatment"]["totals"]["semantic_misselections"])
        self.assertIsNone(result["treatment"]["totals"]["false_completeness"])

    def test_missing_cost_even_on_failed_attempt_is_insufficient_not_free(self):
        trials = list(fixture())
        self.assertFalse(trials[3].correct)
        trials[3] = replace(trials[3], cost_usd=None)
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        totals = result["treatment"]["totals"]
        self.assertIsNone(totals["cost_usd"])
        self.assertEqual(14.0, totals["known_cost_usd"])
        self.assertEqual(1, totals["missing_cost_trials"])
        self.assertEqual(20.0, totals["wall_seconds"])
        self.assertFalse(result["gates"]["known_nonzero_cost"])
        for value in (
            result["treatment"]["correct_per_dollar"],
            result["efficiency"]["ratio"],
            result["efficiency"]["ci95"],
        ):
            self.assertEqual({"type": "INSUFFICIENT_EVIDENCE", "reason": "MISSING_COST"}, value)
        self.assertEqual("AVAILABLE", result["throughput"]["ci95"]["type"])
        _ = json.dumps(result, allow_nan=False)

    def test_any_zero_cost_trial_blocks_acceptance_even_with_positive_aggregate(self):
        trials = list(fixture())
        trials[3] = replace(trials[3], cost_usd=0.0)
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        self.assertEqual("ZERO_COST", insufficient(result["efficiency"]["ratio"])["reason"])
        self.assertFalse(result["gates"]["known_nonzero_cost"])

    def test_zero_correct_control_never_becomes_infinity_or_zero_ratio(self):
        for arms in (("control",), ("control", "treatment")):
            trials = tuple(replace(t, correct=False) if t.arm in arms else t for t in fixture())
            result = compare(trials, bootstrap_samples=100)
            self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
            self.assertEqual(0.0, available(result["control"]["correct_per_hour"])["value"])
            for name in ("throughput", "efficiency"):
                self.assertEqual(
                    {"type": "INSUFFICIENT_EVIDENCE", "reason": "ZERO_CONTROL_RATE"},
                    result[name]["ratio"],
                )
            _ = json.dumps(result, allow_nan=False)

    def test_zero_correct_treatment_is_real_zero_but_degenerate_evidence(self):
        trials = tuple(replace(t, correct=False) if t.arm == "treatment" else t for t in fixture())
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        self.assertEqual(0.0, available(result["throughput"]["ratio"])["value"])
        self.assertEqual(
            "DEGENERATE_INTERVAL", insufficient(result["throughput"]["ci95"])["reason"]
        )
        self.assertFalse(result["gates"]["accuracy_overall"])

    def test_undefined_cluster_resamples_are_not_discarded(self):
        trials = tuple(
            replace(t, correct=False) if t.task_id == "a" and t.arm == "control" else t
            for t in fixture()
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("AVAILABLE", result["throughput"]["ratio"]["type"])
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        self.assertEqual("UNDEFINED_RESAMPLE", insufficient(result["throughput"]["ci95"])["reason"])
        self.assertEqual("UNDEFINED_RESAMPLE", insufficient(result["efficiency"]["ci95"])["reason"])

    def test_single_task_with_many_repeats_is_still_only_one_cluster(self):
        trials = tuple(t for t in fixture() if t.task_id == "a")
        result = compare(trials, expected_task_ids=("a",), bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        self.assertEqual("TOO_FEW_CLUSTERS", insufficient(result["throughput"]["ci95"])["reason"])

    def test_zero_wall_and_numeric_overflow_fail_closed(self):
        trials = tuple(replace(t, wall_seconds=0.0) if t.arm == "control" else t for t in fixture())
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        self.assertEqual("ZERO_WALL", insufficient(result["throughput"]["ratio"])["reason"])
        huge = tuple(replace(t, wall_seconds=1e308) for t in fixture())
        self.assertEqual(
            {"type": "INSUFFICIENT_EVIDENCE", "reason": "NUMERIC_RANGE"},
            metrics.compare(huge, expected_task_ids=("a", "b")),
        )
        tiny = tuple(
            replace(t, wall_seconds=5e-324) if t.arm == "treatment" else t for t in fixture()
        )
        result = compare(tiny, bootstrap_samples=100)
        self.assertEqual("NUMERIC_RANGE", insufficient(result["throughput"]["ratio"])["reason"])
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["type"])
        _ = json.dumps(result, allow_nan=False)

    def test_reproducible_order_independent_and_no_mutation_of_input_or_global_rng(self):
        trials = fixture()
        snapshot = tuple(asdict(t) for t in trials)
        rng_state = random.getstate()
        result = compare(trials, bootstrap_seed=3, bootstrap_samples=100)
        self.assertEqual(result, compare(reversed(trials), bootstrap_seed=3, bootstrap_samples=100))
        self.assertEqual(snapshot, tuple(asdict(t) for t in trials))
        self.assertEqual(rng_state, random.getstate())

    def test_unequal_repeat_counts_are_valid_when_exactly_paired(self):
        trials = fixture() + tuple(
            replace(t, repeat=t.repeat + 12) for t in fixture() if t.task_id == "a"
        )
        result = compare(trials, bootstrap_samples=100)
        self.assertEqual("REJECT", result["type"])
        # a is repeated twice: control 160, treatment 28; each arm 3 correct.
        self.assertEqual(160.0, result["control"]["totals"]["wall_seconds"])
        self.assertEqual(28.0, result["treatment"]["totals"]["wall_seconds"])
        self.assertEqual(160 / 28, available(result["throughput"]["ratio"])["value"])
        self.assertEqual(2, result["task_clusters"])


class PairAdmissionTest(unittest.TestCase):
    def assert_invalid(
        self,
        trials: Iterable[object],
        reason: metrics.InputReason,
        **options: Unpack[BootstrapOptions],
    ) -> None:
        result = metrics.compare(trials, expected_task_ids=("a", "b"), **options)
        self.assertEqual("INVALID_INPUT", result["type"])
        assert result["type"] == "INVALID_INPUT"
        self.assertEqual(reason, result["reason"])

    def test_missing_arm_repeat_duplicate_task_and_unknown_trial(self):
        trials = fixture()
        self.assert_invalid(trials[:-1], "MISSING_PAIR")
        self.assert_invalid(trials[:4], "MISSING_TASK")
        self.assert_invalid((), "MISSING_TASK")
        self.assert_invalid(trials + (trials[0],), "DUPLICATE_TRIAL")
        self.assert_invalid(trials + (replace(trials[0], task_id="c"),), "UNEXPECTED_TASK")
        self.assert_invalid((record(),), "INVALID_TRIAL")
        self.assert_invalid(
            tuple(replace(t, repeat=99) if t is trials[0] else t for t in trials), "MISSING_PAIR"
        )

    def test_family_must_match_between_arms_and_across_repeats(self):
        for index in (1, 2):
            trials = list(fixture())
            trials[index] = replace(trials[index], family="different")
            self.assert_invalid(trials, "FAMILY_MISMATCH")

    def test_expected_task_universe_is_required_exact_and_nonempty(self):
        for tasks in ((), ("a", "a"), ("",), ("a", " b"), ["a", "b"], (1,)):
            with self.subTest(tasks=tasks):
                result = metrics.compare(fixture(), expected_task_ids=tasks)
                assert result["type"] == "INVALID_INPUT"
                self.assertEqual("INVALID_EXPECTED_TASKS", result["reason"])

    def test_bootstrap_arguments_reject_ambiguous_or_unestimable_values(self):
        cases: tuple[BootstrapOptions, ...] = (
            {"bootstrap_seed": True},
            {"bootstrap_seed": 1.2},
            {"bootstrap_samples": 0},
            {"bootstrap_samples": 1},
            {"bootstrap_samples": True},
            {"bootstrap_samples": 2.5},
        )
        for options in cases:
            with self.subTest(options=options):
                self.assert_invalid(fixture(), "INVALID_BOOTSTRAP", **options)


if __name__ == "__main__":
    _ = unittest.main()
