"""Pure paired benchmark accounting; no benchmark execution or external state.

Trial input tokens INCLUDE cache-read tokens, unlike Pi API input, which
excludes cache tokens. Total tokens = input + output, never
input + output + cache reads. All attempts, including failures, consume wall
and cost exposure. Wall quantiles use linear interpolation at (n - 1) * p.
Bootstrap samples draw task IDs with replacement, retaining both arms and ALL
repeats of each selected task. Percentile intervals use the same interpolation.
Unknown/zero costs and any undefined resample fail closed; resamples are never
dropped. A single cluster or a zero-width interval is insufficient evidence.
Semantic counts require independent audits: null means unmeasured, not zero.
Any missing audit keeps its aggregate unknown and blocks acceptance without
suppressing available accuracy, throughput, or cost metrics.
"""

import math
import random
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, fields
from fractions import Fraction
from typing import Literal, TypedDict, TypeGuard

Arm = Literal["control", "treatment"]
InputReason = Literal[
    "INVALID_FIELDS",
    "INVALID_IDENTITY",
    "INVALID_ARM",
    "INVALID_BOOLEAN",
    "INVALID_QUANTITY",
    "INVALID_MEASUREMENT",
    "CACHE_EXCEEDS_INPUT",
    "INVALID_TRIAL",
    "INVALID_EXPECTED_TASKS",
    "INVALID_BOOTSTRAP",
    "UNEXPECTED_TASK",
    "MISSING_TASK",
    "DUPLICATE_TRIAL",
    "MISSING_PAIR",
    "FAMILY_MISMATCH",
]
EvidenceReason = Literal[
    "MISSING_COST",
    "ZERO_COST",
    "ZERO_WALL",
    "ZERO_CONTROL_RATE",
    "NUMERIC_RANGE",
    "TOO_FEW_CLUSTERS",
    "UNDEFINED_RESAMPLE",
    "DEGENERATE_INTERVAL",
]


@dataclass(frozen=True, slots=True)
class InvalidInput:
    reason: InputReason
    field: str = ""
    type: Literal["INVALID_INPUT"] = "INVALID_INPUT"


@dataclass(frozen=True, slots=True)
class Trial:
    task_id: str
    family: str
    arm: Arm
    repeat: int
    correct: bool
    semantic_misselections: int | None
    false_completeness: int | None
    wall_seconds: int | float
    cost_usd: int | float | None
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    tool_calls: int

    def __post_init__(self) -> None:
        # Invalid direct construction is a programming error. JSON admission
        # instead returns the finite InvalidInput variant without exceptions.
        admitted = _trial_fields(
            {
                "task_id": self.task_id,
                "family": self.family,
                "arm": self.arm,
                "repeat": self.repeat,
                "correct": self.correct,
                "semantic_misselections": self.semantic_misselections,
                "false_completeness": self.false_completeness,
                "wall_seconds": self.wall_seconds,
                "cost_usd": self.cost_usd,
                "input_tokens": self.input_tokens,
                "output_tokens": self.output_tokens,
                "cache_read_tokens": self.cache_read_tokens,
                "tool_calls": self.tool_calls,
            }
        )
        if isinstance(admitted, InvalidInput):
            raise ValueError(f"{admitted.reason}: {admitted.field}")


@dataclass(frozen=True, slots=True)
class ParsedTrial:
    trial: Trial
    type: Literal["PARSED"] = "PARSED"


class TrialFields(TypedDict):
    task_id: str
    family: str
    arm: Arm
    repeat: int
    correct: bool
    semantic_misselections: int | None
    false_completeness: int | None
    wall_seconds: int | float
    cost_usd: int | float | None
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    tool_calls: int


def _object_mapping(value: object) -> TypeGuard[Mapping[object, object]]:
    return type(value) is dict


def _identity(value: object) -> TypeGuard[str]:
    return type(value) is str and bool(value) and value == value.strip()


def _measurement(value: object) -> TypeGuard[int | float]:
    return (type(value) is int or type(value) is float) and value >= 0 and _finite(value)


def _quantity(value: object) -> TypeGuard[int]:
    return type(value) is int and value >= 0


