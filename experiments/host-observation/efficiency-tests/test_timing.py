"""Pure timing fixtures: independent token/time arithmetic, no inference or clocks."""

from pathlib import Path

import pytest
from pydantic import JsonValue, ValidationError

from agent_efficiency_timing import (
    AssistantEnd,
    Available,
    Interval,
    ProviderRequest,
    ProviderTiming,
    TaskTiming,
    TimingFailure,
    ToolEnd,
    Unavailable,
    Usage,
    aggregate_timing,
    analyze_timing,
    no_journal,
    read_timing,
)


class ResponseFixture(AssistantEnd):
    providerTiming: ProviderTiming | None = None


def request(number: int, millis: float) -> JsonValue:
    return ProviderRequest(request=number, elapsedMillis=millis).model_dump(mode="json")


def response(tokens: int, millis: float, metadata: ProviderTiming | None = None) -> JsonValue:
    usage = Usage(input=2, output=tokens, cacheRead=3, cacheWrite=4, totalTokens=tokens + 9)
    return ResponseFixture(
        elapsedMillis=millis,
        stopReason="stop",
        usage=usage.model_dump(mode="json"),
        providerTiming=metadata,
    ).model_dump(mode="json")


def two_responses() -> tuple[JsonValue, ...]:
    return (request(1, 500), response(10, 1500), request(2, 2000), response(90, 5000))


def measure(events: tuple[JsonValue, ...]) -> TaskTiming:
    return analyze_timing(events, wall_seconds=6, requests=2, output_tokens=100)


def test_client_rate_is_output_weighted_not_mean_and_wall_is_partitioned() -> None:
    measured = measure(two_responses())
    assert measured.qualification == "complete"
    assert measured.issues == ()
    assert measured.provider_client_seconds == Available(value=4)
    assert measured.non_provider_wall_seconds == Available(value=2)
    assert measured.output_tokens_per_client_second == Available(value=25)
    first, second = measured.responses
    assert (first.request, first.output_tokens, first.client_response_seconds) == (1, 10, 1)
    assert (second.request, second.output_tokens, second.client_response_seconds) == (2, 90, 3)
    assert first.output_tokens_per_client_second == Available(value=10)
    assert second.output_tokens_per_client_second == Available(value=30)
    assert first.model_dump(mode="json")["output_tokens_per_client_second"] == {
        "kind": "available",
        "value": 10.0,
    }


def test_nested_and_parallel_tools_use_union_not_duration_sum() -> None:
    tools = (
        ToolEnd(toolCallId="outer", elapsedMillis=1000, durationMillis=1000),
        ToolEnd(toolCallId="inner", elapsedMillis=800, durationMillis=600),
        ToolEnd(toolCallId="parallel", elapsedMillis=1500, durationMillis=1000),
    )
    events = tuple(tool.model_dump(mode="json") for tool in tools)
    nested = analyze_timing(events[:2], wall_seconds=6, requests=0, output_tokens=0)
    assert nested.tool_active_seconds == Available(value=1)
    overlapping = analyze_timing(events, wall_seconds=6, requests=0, output_tokens=0)
    assert overlapping.tool_active_seconds == Available(value=1.5)
    # Tool time is diagnostic, not subtracted again from the wall partition.
    combined = measure(two_responses() + events)
    assert combined.provider_client_seconds == Available(value=4)
    assert combined.non_provider_wall_seconds == Available(value=2)


def test_optional_server_metadata_is_distinct_from_client_spans() -> None:
    metadata = ProviderTiming(
        sdkDurationMillis=800,
        headersReceivedMillis=600,
        firstObservableDeltaMillis=700,
        serverCreatedAtSeconds=100,
        serverCompletedAtSeconds=100.5,
        providerProcessingMillis=450,
    )
    result = analyze_timing(
        (request(1, 500), response(10, 1500, metadata)),
        wall_seconds=2,
        requests=1,
        output_tokens=10,
    )
    evidence = result.responses[0]
    assert evidence.client_response_seconds == 1
    assert evidence.output_tokens_per_client_second == Available(value=10)
    assert evidence.sdk_duration_seconds == Available(value=0.8)
    assert evidence.headers_received_seconds == Available(value=0.1)
    assert evidence.first_observable_delta_seconds == Available(value=0.2)
    assert evidence.provider_timestamp_seconds == Available(value=0.5)
    assert evidence.output_tokens_per_provider_timestamp_second == Available(value=20)
    assert evidence.provider_processing_millis == Available(value=450)


