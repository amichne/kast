"""Pure journal timing accounting; file access is confined to read_timing.

Client spans include transport and observation overhead, not pure decode time.
Output counters include reasoning. No reference-rate normalization is performed.
"""

from __future__ import annotations

import math
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    JsonValue,
    StrictInt,
    TypeAdapter,
    ValidationError,
    model_validator,
)

Nonnegative = Annotated[float, Field(ge=0, allow_inf_nan=False, strict=True)]
Count = Annotated[StrictInt, Field(ge=0)]


class TimingRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)


class TimingFailure(StrEnum):
    NO_JOURNAL = "NO_JOURNAL"
    JOURNAL_UNREADABLE = "JOURNAL_UNREADABLE"
    MALFORMED_JOURNAL = "MALFORMED_JOURNAL"
    INVALID_SESSION = "INVALID_SESSION"
    MALFORMED_EVENT = "MALFORMED_EVENT"
    INVALID_IDENTITY = "INVALID_IDENTITY"
    UNMATCHED_REQUEST = "UNMATCHED_REQUEST"
    UNMATCHED_RESPONSE = "UNMATCHED_RESPONSE"
    INVALID_INTERVAL = "INVALID_INTERVAL"
    NON_FINALIZED_USAGE = "NON_FINALIZED_USAGE"
    USAGE_TOTAL_MISMATCH = "USAGE_TOTAL_MISMATCH"
    REQUEST_TOTAL_MISMATCH = "REQUEST_TOTAL_MISMATCH"
    OUTPUT_TOTAL_MISMATCH = "OUTPUT_TOTAL_MISMATCH"
    MISSING_PROVIDER_METADATA = "MISSING_PROVIDER_METADATA"
    INVALID_PROVIDER_METADATA = "INVALID_PROVIDER_METADATA"
    ZERO_DURATION = "ZERO_DURATION"
    INCOMPLETE_TASKS = "INCOMPLETE_TASKS"


class Available(TimingRecord):
    kind: Literal["available"] = "available"
    value: Nonnegative


class Unavailable(TimingRecord):
    kind: Literal["unavailable"] = "unavailable"
    reason: TimingFailure


type Measurement = Annotated[Available | Unavailable, Field(discriminator="kind")]


class JournalRecord(BaseModel):
    # Other journal facts are not owned by timing.
    model_config = ConfigDict(extra="ignore", frozen=True, allow_inf_nan=False)


class ProviderRequest(JournalRecord):
    type: Literal["provider_request"] = "provider_request"
    request: Annotated[StrictInt, Field(gt=0)]
    elapsedMillis: Nonnegative


class Usage(JournalRecord):
    type: Literal["usage"] = "usage"
    input: Count
    output: Count
    cacheRead: Count
    cacheWrite: Count
    totalTokens: Count
    catalogCostUsd: Nonnegative | None = None


class ProviderTiming(JournalRecord):
    sdkDurationMillis: Nonnegative | None = None
    headersReceivedMillis: Nonnegative | None = None
    firstObservableDeltaMillis: Nonnegative | None = None
    serverCreatedAtSeconds: Nonnegative | None = None
    serverCompletedAtSeconds: Nonnegative | None = None
    providerProcessingMillis: Nonnegative | None = None


class AssistantEnd(JournalRecord):
    type: Literal["assistant_end"] = "assistant_end"
    elapsedMillis: Nonnegative
    stopReason: str
    usage: JsonValue


class ToolEnd(JournalRecord):
    type: Literal["tool_end"] = "tool_end"
    toolCallId: Annotated[str, Field(min_length=1, strict=True)]
    elapsedMillis: Nonnegative
    durationMillis: Nonnegative


class Interval(TimingRecord):
    start: Nonnegative
    end: Nonnegative

    @model_validator(mode="after")
    def ordered(self) -> Interval:
        if self.end < self.start:
            raise ValueError("REVERSED_TIMING_INTERVAL")
        return self


class ResponseTiming(TimingRecord):
    request: int
    span: Interval
    output_tokens: Count
    client_response_seconds: Nonnegative
    output_tokens_per_client_second: Measurement
    first_observable_delta_seconds: Measurement
    headers_received_seconds: Measurement
    sdk_duration_seconds: Measurement
    provider_timestamp_seconds: Measurement
    output_tokens_per_provider_timestamp_second: Measurement
    provider_processing_millis: Measurement