def _finite(value: int | float) -> bool:
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _trial_fields(value: Mapping[object, object]) -> TrialFields | InvalidInput:
    task_id, family = value["task_id"], value["family"]
    if not _identity(task_id):
        return InvalidInput("INVALID_IDENTITY", "task_id")
    if not _identity(family):
        return InvalidInput("INVALID_IDENTITY", "family")
    arm = value["arm"]
    if type(arm) is not str:
        return InvalidInput("INVALID_ARM", "arm")
    if arm != "control" and arm != "treatment":
        return InvalidInput("INVALID_ARM", "arm")
    correct = value["correct"]
    if type(correct) is not bool:
        return InvalidInput("INVALID_BOOLEAN", "correct")
    repeat = value["repeat"]
    if not _quantity(repeat):
        return InvalidInput("INVALID_QUANTITY", "repeat")
    semantic = value["semantic_misselections"]
    if semantic is not None and not _quantity(semantic):
        return InvalidInput("INVALID_QUANTITY", "semantic_misselections")
    completeness = value["false_completeness"]
    if completeness is not None and not _quantity(completeness):
        return InvalidInput("INVALID_QUANTITY", "false_completeness")
    quantities: dict[str, int] = {}
    for name in ("input_tokens", "output_tokens", "cache_read_tokens", "tool_calls"):
        quantity = value[name]
        if not _quantity(quantity):
            return InvalidInput("INVALID_QUANTITY", name)
        quantities[name] = quantity
    wall, cost = value["wall_seconds"], value["cost_usd"]
    if not _measurement(wall):
        return InvalidInput("INVALID_MEASUREMENT", "wall_seconds")
    if cost is not None and not _measurement(cost):
        return InvalidInput("INVALID_MEASUREMENT", "cost_usd")
    if quantities["cache_read_tokens"] > quantities["input_tokens"]:
        return InvalidInput("CACHE_EXCEEDS_INPUT", "cache_read_tokens")
    # Do not coerce quantities or measurements: large integers and explicit
    # unknown cost and audit counts must survive admission unchanged.
    return TrialFields(
        task_id=task_id,
        family=family,
        arm=arm,
        correct=correct,
        repeat=repeat,
        semantic_misselections=semantic,
        false_completeness=completeness,
        wall_seconds=wall,
        cost_usd=cost,
        input_tokens=quantities["input_tokens"],
        output_tokens=quantities["output_tokens"],
        cache_read_tokens=quantities["cache_read_tokens"],
        tool_calls=quantities["tool_calls"],
    )


def parse_trial(value: object) -> ParsedTrial | InvalidInput:
    """Admit an exact JSON dictionary; nullable fields must still be present."""
    if not _object_mapping(value) or set(value) != {f.name for f in fields(Trial)}:
        return InvalidInput("INVALID_FIELDS")
    admitted = _trial_fields(value)
    if isinstance(admitted, InvalidInput):
        return admitted
    return ParsedTrial(Trial(**admitted))


@dataclass(frozen=True, slots=True)
class Available:
    value: float
    type: Literal["AVAILABLE"] = "AVAILABLE"


@dataclass(frozen=True, slots=True)
class Insufficient:
    reason: EvidenceReason
    type: Literal["INSUFFICIENT_EVIDENCE"] = "INSUFFICIENT_EVIDENCE"


Metric = Available | Insufficient


@dataclass(frozen=True, slots=True)
class Interval:
    lower: float
    upper: float
    type: Literal["AVAILABLE"] = "AVAILABLE"


@dataclass(frozen=True, slots=True)
class Totals:
    trials: int
    correct: int
    incorrect: int
    semantic_misselections: int | None
    false_completeness: int | None
    wall_seconds: float
    cost_usd: float | None
    known_cost_usd: float
    missing_cost_trials: int
    zero_cost_trials: int
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    total_tokens: int
    tool_calls: int


@dataclass(frozen=True, slots=True)
class Summary:
    totals: Totals
    accuracy: float
    median_wall_seconds: float
    p90_wall_seconds: float
    p95_wall_seconds: float
    correct_per_hour: Metric
    correct_per_dollar: Metric