def test_missing_provider_metadata_never_borrows_client_measurements() -> None:
    evidence = measure(two_responses()).responses[0]
    missing = Unavailable(reason=TimingFailure.MISSING_PROVIDER_METADATA)
    assert evidence.sdk_duration_seconds == missing
    assert evidence.headers_received_seconds == missing
    assert evidence.first_observable_delta_seconds == missing
    assert evidence.provider_timestamp_seconds == missing
    assert evidence.output_tokens_per_provider_timestamp_second == missing
    assert evidence.provider_processing_millis == missing
    assert missing.model_dump(mode="json") == {
        "kind": "unavailable",
        "reason": "MISSING_PROVIDER_METADATA",
    }


@pytest.mark.parametrize(
    "metadata",
    [
        ProviderTiming(serverCreatedAtSeconds=100),
        ProviderTiming(serverCompletedAtSeconds=101),
    ],
)
def test_one_server_timestamp_cannot_prove_a_span(metadata: ProviderTiming) -> None:
    result = analyze_timing(
        (request(1, 500), response(10, 1500, metadata)),
        wall_seconds=2,
        requests=1,
        output_tokens=10,
    )
    assert result.responses[0].provider_timestamp_seconds == Unavailable(
        reason=TimingFailure.MISSING_PROVIDER_METADATA
    )


@pytest.mark.parametrize(
    "metadata",
    [
        {"serverCreatedAtSeconds": 101, "serverCompletedAtSeconds": 100},
        {"serverCreatedAtSeconds": 100, "serverCompletedAtSeconds": float("inf")},
        {"firstObservableDeltaMillis": 100},
        {"firstObservableDeltaMillis": 1600},
        {"sdkDurationMillis": -1},
        {"providerProcessingMillis": float("nan")},
    ],
)
def test_bad_optional_metadata_does_not_erase_completed_client_span(
    metadata: dict[str, JsonValue],
) -> None:
    end = response(10, 1500)
    assert isinstance(end, dict)
    end["providerTiming"] = metadata
    result = analyze_timing(
        (request(1, 500), end),
        wall_seconds=2,
        requests=1,
        output_tokens=10,
    )
    assert result.qualification == "complete"
    assert result.provider_client_seconds == Available(value=1)
    assert Unavailable(reason=TimingFailure.INVALID_PROVIDER_METADATA) in (
        result.responses[0].provider_timestamp_seconds,
        result.responses[0].first_observable_delta_seconds,
        result.responses[0].sdk_duration_seconds,
        result.responses[0].provider_processing_millis,
    )


def test_missing_final_response_keeps_completed_prefix_but_not_task_totals() -> None:
    result = measure(two_responses()[:-1])
    assert result.qualification == "qualified_prefix"
    assert len(result.responses) == 1
    assert result.responses[0].output_tokens == 10
    assert result.responses[0].client_response_seconds == 1
    assert TimingFailure.UNMATCHED_REQUEST in result.issues
    assert isinstance(result.provider_client_seconds, Unavailable)
    assert isinstance(result.non_provider_wall_seconds, Unavailable)
    assert isinstance(result.output_tokens_per_client_second, Unavailable)


@pytest.mark.parametrize(
    ("events", "reason", "known"),
    [
        ((response(10, 1500),), TimingFailure.UNMATCHED_RESPONSE, 0),
        (two_responses() + (response(90, 5000),), TimingFailure.UNMATCHED_RESPONSE, 2),
        ((request(1, 500), request(2, 1000)), TimingFailure.UNMATCHED_REQUEST, 0),
        ((request(2, 500),), TimingFailure.INVALID_IDENTITY, 0),
        (
            (request(1, 500), response(10, 1500), request(1, 2000)),
            TimingFailure.INVALID_IDENTITY,
            1,
        ),
    ],
)
def test_duplicate_unmatched_and_invalid_request_identities(
    events: tuple[JsonValue, ...],
    reason: TimingFailure,
    known: int,
) -> None:
    result = measure(events)
    assert reason in result.issues
    assert len(result.responses) == known
    assert isinstance(result.provider_client_seconds, Unavailable)


@pytest.mark.parametrize("value", [-1, float("inf"), float("nan"), "500", None, True])
@pytest.mark.parametrize("kind", ["provider_request", "assistant_end"])
def test_malformed_nonfinite_and_negative_event_times_are_not_zero(
    value: JsonValue,
    kind: str,
) -> None:
    event = request(1, 500) if kind == "provider_request" else response(10, 1500)
    assert isinstance(event, dict)
    event["elapsedMillis"] = value
    events = (event,) if kind == "provider_request" else (request(1, 500), event)
    result = measure(events)
    assert TimingFailure.MALFORMED_EVENT in result.issues
    assert result.responses == ()
    assert isinstance(result.provider_client_seconds, Unavailable)