class TaskTiming(TimingRecord):
    qualification: Literal["complete", "qualified_prefix", "unavailable"]
    issues: tuple[TimingFailure, ...]
    responses: tuple[ResponseTiming, ...]
    provider_client_seconds: Measurement
    non_provider_wall_seconds: Measurement
    output_tokens_per_client_second: Measurement
    tool_active_seconds: Measurement

    @model_validator(mode="after")
    def qualified_evidence(self) -> TaskTiming:
        match self.qualification:
            case "complete":
                if (
                    self.issues
                    or not isinstance(self.provider_client_seconds, Available)
                    or not isinstance(self.non_provider_wall_seconds, Available)
                ):
                    raise ValueError("INCOMPLETE_PROVIDER_TIMING_EVIDENCE")
            case "qualified_prefix":
                if not self.issues or not self.responses:
                    raise ValueError("MISSING_QUALIFIED_TIMING_PREFIX")
            case "unavailable":
                if not self.issues or self.responses:
                    raise ValueError("INVALID_UNAVAILABLE_TIMING")
        return self


class TimingAggregate(TimingRecord):
    tasks: int
    complete_tasks: int
    unavailable_tasks: int
    known_responses: int
    known_output_tokens: int
    known_provider_client_seconds: Nonnegative
    known_output_tokens_per_client_second: Measurement
    provider_client_seconds: Measurement
    non_provider_wall_seconds: Measurement
    output_tokens_per_client_second: Measurement
    tool_active_seconds: Measurement
    provider_timestamp_seconds: Measurement
    output_tokens_per_provider_timestamp_second: Measurement


def unavailable(reason: TimingFailure) -> Unavailable:
    return Unavailable(reason=reason)


def no_journal() -> TaskTiming:
    missing = unavailable(TimingFailure.NO_JOURNAL)
    return TaskTiming(
        qualification="unavailable",
        issues=(missing.reason,),
        responses=(),
        provider_client_seconds=missing,
        non_provider_wall_seconds=missing,
        output_tokens_per_client_second=missing,
        tool_active_seconds=missing,
    )


def rate(tokens: int, seconds: float) -> Measurement:
    if seconds == 0:
        return unavailable(TimingFailure.ZERO_DURATION)
    value = tokens / seconds
    return (
        Available(value=value)
        if math.isfinite(value)
        else unavailable(TimingFailure.INVALID_INTERVAL)
    )


def union_seconds(intervals: tuple[Interval, ...]) -> float:
    """Intervals have already been admitted at the session boundary."""
    end = 0.0
    total = 0.0
    for interval in sorted(intervals, key=lambda span: span.start):
        total += max(0.0, interval.end - max(end, interval.start))
        end = max(end, interval.end)
    return total


def response_timing(
    request: ProviderRequest, end: AssistantEnd, usage: Usage, metadata_value: JsonValue
) -> ResponseTiming:
    start_seconds, end_seconds = request.elapsedMillis / 1000, end.elapsedMillis / 1000
    seconds = end_seconds - start_seconds
    missing: Measurement = unavailable(TimingFailure.MISSING_PROVIDER_METADATA)
    sdk = headers = delta = server = processing = missing
    try:
        metadata = ProviderTiming.model_validate({} if metadata_value is None else metadata_value)
    except ValidationError:
        sdk = headers = delta = server = processing = unavailable(
            TimingFailure.INVALID_PROVIDER_METADATA
        )
    else:
        if metadata.sdkDurationMillis is not None:
            sdk = Available(value=metadata.sdkDurationMillis / 1000)
        if metadata.providerProcessingMillis is not None:
            processing = Available(value=metadata.providerProcessingMillis)

        def latency(value: float | None) -> Measurement:
            if value is None:
                return missing
            if not request.elapsedMillis <= value <= end.elapsedMillis:
                return unavailable(TimingFailure.INVALID_PROVIDER_METADATA)
            return Available(value=(value - request.elapsedMillis) / 1000)

        headers = latency(metadata.headersReceivedMillis)
        delta = latency(metadata.firstObservableDeltaMillis)
        created, completed = metadata.serverCreatedAtSeconds, metadata.serverCompletedAtSeconds
        if created is not None and completed is not None:
            server = (
                Available(value=completed - created)
                if completed >= created
                else unavailable(TimingFailure.INVALID_PROVIDER_METADATA)
            )
    return ResponseTiming(
        request=request.request,
        span=Interval(start=start_seconds, end=end_seconds),
        output_tokens=usage.output,
        client_response_seconds=seconds,
        output_tokens_per_client_second=rate(usage.output, seconds),
        first_observable_delta_seconds=delta,
        headers_received_seconds=headers,
        sdk_duration_seconds=sdk,
        provider_timestamp_seconds=server,
        output_tokens_per_provider_timestamp_second=(
            rate(usage.output, server.value) if isinstance(server, Available) else server
        ),
        provider_processing_millis=processing,
    )