@dataclass(frozen=True, slots=True)
class FamilyReport:
    control: Summary
    treatment: Summary
    accuracy_gate: bool


@dataclass(frozen=True, slots=True)
class RatioReport:
    ratio: Metric
    ci95: Interval | Insufficient


@dataclass(frozen=True, slots=True)
class Gates:
    accuracy_overall: bool
    accuracy_per_family: bool
    semantic_misselections: bool
    false_completeness: bool
    known_nonzero_cost: bool
    throughput_lower_ci_at_least_5: bool
    efficiency_lower_ci_at_least_5: bool
    accuracy_noninferiority_95: bool


@dataclass(frozen=True, slots=True)
class AccuracyEvidence:
    method: Literal["ONE_SIDED_EXACT_TASK_HARM_BOUND"]
    confidence: float
    margin: float
    task_clusters: int
    harmed_task_clusters: int
    upper_bound: float
    repeat_qualification: Literal["EQUAL_REPEATS", "UNEQUAL_REPEATS_UNSUPPORTED"]


@dataclass(frozen=True, slots=True)
class Comparison:
    type: Literal["ACCEPT", "REJECT", "INSUFFICIENT_EVIDENCE"]
    control: Summary
    treatment: Summary
    per_family: dict[str, FamilyReport]
    throughput: RatioReport
    efficiency: RatioReport
    gates: Gates
    bootstrap_seed: int
    bootstrap_samples: int
    task_clusters: int
    accuracy_evidence: AccuracyEvidence


class InvalidInputData(TypedDict):
    type: Literal["INVALID_INPUT"]
    reason: InputReason
    field: str


class AvailableData(TypedDict):
    type: Literal["AVAILABLE"]
    value: float


class InsufficientData(TypedDict):
    type: Literal["INSUFFICIENT_EVIDENCE"]
    reason: EvidenceReason


class IntervalData(TypedDict):
    type: Literal["AVAILABLE"]
    lower: float
    upper: float


class TotalsData(TypedDict):
    trials: int
    correct: int
    incorrect: int
    semantic_misselections: int | None
    false_completeness: int | None
    wall_seconds: float
    cost_usd: float | None
    known_cost_usd: float
    missing_cost_trials: int
    zero_cost_trials: int
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    total_tokens: int
    tool_calls: int


class SummaryData(TypedDict):
    totals: TotalsData
    accuracy: float
    median_wall_seconds: float
    p90_wall_seconds: float
    p95_wall_seconds: float
    correct_per_hour: AvailableData | InsufficientData
    correct_per_dollar: AvailableData | InsufficientData


class FamilyData(TypedDict):
    control: SummaryData
    treatment: SummaryData
    accuracy_gate: bool


class RatioData(TypedDict):
    ratio: AvailableData | InsufficientData
    ci95: IntervalData | InsufficientData


class GatesData(TypedDict):
    accuracy_overall: bool
    accuracy_per_family: bool
    semantic_misselections: bool
    false_completeness: bool
    known_nonzero_cost: bool
    throughput_lower_ci_at_least_5: bool
    efficiency_lower_ci_at_least_5: bool
    accuracy_noninferiority_95: bool


class AccuracyEvidenceData(TypedDict):
    method: Literal["ONE_SIDED_EXACT_TASK_HARM_BOUND"]
    confidence: float
    margin: float
    task_clusters: int
    harmed_task_clusters: int
    upper_bound: float
    repeat_qualification: Literal["EQUAL_REPEATS", "UNEQUAL_REPEATS_UNSUPPORTED"]


class ComparisonData(TypedDict):
    type: Literal["ACCEPT", "REJECT", "INSUFFICIENT_EVIDENCE"]
    control: SummaryData
    treatment: SummaryData
    per_family: dict[str, FamilyData]
    throughput: RatioData
    efficiency: RatioData
    gates: GatesData
    bootstrap_seed: int
    bootstrap_samples: int
    task_clusters: int
    accuracy_evidence: AccuracyEvidenceData


def _invalid_data(value: InvalidInput) -> InvalidInputData:
    return {"type": value.type, "reason": value.reason, "field": value.field}


