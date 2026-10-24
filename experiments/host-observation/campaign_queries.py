"""Typed variants for the existing installed semantic replay owner.

A variant proves only its named oracle. It never completes a checklist family.
"""

from __future__ import annotations

import hashlib
import json
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, JsonValue, TypeAdapter, ValidationError

JsonObject = dict[str, JsonValue]
JSON: TypeAdapter[JsonValue] = TypeAdapter(JsonValue)


class Frozen(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class Corpus(Frozen):
    name: str
    file: str
    sha256: str
    source_set: str
    name_selected: str
    anchor: str
    direct_witness: str


CORPORA = (
    Corpus(
        name="spring-framework",
        file="spring-webflux/src/main/kotlin/org/springframework/web/reactive/function/server/RouterFunctionDsl.kt",
        sha256="0194cafa334df775cb74e7e4c6cc89fa3ee157436cd3b1b3ac4304e81f3477df",
        source_set="main",
        name_selected="router",
        anchor="fun router(routes:",
        direct_witness="build",
    ),
    Corpus(
        name="ktor",
        file="ktor-server/ktor-server-core/common/src/io/ktor/server/routing/RoutingBuilder.kt",
        sha256="1347f1a520ef82c0fdae617ef94b4869cf9c0964c8a72f1853daa281d752b4e4",
        source_set="commonMain",
        name_selected="route",
        anchor="public fun Route.route(path: String, build:",
        direct_witness="createRouteFromPath",
    ),
    Corpus(
        name="kotlinx.coroutines",
        file="kotlinx-coroutines-core/common/src/flow/operators/Transform.kt",
        sha256="bb0b859df00fc9cfe96e518e1969c6a36f68c026cafd7fab70dfd86b66118b1f",
        source_set="commonMain",
        name_selected="map",
        anchor="public inline fun <T, R> Flow<T>.map(crossinline",
        direct_witness="unsafeTransform",
    ),
    Corpus(
        name="kast",
        file="kernel/src/main/kotlin/io/github/amichne/kast/kernel/Validation.kt",
        sha256="cbc36808fffbdf66e28ac10e2f94a558b160e20e66bb38478bcbc3cb7e685e7a",
        source_set="main",
        name_selected="rejected",
        anchor="fun <Strong, Failure> rejected(failure:",
        direct_witness="one",
    ),
)


class Failure(StrEnum):
    INPUT_UNAVAILABLE = "INPUT_UNAVAILABLE"
    SOURCE_CHANGED = "SOURCE_CHANGED"
    ANCHOR_AMBIGUOUS = "ANCHOR_AMBIGUOUS"
    INVALID_RESPONSE = "INVALID_RESPONSE"
    SELECTION_UNAVAILABLE = "SELECTION_UNAVAILABLE"
    SELECTION_AMBIGUOUS = "SELECTION_AMBIGUOUS"
    RETENTION_UNAVAILABLE = "RETENTION_UNAVAILABLE"
    PROCESS_UNAVAILABLE = "PROCESS_UNAVAILABLE"
    FRESHNESS_REJECTED = "FRESHNESS_REJECTED"
    INCOMPLETE_RESPONSE = "INCOMPLETE_RESPONSE"
    COUNT_MISMATCH = "COUNT_MISMATCH"
    WITNESS_MISSING = "WITNESS_MISSING"
    REJECTION_MISMATCH = "REJECTION_MISMATCH"
    SOURCE_TEXT_MISMATCH = "SOURCE_TEXT_MISMATCH"


class Blocked(Frozen):
    status: Literal["BLOCKED"] = "BLOCKED"
    reason: Failure


class SourceWitness(Frozen):
    corpus: Corpus
    absolute_file: str
    utf16_offset: int
    text: str


def load_witness(corpus: Corpus, root: Path) -> SourceWitness | Blocked:
    path = root / corpus.file
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (OSError, UnicodeError):
        return Blocked(reason=Failure.INPUT_UNAVAILABLE)
    if hashlib.sha256(raw).hexdigest() != corpus.sha256:
        return Blocked(reason=Failure.SOURCE_CHANGED)
    if text.count(corpus.anchor) != 1:
        return Blocked(reason=Failure.ANCHOR_AMBIGUOUS)
    offset = text.index(corpus.anchor) + len(corpus.anchor) - 1
    return SourceWitness(
        corpus=corpus,
        absolute_file=str(path.resolve()),
        utf16_offset=len(text[:offset].encode("utf-16-le")) // 2,
        text=text,
    )


class CompleteCount(Frozen):
    type: Literal["COMPLETE_COUNT"] = "COMPLETE_COUNT"
    count: int = Field(ge=0)


class CompleteWitness(Frozen):
    type: Literal["COMPLETE_WITNESS"] = "COMPLETE_WITNESS"
    name: str


class CompleteOnly(Frozen):
    type: Literal["COMPLETION_ONLY"] = "COMPLETION_ONLY"


class RelationWitness(Frozen):
    type: Literal["EXACT_RELATION_WITNESS"] = "EXACT_RELATION_WITNESS"
    projection: Literal["OCCURRENCES", "TRAVERSAL_RECORDS"]
    target: str


class SourceSlice(Frozen):
    type: Literal["SOURCE_SLICE"] = "SOURCE_SLICE"
    region: str
    text_mode: str


class Rejection(Frozen):
    type: Literal["EXACT_REJECTION"] = "EXACT_REJECTION"
    codes: tuple[str, ...] = Field(min_length=1)


Oracle = Annotated[
    CompleteCount | CompleteWitness | CompleteOnly | RelationWitness | Rejection | SourceSlice,
    Field(discriminator="type"),
]


class Variant(Frozen):
    id: str = Field(pattern=r"^[A-N][0-9]{2}-[a-z0-9-]+$")
    request_json: str
    oracle: Oracle
    scope: Literal["VARIANT_ONLY"] = "VARIANT_ONLY"


class Passed(Frozen):
    status: Literal["PASSED"] = "PASSED"
    assertion: str


class Failed(Frozen):
    status: Literal["FAILED"] = "FAILED"
    reason: Failure


class Selected(Frozen):
    ref: str
    name: str


class Retained(Frozen):
    result: str


def json_object(text: str) -> JsonObject | Blocked:
    try:
        value = JSON.validate_json(text)
    except ValidationError:
        return Blocked(reason=Failure.INVALID_RESPONSE)
    if not isinstance(value, dict):
        return Blocked(reason=Failure.INVALID_RESPONSE)
    return value


def select_exact(response: JsonObject, witness: SourceWitness) -> Selected | Blocked:
    items = response.get("items")
    if not isinstance(items, list):
        return Blocked(reason=Failure.SELECTION_UNAVAILABLE)
    selected: list[Selected] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        location = item.get("location")
        if not isinstance(location, dict) or location.get("file") != witness.absolute_file:
            continue
        extent = location.get("range")
        if not isinstance(extent, dict):
            continue
        start, end = extent.get("startInclusive"), extent.get("endExclusive")
        ref, name = item.get("ref"), item.get("name")
        if (
            isinstance(start, int)
            and isinstance(end, int)
            and start <= witness.utf16_offset < end
            and isinstance(ref, str)
            and ref.startswith("exact:")
            and name == witness.corpus.name_selected
        ):
            selected.append(Selected(ref=ref, name=witness.corpus.name_selected))
    if len(selected) == 1:
        return selected[0]
    return Blocked(
        reason=Failure.SELECTION_AMBIGUOUS if selected else Failure.SELECTION_UNAVAILABLE
    )


def retained_result(response: JsonObject) -> Retained | Blocked:
    value = response.get("retention")
    if isinstance(value, dict) and value.get("kind") == "retained":
        ref = value.get("reference")
        if isinstance(ref, str) and ref.startswith("result:"):
            return Retained(result=ref)
    return Blocked(reason=Failure.RETENTION_UNAVAILABLE)


def scope(corpus: Corpus) -> JsonObject:
    return {
        "type": "DIRECTORY",
        "relativeDirectoryPath": str(Path(corpus.file).parent),
        "sourceSetNames": [corpus.source_set],
    }


def source_domain(corpus: Corpus) -> JsonObject:
    return {
        "type": "SOURCE_DOMAIN",
        "directory": str(Path(corpus.file).parent),
        "sourceSets": [corpus.source_set],
        "sourcePolicy": "PRODUCTION_ONLY",
        "generatedSources": "EXCLUDE",
    }


def search(corpus: Corpus, name: str, match: str = "EXACT") -> JsonObject:
    return {
        "type": "SEARCH_DECLARATIONS",
        "declarationName": name,
        "declarationKinds": ["FUNCTION"],
        "scope": scope(corpus),
        "nameMatch": match,
    }


def symbols(fields: tuple[str, ...] = ("NAME", "LOCATION", "SIGNATURE")) -> JsonObject:
    return {"type": "SYMBOLS", "fields": list(fields)}


def run(
    source: JsonObject,
    steps: tuple[JsonObject, ...] = (),
    output: JsonObject | None = None,
    retain: bool = False,
) -> JsonObject:
    return {
        "verbose": True,
        "request": {
            "type": "RUN",
            "source": source,
            "steps": list(steps),
            "terminalStep": {"type": "EMIT", "output": symbols() if output is None else output},
            "executionMode": "BOUNDED",
            "executionBudget": {
                "maxElapsedMs": 15000,
                "maxWorkUnits": 2000000,
                "maxResults": 128,
                "maxReturnedBytes": 49152,
            },
            "retention": "RETAIN" if retain else "DISCARD",
        },
    }


def variant(id: str, request: JsonObject, oracle: Oracle) -> Variant:
    return Variant(id=id, request_json=json.dumps(request, separators=(",", ":")), oracle=oracle)


def discovery_variants(corpus: Corpus) -> tuple[Variant, ...]:
    variants: list[Variant] = []
    for label, mode in (("exact", "EXACT"), ("fuzzy", "FUZZY"), ("omitted", "EXACT")):
        source = search(corpus, corpus.name_selected, mode)
        if label == "omitted":
            source.pop("nameMatch")
        variants.append(
            variant(
                f"B01-{label}-function", run(source), CompleteWitness(name=corpus.name_selected)
            )
        )
    variants.append(
        variant(
            "B01-absent-function",
            run(search(corpus, "KastQualificationAbsentSymbol7E31C2")),
            CompleteCount(count=0),
        )
    )
    variants.append(
        variant(
            "B01-case-sensitive-function",
            run(search(corpus, corpus.name_selected.upper())),
            CompleteCount(count=0),
        )
    )
    return tuple(variants)


def relation_variants(witness: SourceWitness, selected: Selected) -> tuple[Variant, ...]:
    corpus = witness.corpus
    source: JsonObject = {"type": "SYMBOL_REFS", "symbolRefs": [selected.ref]}
    empty = search(corpus, "KastQualificationAbsentSymbol7E31C2")
    variants: list[Variant] = []
    strategies: tuple[tuple[str, JsonObject], ...] = (
        ("bfs", {"type": "BREADTH_FIRST"}),
        ("fan-out", {"type": "BOUNDED_FAN_OUT", "maximumEdgesPerNode": 1000}),
    )
    for label, strategy in strategies:
        walk: JsonObject = {
            "type": "WALK",
            "relation": "CALLEES",
            "maximumDepth": 1,
            "strategy": strategy,
            "expansionScope": source_domain(corpus),
        }
        variants.extend(
            (
                variant(
                    f"C07-{label}-one-hop-symbols",
                    run(source, (walk,)),
                    CompleteWitness(name=corpus.direct_witness),
                ),
                variant(f"C07-{label}-empty-seed", run(empty, (walk,)), CompleteCount(count=0)),
            )
        )
    for label, seed, oracle in (
        ("selected", source, CompleteOnly()),
        ("empty", empty, CompleteCount(count=0)),
    ):
        request = run(seed)
        action = request["request"]
        assert isinstance(action, dict)
        action["terminalStep"] = {
            "type": "TRACE",
            "expansionScope": source_domain(corpus),
            "output": symbols(),
        }
        variants.append(variant(f"C08-trace-{label}-domain", request, oracle))
    for projection in ("OCCURRENCES", "TRAVERSAL_RECORDS"):
        step: JsonObject = {
            "type": "EXPAND_RELATION" if projection == "OCCURRENCES" else "WALK",
            "relation": "CALLEES",
            "expansionScope": source_domain(corpus),
        }
        if projection == "TRAVERSAL_RECORDS":
            step["maximumDepth"] = 1
            step["strategy"] = {"type": "BREADTH_FIRST"}
        variants.append(
            variant(
                f"C09-{projection.lower().replace('_', '-')}-callee-witness",
                run(source, (step,), {"type": projection}),
                RelationWitness(projection=projection, target=corpus.direct_witness),
            )
        )
    return tuple(variants)


def source_variants(corpus: Corpus) -> tuple[Variant, ...]:
    selected_scope = scope(corpus)
    selected_scope["includeSubdirectories"] = False
    absent = "KastQualificationAbsentSymbol7E31C2"
    text: JsonObject = {
        "type": "SEARCH_TEXT",
        "word": corpus.name_selected,
        "scope": selected_scope,
        "declarationKinds": ["FUNCTION"],
    }
    declarations: JsonObject = {
        "type": "ALL_DECLARATIONS",
        "scope": selected_scope,
        "declarationKinds": ["FUNCTION"],
    }
    negative: JsonObject = {
        "type": "WHERE",
        "predicate": {"type": "PRIMITIVE", "field": "NAME", "operator": "EQUALS", "value": absent},
    }
    return (
        variant("B04-text-declaration-word", run(text), CompleteWitness(name=corpus.name_selected)),
        variant("B04-text-absent-word", run({**text, "word": absent}), CompleteCount(count=0)),
        variant(
            "B03-all-direct-functions",
            run(declarations),
            CompleteWitness(name=corpus.name_selected),
        ),
        variant("B03-all-absent-name", run(declarations, (negative,)), CompleteCount(count=0)),
    )


def selected_variants(
    witness: SourceWitness, selected: Selected, retained: Retained
) -> tuple[Variant, ...]:
    corpus = witness.corpus
    source: JsonObject = {"type": "SYMBOL_REFS", "symbolRefs": [selected.ref]}
    right: JsonObject = {"type": "RESULT", "result": retained.result}
    variants: list[Variant] = []

    def add(id: str, request: JsonObject, oracle: Oracle) -> None:
        variants.append(variant(id, request, oracle))

    for field in ("NAME", "LOCATION", "SIGNATURE", "SOURCE"):
        add(
            f"B08-field-{field.lower()}",
            run(source, output=symbols((field,))),
            CompleteCount(count=1),
        )
    add("B08-empty-fields", run(source, output=symbols(())), CompleteCount(count=1))
    add(
        "B05-duplicate-symbol-refs",
        run({"type": "SYMBOL_REFS", "symbolRefs": [selected.ref, selected.ref]}),
        CompleteCount(count=2),
    )
    add("B05-retained-result", run(right), CompleteCount(count=1))
    add(
        "B05-retained-empty-selection",
        run({**right, "rowIds": []}),
        Rejection(codes=("ROW_SELECTION_INCOMPLETE",)),
    )
    add(
        "B04-location",
        run({"type": "AT_LOCATION", "file": corpus.file, "offset": witness.utf16_offset}),
        CompleteWitness(name=selected.name),
    )
    for field, value in (
        ("NAME", selected.name),
        ("KIND", "function"),
        ("FILE", witness.absolute_file),
    ):
        for operator in ("EQUALS", "NOT_EQUALS", "STARTS_WITH", "ENDS_WITH"):
            predicate: JsonObject = {
                "type": "WHERE",
                "predicate": {
                    "type": "PRIMITIVE",
                    "field": field,
                    "operator": operator,
                    "value": value,
                },
            }
            add(
                f"E01-{field.lower()}-{operator.lower().replace('_', '-')}",
                run(source, (predicate,)),
                CompleteCount(count=0 if operator == "NOT_EQUALS" else 1),
            )
    for visibility in ("PUBLIC", "PROTECTED", "INTERNAL", "PRIVATE", "LOCAL"):
        predicate = {"type": "WHERE", "predicate": {"type": "VISIBILITY", "values": [visibility]}}
        add(
            f"E01-visibility-{visibility.lower()}",
            run(source, (predicate,)),
            CompleteCount(count=1 if visibility == "PUBLIC" else 0),
        )
    for operator in ("UNION", "INTERSECT", "DIFFERENCE"):
        add(
            f"E02-{operator.lower()}-identical-set",
            run(source, ({"type": operator, "right": right},)),
            CompleteCount(count=0 if operator == "DIFFERENCE" else 1),
        )
    add(
        "E02-concat-duplicate",
        run(source, ({"type": "CONCAT", "input": source},)),
        CompleteCount(count=2),
    )
    add(
        "E02-distinct-concat",
        run(source, ({"type": "CONCAT", "input": source}, {"type": "DISTINCT_SYMBOLS"})),
        CompleteCount(count=1),
    )
    for mode in ("SEMI", "ANTI"):
        add(
            f"E03-{mode.lower()}-identical-set",
            run(source, ({"type": "JOIN", "mode": {"type": mode}, "right": right},)),
            CompleteCount(count=1 if mode == "SEMI" else 0),
        )
    join: JsonObject = {
        "type": "JOIN",
        "mode": {"type": "INNER", "leftName": "left", "rightName": "right"},
        "right": right,
    }
    add(
        "E03-inner-identical-set",
        run(source, (join,), {"type": "BINDING_ROWS"}),
        CompleteCount(count=1),
    )
    for name in ("left", "right"):
        add(
            f"E04-project-{name}",
            run(source, (join, {"type": "PROJECT_BINDING", "name": name})),
            CompleteCount(count=1),
        )
    for relation in (
        "REFERENCES",
        "CALLERS",
        "CALLEES",
        "IMPLEMENTATIONS",
        "INHERITORS",
        "OVERRIDES",
        "TYPE_USES",
    ):
        oracle: Oracle = (
            CompleteWitness(name=corpus.direct_witness) if relation == "CALLEES" else CompleteOnly()
        )
        add(
            f"C01-{relation.lower().replace('_', '-')}-function-domain",
            run(
                source,
                (
                    {
                        "type": "EXPAND_RELATION",
                        "relation": relation,
                        "expansionScope": source_domain(corpus),
                    },
                ),
                retain=True,
            ),
            oracle,
        )
    domains: tuple[JsonObject, ...] = ({"type": "RETAINED_SEED"}, {"type": "WORKSPACE"})
    for domain in domains:
        add(
            f"C02-references-{str(domain['type']).lower().replace('_', '-')}",
            run(
                source,
                ({"type": "EXPAND_RELATION", "relation": "REFERENCES", "expansionScope": domain},),
                retain=True,
            ),
            CompleteOnly(),
        )
    for retention in ("RETAIN", "DISCARD", "omitted", "null"):
        request = run(source)
        action = request["request"]
        assert isinstance(action, dict)
        if retention == "omitted":
            action.pop("retention")
        else:
            action["retention"] = None if retention == "null" else retention
        add(f"F04-retention-{retention.lower()}", request, CompleteCount(count=1))
    for mode in ("RUNNER_CONTROLLED", "omitted", "null", "BOUNDED"):
        request = run(source)
        action = request["request"]
        assert isinstance(action, dict)
        if mode != "BOUNDED":
            action["executionBudget"] = {"maxResults": 128, "maxReturnedBytes": 49152}
        if mode == "omitted":
            action.pop("executionMode")
        else:
            action["executionMode"] = None if mode == "null" else mode
        add(f"F01-mode-{mode.lower().replace('_', '-')}", request, CompleteCount(count=1))
    for region in (
        "ANCHOR",
        "CALLABLE_BODY",
        "CLASS_BODY",
        "FILE",
        "ENCLOSING_DECLARATION",
        "ENCLOSING_CALLABLE_BODY",
        "ENCLOSING_CLASS_BODY",
    ):
        absent = region == "ENCLOSING_CALLABLE_BODY" or (
            corpus.name != "kast"
            and region in ("CLASS_BODY", "ENCLOSING_DECLARATION", "ENCLOSING_CLASS_BODY")
        )
        for text_mode in ("COMPLETE", "NONE", "WINDOW", "EXCERPT"):
            text: JsonObject = {"type": text_mode}
            if text_mode == "WINDOW":
                text.update(beforeLines=1, afterLines=1)
            if text_mode == "EXCERPT":
                text["maxLines"] = 3
            request: JsonObject = {
                "verbose": True,
                "request": {
                    "type": "READ_SOURCE",
                    "candidateRef": selected.ref,
                    "region": region,
                    "text": text,
                },
            }
            oracle = (
                Rejection(codes=("region-absent",))
                if absent
                else SourceSlice(region=region, text_mode=text_mode)
            )
            add(f"B09-{region.lower().replace('_', '-')}-{text_mode.lower()}", request, oracle)
    return tuple(variants)


def source_slice_matches(response: JsonObject, oracle: SourceSlice, witness: SourceWitness) -> bool:
    snapshot, region, text = response.get("snapshot"), response.get("region"), response.get("text")
    if not isinstance(snapshot, dict) or not isinstance(region, dict) or not isinstance(text, dict):
        return False
    file = snapshot.get("file")
    root = snapshot.get("canonicalRoot")
    if not isinstance(file, str) or not isinstance(root, str):
        return False
    if str(Path(root) / file) != witness.absolute_file:
        return False
    if oracle.text_mode == "NONE":
        return text.get("type") == "not-requested"
    selection = text.get("selection")
    value = text.get("text")
    if not isinstance(selection, dict) or not isinstance(value, str):
        return False
    extent = selection.get("range")
    if not isinstance(extent, dict):
        return False
    start, end = extent.get("startInclusive"), extent.get("endExclusive")
    if not isinstance(start, int) or not isinstance(end, int) or start < 0 or end < start:
        return False
    raw = witness.text.encode("utf-16-le")
    if end * 2 > len(raw):
        return False
    try:
        expected = raw[start * 2 : end * 2].decode("utf-16-le")
    except UnicodeDecodeError:
        return False
    if value != expected:
        return False
    if oracle.region == "FILE" and oracle.text_mode == "COMPLETE":
        return start == 0 and end * 2 == len(raw)
    return True


def rejection_codes(response: JsonObject) -> tuple[str, ...]:
    found: list[str] = []
    pending: list[JsonValue] = [response]
    while pending:
        value = pending.pop()
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ("failure", "code", "type", "reason", "cause", "detail") and isinstance(
                    item, str
                ):
                    found.append(item)
                elif key == "limitations" and isinstance(item, list):
                    found.extend(code for code in item if isinstance(code, str))
                elif isinstance(item, (dict, list)):
                    pending.append(item)
        elif isinstance(value, list):
            pending.extend(value)
    return tuple(found)


def relation_witness_matches(
    item: JsonObject, oracle: RelationWitness, witness: SourceWitness
) -> bool:
    if oracle.projection == "TRAVERSAL_RECORDS":
        record = item.get("record")
        if item.get("type") != "traversal_record" or not isinstance(record, dict):
            return False
        if record.get("depth") != 1:
            return False
        relation = record.get("relation")
    else:
        if item.get("type") != "occurrence":
            return False
        relation = item.get("relation")
    if not isinstance(relation, dict):
        return False
    source, target = relation.get("source"), relation.get("target")
    occurrence = relation.get("occurrence")
    if (
        not isinstance(source, dict)
        or not isinstance(target, dict)
        or not isinstance(occurrence, dict)
    ):
        return False
    extent = occurrence.get("range")
    if not isinstance(extent, dict):
        return False
    start, end = extent.get("startInclusive"), extent.get("endExclusive")
    return (
        relation.get("meaning") == "callees"
        and relation.get("coverage") == "exact-compiler-confirmed"
        and relation.get("provenance") == "k2-authored-source"
        and source.get("name") == witness.corpus.name_selected
        and target.get("name") == oracle.target
        and occurrence.get("file") == witness.absolute_file
        and type(start) is int
        and type(end) is int
        and 0 <= start < end <= len(witness.text.encode("utf-16-le")) // 2
    )


def assess(
    oracle: Oracle,
    responses: tuple[JsonObject, ...],
    process_completed: bool,
    witness: SourceWitness,
) -> Passed | Failed | Blocked:
    if not process_completed or not responses:
        return Blocked(reason=Failure.PROCESS_UNAVAILABLE)
    codes = tuple(code for response in responses for code in rejection_codes(response))
    if "FRESHNESS_REJECTED" in codes:
        return Blocked(reason=Failure.FRESHNESS_REJECTED)
    if isinstance(oracle, Rejection):
        return (
            Passed(assertion=oracle.type)
            if any(
                response.get("status") == "rejected"
                and set(rejection_codes(response)) & set(oracle.codes)
                for response in responses
            )
            else Failed(reason=Failure.REJECTION_MISMATCH)
        )
    terminal = responses[-1]
    if isinstance(oracle, SourceSlice):
        if terminal.get("status") != "complete":
            return Failed(reason=Failure.INCOMPLETE_RESPONSE)
        return (
            Passed(assertion=oracle.type)
            if source_slice_matches(terminal, oracle, witness)
            else Failed(reason=Failure.SOURCE_TEXT_MISMATCH)
        )
    coverage = terminal.get("coverage")
    if (
        any(response.get("status") in ("rejected", "qualified") for response in responses)
        or terminal.get("status") != "complete"
        or not isinstance(coverage, dict)
        or coverage.get("exhaustive") is not True
    ):
        return Failed(reason=Failure.INCOMPLETE_RESPONSE)
    items: list[JsonObject] = []
    for response in responses:
        rows = response.get("items")
        if not isinstance(rows, list):
            return Blocked(reason=Failure.INVALID_RESPONSE)
        for item in rows:
            if not isinstance(item, dict):
                return Blocked(reason=Failure.INVALID_RESPONSE)
            items.append(item)
    if isinstance(oracle, CompleteCount) and len(items) != oracle.count:
        return Failed(reason=Failure.COUNT_MISMATCH)
    if isinstance(oracle, CompleteWitness) and not any(
        item.get("name") == oracle.name for item in items
    ):
        return Failed(reason=Failure.WITNESS_MISSING)
    if isinstance(oracle, RelationWitness) and not any(
        relation_witness_matches(item, oracle, witness) for item in items
    ):
        return Failed(reason=Failure.WITNESS_MISSING)
    return Passed(assertion=oracle.type)
