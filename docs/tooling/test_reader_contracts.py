"""Reader metadata has one owner and derived links preserve actual content."""

from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path

import pytest
from reader_contracts import (
    Code,
    DensityDisposition,
    Failure,
    PageContract,
    Profile,
    ReaderContracts,
    Report,
    Role,
    Scenario,
    assess,
    content_inputs,
    local_imports,
    navigation_parents,
    outgoing_links,
    parse_contracts,
)


def contract(route: str) -> PageContract:
    return PageContract(
        route,
        Role.HOW_TO,
        "Read a known result.",
        ("Read the result.", "Keep its scope."),
        "Native runtime proof.",
        "docs/public/start.mdx",
        Profile.TASK,
        "Compare the result with known source.",
        "END",
        "/",
        DensityDisposition("ACCEPTED", "The procedure keeps its required qualifications."),
    )


def metadata() -> ReaderContracts:
    return ReaderContracts(
        (contract("/"), contract("/start")),
        Scenario(
            ("The reader owns the project.",),
            "Read a result.",
            ("/", "/start"),
            (),
            (),
            "Compare known source.",
        ),
    )


def fixture(root: Path) -> None:
    site = root / "docs/public"
    (site / "reference").mkdir(parents=True)
    (root / "docs/okf-public.json").write_text(json.dumps({"readerContracts": asdict(metadata())}))
    (site / "docs.json").write_text(
        json.dumps({"navigation": {"tabs": [{"tab": "Guides", "pages": ["index", "start"]}]}})
    )
    (site / "index.mdx").write_text("[Start](/start)\n")
    (site / "start.mdx").write_text("A useful result.\n")
    (site / "reference/callables.openapi.json").write_text('{"paths": {}}')


def test_complete_contract_parses_and_keeps_scenario_facts() -> None:
    assert parse_contracts(json.loads(json.dumps(asdict(metadata())))) == metadata()


@pytest.mark.parametrize(
    "field,value",
    [
        ("scope", ["One bullet"]),
        ("role", "mixed"),
        ("outcome", ""),
        ("primaryContinuation", "somewhere"),
    ],
)
def test_invalid_contract_is_a_finite_failure(field: str, value: object) -> None:
    raw = json.loads(json.dumps(asdict(metadata())))
    raw["pages"][0][field] = value
    result = parse_contracts(raw)
    assert isinstance(result, Failure)
    assert result.code == Code.INPUT


def test_duplicate_contract_rejects() -> None:
    raw = json.loads(json.dumps(asdict(metadata())))
    raw["pages"] = [raw["pages"][0], raw["pages"][0]]
    assert isinstance(parse_contracts(raw), Failure)


@pytest.mark.parametrize("status", [{}, []])
def test_malformed_density_status_returns_finite_failure(status: object) -> None:
    raw = json.loads(json.dumps(asdict(metadata())))
    raw["pages"][0]["densityDisposition"]["status"] = status
    result = parse_contracts(raw)
    assert isinstance(result, Failure)
    assert result.code == Code.INPUT


@pytest.mark.parametrize("field", ["primaryContinuation", "recoveryDestination"])
def test_malformed_destination_returns_finite_failure(field: str) -> None:
    raw = json.loads(json.dumps(asdict(metadata())))
    raw["pages"][0][field] = "//["
    result = parse_contracts(raw)
    assert isinstance(result, Failure)
    assert result.code == Code.INPUT


@pytest.mark.parametrize(
    "destination", ["/start#3-ask-about-your-code", "/start#okf-source_identity"]
)
def test_legal_fragment_destinations_keep_their_exact_spelling(destination: str) -> None:
    raw = json.loads(json.dumps(asdict(metadata())))
    raw["pages"][0]["primaryContinuation"] = destination
    raw["pages"][0]["recoveryDestination"] = destination
    result = parse_contracts(raw)
    assert isinstance(result, ReaderContracts)
    assert result.pages[0].primaryContinuation == destination
    assert result.pages[0].recoveryDestination == destination


def test_navigation_derives_parents_and_rejects_duplicate_route() -> None:
    tree = {
        "tabs": [
            {
                "tab": "Reference",
                "groups": [{"group": "Schemas", "pages": ["POST /callables/query_symbols"]}],
            }
        ]
    }
    assert navigation_parents(tree) == {
        "/reference/callables/query-symbols": ("Reference", "Schemas")
    }
    assert isinstance(navigation_parents({"pages": ["start", "start"]}), Failure)


def test_graph_reverses_actual_edges_and_binds_source_content(tmp_path: Path) -> None:
    fixture(tmp_path)
    result = assess(tmp_path, "candidate-sha")
    assert isinstance(result, Report)
    assert result.sourceRevision == "candidate-sha"
    assert result.pages[1].incoming == ("/",)
    assert result.pages[0].outgoing == ("/start",)
    assert (result.firstUse.D, result.firstUse.K, result.firstUse.H) == (0, 0, 1)
    before = result.inputs
    (tmp_path / "docs/public/start.mdx").write_text("A changed result.\n")
    after = assess(tmp_path, "candidate-sha")
    assert isinstance(after, Report)
    assert before != after.inputs
    assert after.firstUse.nativeTask == "NOT_RUN"