def _metric_data(value: Metric) -> AvailableData | InsufficientData:
    if isinstance(value, Available):
        return {"type": value.type, "value": value.value}
    return {"type": value.type, "reason": value.reason}


def _summary_data(value: Summary) -> SummaryData:
    t = value.totals
    return SummaryData(
        totals=TotalsData(
            trials=t.trials,
            correct=t.correct,
            incorrect=t.incorrect,
            semantic_misselections=t.semantic_misselections,
            false_completeness=t.false_completeness,
            wall_seconds=t.wall_seconds,
            cost_usd=t.cost_usd,
            known_cost_usd=t.known_cost_usd,
            missing_cost_trials=t.missing_cost_trials,
            zero_cost_trials=t.zero_cost_trials,
            input_tokens=t.input_tokens,
            output_tokens=t.output_tokens,
            cache_read_tokens=t.cache_read_tokens,
            total_tokens=t.total_tokens,
            tool_calls=t.tool_calls,
        ),
        accuracy=value.accuracy,
        median_wall_seconds=value.median_wall_seconds,
        p90_wall_seconds=value.p90_wall_seconds,
        p95_wall_seconds=value.p95_wall_seconds,
        correct_per_hour=_metric_data(value.correct_per_hour),
        correct_per_dollar=_metric_data(value.correct_per_dollar),
    )


def _ratio_data(value: RatioReport) -> RatioData:
    ci = value.ci95
    return RatioData(
        ratio=_metric_data(value.ratio),
        ci95=IntervalData(type=ci.type, lower=ci.lower, upper=ci.upper)
        if isinstance(ci, Interval)
        else InsufficientData(type=ci.type, reason=ci.reason),
    )


def _comparison_data(value: Comparison) -> ComparisonData:
    g = value.gates
    return ComparisonData(
        type=value.type,
        control=_summary_data(value.control),
        treatment=_summary_data(value.treatment),
        per_family={
            name: FamilyData(
                control=_summary_data(f.control),
                treatment=_summary_data(f.treatment),
                accuracy_gate=f.accuracy_gate,
            )
            for name, f in value.per_family.items()
        },
        throughput=_ratio_data(value.throughput),
        efficiency=_ratio_data(value.efficiency),
        gates=GatesData(
            accuracy_overall=g.accuracy_overall,
            accuracy_per_family=g.accuracy_per_family,
            semantic_misselections=g.semantic_misselections,
            false_completeness=g.false_completeness,
            known_nonzero_cost=g.known_nonzero_cost,
            throughput_lower_ci_at_least_5=g.throughput_lower_ci_at_least_5,
            efficiency_lower_ci_at_least_5=g.efficiency_lower_ci_at_least_5,
            accuracy_noninferiority_95=g.accuracy_noninferiority_95,
        ),
        bootstrap_seed=value.bootstrap_seed,
        bootstrap_samples=value.bootstrap_samples,
        task_clusters=value.task_clusters,
        accuracy_evidence=AccuracyEvidenceData(
            method=value.accuracy_evidence.method,
            confidence=value.accuracy_evidence.confidence,
            margin=value.accuracy_evidence.margin,
            task_clusters=value.accuracy_evidence.task_clusters,
            harmed_task_clusters=value.accuracy_evidence.harmed_task_clusters,
            upper_bound=value.accuracy_evidence.upper_bound,
            repeat_qualification=value.accuracy_evidence.repeat_qualification,
        ),
    )


def _object_tuple(value: object) -> TypeGuard[tuple[object, ...]]:
    return type(value) is tuple


def _task_ids(value: object) -> TypeGuard[tuple[str, ...]]:
    return (
        _object_tuple(value)
        and bool(value)
        and all(_identity(task) for task in value)
        and len(set(value)) == len(value)
    )


def _quantile(ordered: list[float], p: float) -> float:
    position = (len(ordered) - 1) * p
    lower = math.floor(position)
    upper = math.ceil(position)
    weight = position - lower
    # Convex combination avoids overflow in (upper - lower).
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def _divide(
    numerator: int | float, denominator: int | float, zero: EvidenceReason, scale: int = 1
) -> Metric:
    if denominator == 0:
        return Insufficient(zero)
    # Avoid intermediate overflow and retain an exact inclusive 5x threshold.
    try:
        result = float(Fraction(numerator) * scale / Fraction(denominator))
    except OverflowError:
        return Insufficient("NUMERIC_RANGE")
    if not math.isfinite(result) or (result == 0 and numerator != 0):
        return Insufficient("NUMERIC_RANGE")
    return Available(result)