@pytest.mark.parametrize("number", [0, -1, True, 1.0, "1", None])
def test_malformed_request_identities(number: JsonValue) -> None:
    event = request(1, 500)
    assert isinstance(event, dict)
    event["request"] = number
    result = measure((event, response(10, 1500)))
    assert TimingFailure.MALFORMED_EVENT in result.issues
    assert result.responses == ()


@pytest.mark.parametrize("millis", [400, 6001])
def test_reversed_or_outside_session_response_intervals(millis: float) -> None:
    result = measure((request(1, 500), response(10, millis)))
    assert TimingFailure.INVALID_INTERVAL in result.issues
    assert result.responses == ()


@pytest.mark.parametrize("stop_reason", ["error", "aborted", "unknown"])
def test_non_finalized_usage_never_proves_zero_provider_time(stop_reason: str) -> None:
    event = response(0, 1500)
    assert isinstance(event, dict)
    event["stopReason"] = stop_reason
    result = measure((request(1, 500), event))
    assert TimingFailure.NON_FINALIZED_USAGE in result.issues
    assert result.responses == ()


def test_usage_total_mismatch_and_expected_output_mismatch() -> None:
    event = response(10, 1500)
    assert isinstance(event, dict) and isinstance(event["usage"], dict)
    event["usage"]["totalTokens"] = 999
    result = measure((request(1, 500), event))
    assert TimingFailure.USAGE_TOTAL_MISMATCH in result.issues
    assert result.responses == ()
    mismatch = analyze_timing(two_responses(), wall_seconds=6, requests=2, output_tokens=101)
    assert mismatch.issues == (TimingFailure.OUTPUT_TOTAL_MISMATCH,)
    assert len(mismatch.responses) == 2
    assert isinstance(mismatch.non_provider_wall_seconds, Unavailable)


@pytest.mark.parametrize(
    "usage",
    [
        None,
        {"type": "rejected"},
        {
            "type": "usage",
            "input": 0,
            "output": -1,
            "cacheRead": 0,
            "cacheWrite": 0,
            "totalTokens": 0,
        },
    ],
)
def test_missing_or_invalid_usage_is_not_finalized(usage: JsonValue) -> None:
    event = response(10, 1500)
    assert isinstance(event, dict)
    event["usage"] = usage
    result = measure((request(1, 500), event))
    assert TimingFailure.NON_FINALIZED_USAGE in result.issues


@pytest.mark.parametrize(
    ("identity", "end", "duration", "reason"),
    [
        ("", 1000, 1000, TimingFailure.MALFORMED_EVENT),
        (" ", 1000, 1000, TimingFailure.INVALID_IDENTITY),
        ("tool", 1000, 1001, TimingFailure.INVALID_INTERVAL),
        ("tool", 7000, 1, TimingFailure.INVALID_INTERVAL),
        ("tool", 1000, -1, TimingFailure.MALFORMED_EVENT),
        ("tool", float("inf"), 1, TimingFailure.MALFORMED_EVENT),
        ("tool", 1000, float("nan"), TimingFailure.MALFORMED_EVENT),
    ],
)
def test_bad_tools_cannot_become_zero_active_time(
    identity: str,
    end: float,
    duration: float,
    reason: TimingFailure,
) -> None:
    result = measure(
        two_responses()
        + (
            {
                "type": "tool_end",
                "toolCallId": identity,
                "elapsedMillis": end,
                "durationMillis": duration,
            },
        )
    )
    assert result.tool_active_seconds == Unavailable(reason=reason)
    assert result.provider_client_seconds == Available(value=4)


def test_duplicate_tool_identity_is_rejected() -> None:
    tool = ToolEnd(toolCallId="duplicate", elapsedMillis=1000, durationMillis=500)
    result = measure(two_responses() + (tool.model_dump(mode="json"),) * 2)
    assert result.tool_active_seconds == Unavailable(reason=TimingFailure.INVALID_IDENTITY)


@pytest.mark.parametrize("wall", [-1, float("inf"), float("nan")])
def test_invalid_session_wall(wall: float) -> None:
    result = analyze_timing(two_responses(), wall_seconds=wall, requests=2, output_tokens=100)
    assert result.issues == (TimingFailure.INVALID_SESSION,)
    assert result.responses == ()