def tool_time(events: tuple[JsonValue, ...], wall: float) -> Measurement:
    spans: list[Interval] = []
    identities: set[str] = set()
    for event in events:
        if not isinstance(event, dict) or event.get("type") != "tool_end":
            continue
        try:
            tool = ToolEnd.model_validate(event)
        except ValidationError:
            return unavailable(TimingFailure.MALFORMED_EVENT)
        if not tool.toolCallId.strip() or tool.toolCallId in identities:
            return unavailable(TimingFailure.INVALID_IDENTITY)
        identities.add(tool.toolCallId)
        start = (tool.elapsedMillis - tool.durationMillis) / 1000
        end = tool.elapsedMillis / 1000
        if not 0 <= start <= end <= wall:
            return unavailable(TimingFailure.INVALID_INTERVAL)
        spans.append(Interval(start=start, end=end))
    return Available(value=union_seconds(tuple(spans)))


def analyze_timing(
    events: tuple[JsonValue, ...],
    *,
    wall_seconds: float,
    requests: int,
    output_tokens: int,
    journal_failure: TimingFailure | None = None,
) -> TaskTiming:
    """Pair serial request/terminal events, preserving only proven prefix spans on failure."""
    if (
        not math.isfinite(wall_seconds)
        or wall_seconds < 0
        or isinstance(wall_seconds, bool)
        or type(requests) is not int
        or requests < 0
        or type(output_tokens) is not int
        or output_tokens < 0
    ):
        failed = unavailable(TimingFailure.INVALID_SESSION)
        return TaskTiming(
            qualification="unavailable",
            issues=(failed.reason,),
            responses=(),
            provider_client_seconds=failed,
            non_provider_wall_seconds=failed,
            output_tokens_per_client_second=failed,
            tool_active_seconds=failed,
        )
    responses: list[ResponseTiming] = []
    pending: ProviderRequest | None = None
    seen = 0
    previous = 0.0
    failure: TimingFailure | None = None
    for event in events:
        if not isinstance(event, dict) or not isinstance(event.get("type"), str):
            failure = TimingFailure.MALFORMED_EVENT
            break
        kind = event["type"]
        if kind not in ("provider_request", "assistant_end"):
            continue
        try:
            observed = (
                ProviderRequest.model_validate(event)
                if kind == "provider_request"
                else AssistantEnd.model_validate(event)
            )
        except ValidationError:
            failure = TimingFailure.MALFORMED_EVENT
            break
        elapsed = observed.elapsedMillis / 1000
        if not previous <= elapsed <= wall_seconds:
            failure = TimingFailure.INVALID_INTERVAL
            break
        previous = elapsed
        if isinstance(observed, ProviderRequest):
            if observed.request != seen + 1 or observed.request > requests:
                failure = TimingFailure.INVALID_IDENTITY
                break
            if pending is not None:
                failure = TimingFailure.UNMATCHED_REQUEST
                break
            pending = observed
            seen += 1
        else:
            if pending is None:
                failure = TimingFailure.UNMATCHED_RESPONSE
                break
            if observed.stopReason not in ("stop", "length", "toolUse"):
                failure = TimingFailure.NON_FINALIZED_USAGE
                break
            try:
                usage = Usage.model_validate(observed.usage)
            except ValidationError:
                failure = TimingFailure.NON_FINALIZED_USAGE
                break
            if usage.totalTokens != usage.input + usage.output + usage.cacheRead + usage.cacheWrite:
                failure = TimingFailure.USAGE_TOTAL_MISMATCH
                break
            responses.append(response_timing(pending, observed, usage, event.get("providerTiming")))
            pending = None
    issues: list[TimingFailure] = []
    if failure is not None:
        issues.append(failure)
    if pending is not None:
        issues.append(TimingFailure.UNMATCHED_REQUEST)
    if seen != requests or len(responses) != requests:
        issues.append(TimingFailure.REQUEST_TOTAL_MISMATCH)
    if sum(response.output_tokens for response in responses) != output_tokens:
        issues.append(TimingFailure.OUTPUT_TOTAL_MISMATCH)
    if journal_failure is not None:
        issues.append(journal_failure)
    tools = tool_time(events, wall_seconds)
    if journal_failure is not None:
        tools = unavailable(journal_failure)
    if issues:
        failed = unavailable(issues[0])
        return TaskTiming(
            qualification="qualified_prefix" if responses else "unavailable",
            issues=tuple(dict.fromkeys(issues)),
            responses=tuple(responses),
            provider_client_seconds=failed,
            non_provider_wall_seconds=failed,
            output_tokens_per_client_second=failed,
            tool_active_seconds=tools,
        )
    seconds = union_seconds(tuple(response.span for response in responses))
    return TaskTiming(
        qualification="complete",
        issues=(),
        responses=tuple(responses),
        provider_client_seconds=Available(value=seconds),
        non_provider_wall_seconds=Available(value=max(0, wall_seconds - seconds)),
        output_tokens_per_client_second=rate(output_tokens, seconds),
        tool_active_seconds=tools,
    )