def _audit_total(counts: Iterable[int | None]) -> int | None:
    total = 0
    for count in counts:
        if count is None:
            return None
        total += count
    return total


def _summary(trials: tuple[Trial, ...]) -> Summary:
    correct = sum(t.correct for t in trials)
    wall = math.fsum(t.wall_seconds for t in trials)
    known_cost = math.fsum(t.cost_usd for t in trials if t.cost_usd is not None)
    missing = sum(t.cost_usd is None for t in trials)
    zero = sum(t.cost_usd == 0 for t in trials)
    cost = None if missing else known_cost
    input_tokens = sum(t.input_tokens for t in trials)
    output_tokens = sum(t.output_tokens for t in trials)
    totals = Totals(
        len(trials),
        correct,
        len(trials) - correct,
        _audit_total(t.semantic_misselections for t in trials),
        _audit_total(t.false_completeness for t in trials),
        wall,
        cost,
        known_cost,
        missing,
        zero,
        input_tokens,
        output_tokens,
        sum(t.cache_read_tokens for t in trials),
        input_tokens + output_tokens,
        sum(t.tool_calls for t in trials),
    )
    ordered = sorted(t.wall_seconds for t in trials)
    dollars = (
        Insufficient("MISSING_COST")
        if missing
        else Insufficient("ZERO_COST")
        if zero
        else _divide(correct, known_cost, "ZERO_COST")
    )
    return Summary(
        totals,
        correct / len(trials),
        _quantile(ordered, 0.5),
        _quantile(ordered, 0.9),
        _quantile(ordered, 0.95),
        _divide(correct, wall, "ZERO_WALL", 3600),
        dollars,
    )


def _ratio(
    control_correct: int, treatment_correct: int, control_exposure: float, treatment_exposure: float
) -> Metric:
    if control_correct == 0:
        return Insufficient("ZERO_CONTROL_RATE")
    # Compute the ratio of rates, not the mean of per-task speedups.
    try:
        result = float(
            Fraction(treatment_correct)
            * Fraction(control_exposure)
            / (Fraction(control_correct) * Fraction(treatment_exposure))
        )
    except OverflowError:
        return Insufficient("NUMERIC_RANGE")
    if not math.isfinite(result) or (result == 0 and treatment_correct != 0):
        return Insufficient("NUMERIC_RANGE")
    return Available(result)


def _ratios(control: Summary, treatment: Summary) -> tuple[Metric, Metric]:
    c, t = control.totals, treatment.totals
    throughput = (
        Insufficient("ZERO_WALL")
        if c.wall_seconds == 0 or t.wall_seconds == 0
        else _ratio(c.correct, t.correct, c.wall_seconds, t.wall_seconds)
    )
    efficiency = (
        Insufficient("MISSING_COST")
        if c.missing_cost_trials or t.missing_cost_trials
        else Insufficient("ZERO_COST")
        if c.zero_cost_trials or t.zero_cost_trials
        else _ratio(c.correct, t.correct, c.known_cost_usd, t.known_cost_usd)
    )
    return throughput, efficiency


def _interval(values: list[float], samples: int, clusters: int) -> Interval | Insufficient:
    if clusters < 2:
        return Insufficient("TOO_FEW_CLUSTERS")
    if len(values) != samples:
        return Insufficient("UNDEFINED_RESAMPLE")
    values.sort()
    lower, upper = _quantile(values, 0.025), _quantile(values, 0.975)
    if lower == upper:
        return Insufficient("DEGENERATE_INTERVAL")
    return Interval(lower, upper)