def test_hidden_panels_and_each_import_use_count_in_full(tmp_path: Path) -> None:
    fixture(tmp_path)
    snippets = tmp_path / "docs/public/snippets"
    snippets.mkdir()
    (snippets / "shared.mdx").write_text("Shared words.\n```text\nline one\n\nline two\n```\n")
    (tmp_path / "docs/public/start.mdx").write_text(
        'import Shared from "/snippets/shared.mdx"\n\n<Tabs><Tab title="First"><Shared /></Tab><Tab title="Second"><Shared /></Tab></Tabs>\n'
    )
    result = assess(tmp_path, "revision")
    assert isinstance(result, Report)
    footprint = result.pages[1].footprint
    assert footprint.proseWords == 6
    assert footprint.codeLines == 4
    assert footprint.scope == "STATIC_COMPLETE"


def test_missing_scope_and_unreachable_scenario_do_not_pass(tmp_path: Path) -> None:
    fixture(tmp_path)
    (tmp_path / "docs/public/index.mdx").write_text("No transition.\n")
    result = assess(tmp_path, "revision")
    assert isinstance(result, Failure)
    assert result.code == Code.SCENARIO


def test_broken_content_link_rejects(tmp_path: Path) -> None:
    fixture(tmp_path)
    (tmp_path / "docs/public/start.mdx").write_text("[Missing](/missing)\n")
    result = assess(tmp_path, "revision")
    assert isinstance(result, Failure)
    assert result.code == Code.LINK


def test_recursive_import_rejects(tmp_path: Path) -> None:
    fixture(tmp_path)
    (tmp_path / "docs/public/start.mdx").write_text('import Start from "/start.mdx"\n<Start />\n')
    result = assess(tmp_path, "revision")
    assert isinstance(result, Failure)
    assert result.code == Code.IMPORT


def test_code_examples_do_not_create_navigation_edges() -> None:
    assert outgoing_links(
        "[Real](/start)\n```md\n[Example](/missing)\n```\n`[Literal](/literal)`"
    ) == ("/start",)


def test_images_do_not_create_navigation_edges() -> None:
    assert outgoing_links("![Alt](/images/example.svg)\n[Start](/start)") == ("/start",)


def test_import_literals_preserve_code_and_do_not_add_prose(tmp_path: Path) -> None:
    fixture(tmp_path)
    snippets = tmp_path / "docs/public/snippets"
    snippets.mkdir()
    (snippets / "shared.mdx").write_text("Shared words.\n")
    (tmp_path / "docs/public/start.mdx").write_text(
        'import Shared from "/snippets/shared.mdx"\n\n```mdx\n<Shared />\n```\n\n<Shared />\n'
    )
    result = assess(tmp_path, "revision")
    assert isinstance(result, Report)
    assert result.pages[1].footprint.proseWords == 2
    assert result.pages[1].footprint.codeLines == 1


def test_content_closure_binds_nested_mdx_and_jsx(tmp_path: Path) -> None:
    fixture(tmp_path)
    snippets = tmp_path / "docs/public/snippets"
    snippets.mkdir()
    (snippets / "inner.jsx").write_text("export const Inner = () => null;\n")
    (snippets / "shared.mdx").write_text('import {Inner} from "./inner.jsx"\n<Inner />\n')
    (tmp_path / "docs/public/start.mdx").write_text(
        'import Shared from "/snippets/shared.mdx"\n<Shared />\n'
    )
    before = content_inputs(tmp_path, "/start")
    assert not isinstance(before, Failure)
    assert tuple(item.path for item in before) == (
        "docs/public/snippets/inner.jsx",
        "docs/public/snippets/shared.mdx",
        "docs/public/start.mdx",
    )
    (snippets / "inner.jsx").write_text("export const Inner = () => 'changed';\n")
    after = content_inputs(tmp_path, "/start")
    assert not isinstance(after, Failure)
    assert before != after


def test_callable_closure_binds_the_generated_artifact(tmp_path: Path) -> None:
    fixture(tmp_path)
    result = content_inputs(tmp_path, "/reference/callables/query-symbols")
    assert not isinstance(result, Failure)
    assert tuple(item.path for item in result) == ("docs/public/reference/callables.openapi.json",)


@pytest.mark.parametrize("target", ["/../../../outside.mdx", "../../outside.mdx", "/missing.mdx"])
def test_content_closure_rejects_escape_or_missing_import(tmp_path: Path, target: str) -> None:
    fixture(tmp_path)
    (tmp_path / "docs/public/start.mdx").write_text(
        f'import Imported from "{target}"\n<Imported />\n'
    )
    assert isinstance(content_inputs(tmp_path, "/start"), Failure)


def test_content_closure_rejects_symlink_escape(tmp_path: Path) -> None:
    fixture(tmp_path)
    outside = tmp_path / "outside.mdx"
    outside.write_text("Outside the public source.\n")
    (tmp_path / "docs/public/linked.mdx").symlink_to(outside)
    (tmp_path / "docs/public/start.mdx").write_text(
        'import Imported from "/linked.mdx"\n<Imported />\n'
    )
    result = content_inputs(tmp_path, "/start")
    assert isinstance(result, Failure)
    assert result.code == Code.IMPORT


def test_wrapped_import_prose_is_not_a_component_import() -> None:
    assert (
        local_imports(
            "These directions grant no automatic\nimport, or refresh capability.\nimport remains an explicit operation.\n",
            "docs/public/example.mdx",
        )
        == ()
    )