def aggregate_timing(tasks: tuple[TaskTiming, ...]) -> TimingAggregate:
    responses = tuple(response for task in tasks for response in task.responses)
    seconds = math.fsum(response.client_response_seconds for response in responses)
    tokens = sum(response.output_tokens for response in responses)
    complete = sum(task.qualification == "complete" for task in tasks)

    def total(values: tuple[Measurement, ...]) -> Measurement:
        if any(isinstance(value, Unavailable) for value in values):
            return unavailable(TimingFailure.INCOMPLETE_TASKS)
        return Available(value=math.fsum(v.value for v in values if isinstance(v, Available)))

    server_values = tuple(response.provider_timestamp_seconds for response in responses)
    server_seconds = next(
        (value for value in server_values if isinstance(value, Unavailable)),
        Available(
            value=math.fsum(value.value for value in server_values if isinstance(value, Available))
        ),
    )
    if not responses:
        server_seconds = unavailable(TimingFailure.MISSING_PROVIDER_METADATA)
    if complete != len(tasks):
        server_seconds = unavailable(TimingFailure.INCOMPLETE_TASKS)
    server_rate = (
        rate(tokens, server_seconds.value)
        if isinstance(server_seconds, Available)
        else server_seconds
    )
    return TimingAggregate(
        tasks=len(tasks),
        complete_tasks=complete,
        unavailable_tasks=len(tasks) - complete,
        known_responses=len(responses),
        known_output_tokens=tokens,
        known_provider_client_seconds=seconds,
        known_output_tokens_per_client_second=rate(tokens, seconds),
        provider_client_seconds=total(tuple(t.provider_client_seconds for t in tasks)),
        non_provider_wall_seconds=total(tuple(t.non_provider_wall_seconds for t in tasks)),
        output_tokens_per_client_second=(
            rate(tokens, seconds)
            if complete == len(tasks)
            else unavailable(TimingFailure.INCOMPLETE_TASKS)
        ),
        tool_active_seconds=total(tuple(t.tool_active_seconds for t in tasks)),
        provider_timestamp_seconds=server_seconds,
        output_tokens_per_provider_timestamp_second=server_rate,
    )


def read_timing(
    path: Path, *, wall_seconds: float, requests: int, output_tokens: int
) -> TaskTiming:
    """Journal adapter only; the token report remains the sole manifest reader."""
    try:
        contents = path.read_bytes()
    except FileNotFoundError:
        return no_journal()
    except OSError:
        missing = no_journal()
        failed = unavailable(TimingFailure.JOURNAL_UNREADABLE)
        return missing.model_copy(
            update={
                "issues": (failed.reason,),
                "provider_client_seconds": failed,
                "non_provider_wall_seconds": failed,
                "output_tokens_per_client_second": failed,
                "tool_active_seconds": failed,
            }
        )
    events: list[JsonValue] = []
    failure: TimingFailure | None = None
    for line in contents.splitlines():
        try:
            events.append(TypeAdapter(JsonValue).validate_json(line))
        except ValidationError:
            failure = TimingFailure.MALFORMED_JOURNAL
            break
    return analyze_timing(
        tuple(events),
        wall_seconds=wall_seconds,
        requests=requests,
        output_tokens=output_tokens,
        journal_failure=failure,
    )