def _harm_upper_bound(harmed: int, tasks: int) -> float:
    # Invert P[Binomial(tasks, p) <= harmed] = .05. The zero-harm
    # expression and all-harm endpoint avoid numerical cancellation.
    if harmed == 0:
        return -math.expm1(math.log(0.05) / tasks)
    if harmed == tasks:
        return 1.0
    lower, upper = harmed / tasks, 1.0
    coefficients = tuple(
        math.lgamma(tasks + 1) - math.lgamma(k + 1) - math.lgamma(tasks - k + 1)
        for k in range(harmed + 1)
    )
    for _ in range(80):
        probability = (lower + upper) / 2
        if probability == 1:
            break
        terms = tuple(
            coefficient + k * math.log(probability) + (tasks - k) * math.log1p(-probability)
            for k, coefficient in enumerate(coefficients)
        )
        largest = max(terms)
        cdf = math.exp(largest) * math.fsum(math.exp(term - largest) for term in terms)
        if cdf > 0.05:
            lower = probability
        else:
            upper = probability
    return upper


def _accuracy_evidence(clusters: list[tuple[Trial, ...]]) -> AccuracyEvidence:
    # A task's paired accuracy loss is at most 1 when its treatment has fewer
    # successes, and at most 0 otherwise. Its harm probability therefore
    # conservatively bounds the control-minus-treatment accuracy difference.
    # Repeats stay inside each independent task cluster, never as new samples.
    harmed = sum(
        sum(t.correct for t in cluster if t.arm == "control")
        > sum(t.correct for t in cluster if t.arm == "treatment")
        for cluster in clusters
    )
    return AccuracyEvidence(
        "ONE_SIDED_EXACT_TASK_HARM_BOUND",
        0.95,
        0.02,
        len(clusters),
        harmed,
        _harm_upper_bound(harmed, len(clusters)),
        "EQUAL_REPEATS"
        if len({len(cluster) for cluster in clusters}) == 1
        else "UNEQUAL_REPEATS_UNSUPPORTED",
    )


def compare(
    trials: Iterable[object],
    *,
    expected_task_ids: object,
    bootstrap_seed: object = 917,
    bootstrap_samples: object = 2000,
) -> ComparisonData | InvalidInputData | InsufficientData:
    """Return a JSON-ready finite outcome; never silently drop failed attempts.

    Expected task IDs are a nonempty exact task universe. Repeats need not be
    contiguous or equal across tasks, but each (task, repeat) needs both arms.
    Invalid input returns INVALID_INPUT; unavailable metrics carry finite reasons.
    Any unknown audit count, unavailable point ratio, or unavailable interval
    makes the decision insufficient, even when a separate quality gate fails.
    """
    if not _task_ids(expected_task_ids):
        return _invalid_data(InvalidInput("INVALID_EXPECTED_TASKS"))
    if (
        type(bootstrap_seed) is not int
        or type(bootstrap_samples) is not int
        or bootstrap_samples < 2
    ):
        return _invalid_data(InvalidInput("INVALID_BOOTSTRAP"))
    admitted_trials: list[Trial] = []
    seen: set[tuple[str, int, Arm]] = set()
    families: dict[str, str] = {}
    expected = set(expected_task_ids)
    for trial in trials:
        if not isinstance(trial, Trial) or type(trial) is not Trial:
            return _invalid_data(InvalidInput("INVALID_TRIAL"))
        admitted_trials.append(trial)
        if trial.task_id not in expected:
            return _invalid_data(InvalidInput("UNEXPECTED_TASK", trial.task_id))
        key = (trial.task_id, trial.repeat, trial.arm)
        if key in seen:
            return _invalid_data(InvalidInput("DUPLICATE_TRIAL", trial.task_id))
        seen.add(key)
        if trial.task_id in families and families[trial.task_id] != trial.family:
            return _invalid_data(InvalidInput("FAMILY_MISMATCH", trial.task_id))
        families[trial.task_id] = trial.family
    if set(families) != expected:
        return _invalid_data(InvalidInput("MISSING_TASK"))
    if any(
        (task, repeat, "treatment" if arm == "control" else "control") not in seen
        for task, repeat, arm in seen
    ):
        return _invalid_data(InvalidInput("MISSING_PAIR"))
    # Canonical ordering makes output independent of input iteration order.
    admitted = tuple(sorted(admitted_trials, key=lambda t: (t.task_id, t.repeat, t.arm)))
    try:
        return _comparison_data(_compare(admitted, bootstrap_seed, bootstrap_samples))
    except OverflowError:
        # Finite individual measurements can still overflow their aggregate.
        return InsufficientData(type="INSUFFICIENT_EVIDENCE", reason="NUMERIC_RANGE")


