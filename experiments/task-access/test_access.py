import json

import pytest

from access import InputError, prepare


def payload(*args: str) -> dict[str, object]:
    return json.loads(prepare(list(args)).arguments.model_dump_json(exclude_none=True))


def test_interface_search_is_recursive_and_does_not_expand_overloads() -> None:
    actual = payload(
        "find", "run", "--kind", "interface", "--directory", "app-server", "--source-set", "main"
    )
    assert actual == {
        "request": {
            "type": "RUN",
            "source": {
                "type": "SEARCH_DECLARATIONS",
                "declarationName": "run",
                "nameMatch": "EXACT",
                "declarationKinds": ["CLASS"],
                "scope": {
                    "type": "DIRECTORY",
                    "relativeDirectoryPath": "app-server",
                    "includeSubdirectories": True,
                    "sourceSetNames": ["main"],
                },
            },
            "terminalStep": {
                "type": "EMIT",
                "output": {"type": "SYMBOLS", "fields": ["NAME", "LOCATION", "SIGNATURE"]},
            },
            "retention": "RETAIN",
            "executionBudget": {"maxResults": 20},
        }
    }


def test_location_cannot_be_substituted_for_callee_identity() -> None:
    with pytest.raises(InputError):
        prepare(["callers", "--file", "Orders.kt", "--offset", "130"])


def test_trace_has_one_terminal_and_preserves_destination_scope() -> None:
    req = payload(
        "trace",
        "--ref",
        "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
        "--directory",
        "cli",
        "--source-set",
        "main",
    )["request"]
    assert isinstance(req, dict)
    assert "steps" not in req
    assert req["terminalStep"] == {
        "type": "TRACE",
        "output": {"type": "SYMBOLS", "fields": ["NAME", "LOCATION", "SIGNATURE"]},
        "expansionScope": {
            "type": "SOURCE_DOMAIN",
            "directory": "cli",
            "sourceSets": ["main"],
            "includeSubdirectories": True,
            "sourcePolicy": "PRODUCTION_AND_TEST",
            "generatedSources": "INCLUDE",
        },
    }


def test_walk_keeps_each_hop_and_does_not_chain_three_expansions() -> None:
    req = payload("walk", "callers", "--ref", "exact:v5:AAAAAAAAAAAAAAAAAAAAAA", "--depth", "3")[
        "request"
    ]
    assert isinstance(req, dict)
    assert req["steps"] == [
        {
            "type": "WALK",
            "relation": "CALLERS",
            "maximumDepth": 3,
            "expansionScope": {"type": "WORKSPACE"},
        }
    ]
    assert req["terminalStep"] == {"type": "EMIT", "output": {"type": "TRAVERSAL_RECORDS"}}


@pytest.mark.parametrize(
    "args",
    [
        ["find", "Thing", "--bytes", "1000"],
        ["find", "Thing", "--directory", "../other"],
        ["find", "Thing", "--directory", "/absolute"],
        ["find", "Thing", "--directory", "*.kt"],
        ["find", "Thing", "--directory", "Thing.kt"],
        ["find", "Thing", "--source-set", "main", "--source-set", "main"],
        ["callers", "--ref", "result:v1:11111111-1111-1111-1111-111111111111"],
        ["resume", "--token", "result:v1:11111111-1111-1111-1111-111111111111"],
        ["walk", "callers", "--ref", "exact:v5:AAAAAAAAAAAAAAAAAAAAAA"],
    ],
)
def test_reject_known_misuses_before_transport(args: list[str]) -> None:
    with pytest.raises(InputError):
        prepare(args)


def test_occurrence_page_keeps_both_cursors_and_projection() -> None:
    actual = payload(
        "result",
        "--ref",
        "result:v1:11111111-1111-1111-1111-111111111111",
        "--cursor",
        "20",
        "--evidence-cursor",
        "3",
        "--output",
        "occurrences",
    )
    assert actual == {
        "request": {
            "type": "READ_RESULT",
            "result": "result:v1:11111111-1111-1111-1111-111111111111",
            "cursor": 20,
            "evidence_cursor": 3,
            "output": {"type": "OCCURRENCES"},
            "executionBudget": {"maxResults": 20},
        }
    }