def test_zero_duration_is_not_a_finite_rate() -> None:
    result = analyze_timing(
        (request(1, 500), response(10, 500)),
        wall_seconds=1,
        requests=1,
        output_tokens=10,
    )
    assert result.provider_client_seconds == Available(value=0)
    assert result.output_tokens_per_client_second == Unavailable(reason=TimingFailure.ZERO_DURATION)


def test_arm_aggregation_uses_sums_and_preserves_incomplete_evidence() -> None:
    short = analyze_timing(
        (request(1, 0), response(10, 1000)),
        wall_seconds=2,
        requests=1,
        output_tokens=10,
    )
    long = analyze_timing(
        (request(1, 0), response(90, 3000)),
        wall_seconds=4,
        requests=1,
        output_tokens=90,
    )
    arm = aggregate_timing((short, long))
    assert (arm.tasks, arm.complete_tasks, arm.unavailable_tasks) == (2, 2, 0)
    assert arm.provider_client_seconds == Available(value=4)
    assert arm.non_provider_wall_seconds == Available(value=2)
    assert arm.output_tokens_per_client_second == Available(value=25)
    prefix = measure(two_responses()[:-1])
    partial = aggregate_timing((long, prefix))
    assert (partial.tasks, partial.complete_tasks, partial.unavailable_tasks) == (2, 1, 1)
    assert partial.known_output_tokens == 100
    assert partial.known_provider_client_seconds == 4
    assert partial.known_output_tokens_per_client_second == Available(value=25)
    assert partial.output_tokens_per_client_second == Unavailable(
        reason=TimingFailure.INCOMPLETE_TASKS
    )
    assert isinstance(partial.non_provider_wall_seconds, Unavailable)


def test_journal_adapter_missing_and_truncated_file(tmp_path: Path) -> None:
    path = tmp_path / "journal.jsonl"
    missing = read_timing(path, wall_seconds=6, requests=2, output_tokens=100)
    assert missing.issues == (TimingFailure.NO_JOURNAL,)
    assert missing.provider_client_seconds == Unavailable(reason=TimingFailure.NO_JOURNAL)
    lines = (
        ProviderRequest(request=1, elapsedMillis=500).model_dump_json(),
        AssistantEnd(
            elapsedMillis=1500,
            stopReason="stop",
            usage=Usage(input=2, output=10, cacheRead=3, cacheWrite=4, totalTokens=19).model_dump(
                mode="json"
            ),
        ).model_dump_json(),
    )
    _ = path.write_text("\n".join(lines) + '\n{"broken":')
    truncated = read_timing(path, wall_seconds=6, requests=2, output_tokens=100)
    assert truncated.qualification == "qualified_prefix"
    assert TimingFailure.MALFORMED_JOURNAL in truncated.issues
    assert truncated.responses[0].client_response_seconds == 1
    assert isinstance(truncated.provider_client_seconds, Unavailable)
    assert isinstance(truncated.tool_active_seconds, Unavailable)


def test_server_rate_uses_provider_spans_and_missing_fields_stay_unavailable() -> None:
    measured = analyze_timing(
        (
            request(1, 500),
            response(
                10, 1500, ProviderTiming(serverCreatedAtSeconds=100, serverCompletedAtSeconds=100.5)
            ),
            request(2, 2000),
            response(
                90, 5000, ProviderTiming(serverCreatedAtSeconds=102, serverCompletedAtSeconds=103.5)
            ),
        ),
        wall_seconds=6,
        requests=2,
        output_tokens=100,
    )
    aggregate = aggregate_timing((measured,))
    assert aggregate.provider_timestamp_seconds == Available(value=2)
    assert aggregate.output_tokens_per_provider_timestamp_second == Available(value=50)
    assert aggregate.output_tokens_per_client_second == Available(value=25)
    missing = aggregate_timing((measure(two_responses()),))
    assert missing.provider_timestamp_seconds == Unavailable(
        reason=TimingFailure.MISSING_PROVIDER_METADATA
    )
    partial = measure(two_responses()[:-1])
    assert isinstance(aggregate_timing((partial,)).provider_timestamp_seconds, Unavailable)


def test_interval_cannot_reverse_time() -> None:
    with pytest.raises(ValidationError):
        Interval(start=2, end=1)


def test_complete_timing_needs_complete_provider_evidence() -> None:
    unsupported = no_journal().model_dump(mode="python")
    unsupported["qualification"] = "complete"
    with pytest.raises(ValidationError):
        TaskTiming.model_validate(unsupported)