def _compare(trials: tuple[Trial, ...], seed: int, samples: int) -> Comparison:
    control = _summary(tuple(t for t in trials if t.arm == "control"))
    treatment = _summary(tuple(t for t in trials if t.arm == "treatment"))
    per_family: dict[str, FamilyReport] = {}
    for family in sorted({t.family for t in trials}):
        c = _summary(tuple(t for t in trials if t.family == family and t.arm == "control"))
        t = _summary(tuple(t for t in trials if t.family == family and t.arm == "treatment"))
        per_family[family] = FamilyReport(c, t, t.totals.correct >= c.totals.correct)
    clusters = [
        tuple(t for t in trials if t.task_id == task)
        for task in sorted({t.task_id for t in trials})
    ]
    accuracy_evidence = _accuracy_evidence(clusters)
    throughput, efficiency = _ratios(control, treatment)
    through_values: list[float] = []
    efficiency_values: list[float] = []
    rng = random.Random(seed)
    for _ in range(samples):
        selected = tuple(t for _ in clusters for t in clusters[rng.randrange(len(clusters))])
        c = _summary(tuple(t for t in selected if t.arm == "control"))
        t = _summary(tuple(t for t in selected if t.arm == "treatment"))
        through, efficient = _ratios(c, t)
        if isinstance(through, Available):
            through_values.append(through.value)
        if isinstance(efficient, Available):
            efficiency_values.append(efficient.value)
    through_ci = (
        _interval(through_values, samples, len(clusters))
        if isinstance(throughput, Available)
        else throughput
    )
    efficiency_ci = (
        _interval(efficiency_values, samples, len(clusters))
        if isinstance(efficiency, Available)
        else efficiency
    )
    c_totals, t_totals = control.totals, treatment.totals
    audits_known = all(
        count is not None
        for count in (
            c_totals.semantic_misselections,
            t_totals.semantic_misselections,
            c_totals.false_completeness,
            t_totals.false_completeness,
        )
    )
    gates = Gates(
        treatment.totals.correct >= control.totals.correct,
        all(f.accuracy_gate for f in per_family.values()),
        c_totals.semantic_misselections is not None
        and t_totals.semantic_misselections is not None
        and t_totals.semantic_misselections <= c_totals.semantic_misselections,
        c_totals.false_completeness is not None
        and t_totals.false_completeness is not None
        and t_totals.false_completeness <= c_totals.false_completeness,
        control.totals.missing_cost_trials == treatment.totals.missing_cost_trials == 0
        and control.totals.zero_cost_trials == treatment.totals.zero_cost_trials == 0,
        isinstance(through_ci, Interval) and through_ci.lower >= 5,
        isinstance(efficiency_ci, Interval) and efficiency_ci.lower >= 5,
        accuracy_evidence.repeat_qualification == "EQUAL_REPEATS"
        and accuracy_evidence.upper_bound <= accuracy_evidence.margin,
    )
    decision: Literal["ACCEPT", "REJECT", "INSUFFICIENT_EVIDENCE"] = (
        "INSUFFICIENT_EVIDENCE"
        if not audits_known
        or isinstance(through_ci, Insufficient)
        or isinstance(efficiency_ci, Insufficient)
        else "ACCEPT"
        if all(
            (
                gates.accuracy_overall,
                gates.accuracy_per_family,
                gates.semantic_misselections,
                gates.false_completeness,
                gates.known_nonzero_cost,
                gates.throughput_lower_ci_at_least_5,
                gates.efficiency_lower_ci_at_least_5,
                gates.accuracy_noninferiority_95,
            )
        )
        else "REJECT"
    )
    return Comparison(
        decision,
        control,
        treatment,
        per_family,
        RatioReport(throughput, through_ci),
        RatioReport(efficiency, efficiency_ci),
        gates,
        seed,
        samples,
        len(clusters),
        accuracy_evidence,
    )
