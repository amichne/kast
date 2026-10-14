"""Independent negative controls for inventory accounting and result population."""

from __future__ import annotations

import shutil
from pathlib import Path

import inventory as subject

ROOT = Path(__file__).resolve().parents[1]
CATALOGUE = Path(__file__).resolve().parents[3] / "gradle/libs.versions.toml"
EVIDENCE_CASES = ROOT / "inventory-evidence-cases.tsv"
BASELINE_JUNIT = (
    ROOT
    / "evidence/baseline/hosted/TEST-io.github.amichne.kast.runtime.hosted.NativeScopedCacheManagerTraceTest.xml"
)


def recorded_evidence() -> subject.CaseEvidence:
    return subject.case_evidence(subject.execution(EVIDENCE_CASES, (BASELINE_JUNIT,)))


def elements() -> tuple[subject.Element, ...]:
    parsed = subject.extract_elements((ROOT / "upstream/FirTree.kt").read_text())
    assert isinstance(parsed, subject.Accepted)
    return parsed.value


def classifications() -> tuple[subject.Classification, ...]:
    parsed = subject.table(ROOT / "classifications.tsv", subject.Classification)
    assert isinstance(parsed, subject.Accepted)
    return parsed.value


def code_set(issues: tuple[subject.Issue, ...]) -> set[subject.Code]:
    return {item.code for item in issues}


def case(case_id: str = "R1.effect") -> subject.RequiredCase:
    return subject.RequiredCase(case_id=case_id, test_result_id=f"IndependentFixture#{case_id}")


def test_full_inventory_and_independent_generated_visitor() -> None:
    population = elements()
    assert len(population) == 164
    assert len({item.element_id for item in population}) == 164
    assert {item.element_id for item in population} == subject.visitor_elements(
        (ROOT / "upstream/FirVisitor.kt").read_text()
    )
    assert subject.assess_classifications(population, classifications(), recorded_evidence()) == ()


def test_added_upstream_item_cannot_disappear() -> None:
    source = (ROOT / "upstream/FirTree.kt").read_text()
    source += "\n    val newCallbackContainer: Element by element(Expression) {}\n"
    extracted = subject.extract_elements(source)
    assert isinstance(extracted, subject.Accepted)
    assert len(extracted.value) == 165
    issues = subject.assess_classifications(extracted.value, classifications())
    assert subject.Code.MISSING_CLASSIFICATION in code_set(issues)
    assert any(item.detail == "FirNewCallbackContainer" for item in issues)


def test_missing_and_duplicate_classifications_fail() -> None:
    mappings = classifications()
    assert subject.Code.MISSING_CLASSIFICATION in code_set(
        subject.assess_classifications(elements(), mappings[1:])
    )
    assert subject.Code.DUPLICATE_CLASSIFICATION in code_set(
        subject.assess_classifications(elements(), (*mappings, mappings[0]))
    )


def test_implemented_and_traversal_only_need_executable_evidence() -> None:
    mappings = classifications()
    for kind in (subject.Disposition.IMPLEMENTED, subject.Disposition.TRAVERSAL_ONLY):
        changed = mappings[0].model_copy(update={"disposition": kind})
        assert subject.Code.MISSING_CASE_EVIDENCE in code_set(
            subject.assess_classifications(elements(), (changed, *mappings[1:]))
        )


def test_omitted_selected_fixture_and_changed_blob_fail() -> None:
    index = subject.table(ROOT / "upstream/resolver-source-index.tsv", subject.SourceFile)
    selected = subject.table(ROOT / "selected-fixtures.tsv", subject.Fixture)
    pin = subject.parse_pin((ROOT / "pin.toml").read_text())
    assert isinstance(index, subject.Accepted)
    assert isinstance(selected, subject.Accepted)
    assert isinstance(pin, subject.Accepted)
    required = pin.value.selected_fixture_sources
    assert len(index.value) == 1332
    assert len(required) == 6
    assert len(selected.value) == 24
    assert subject.assess_fixtures(required, index.value, selected.value) == ()
    without_first_source = tuple(row for row in selected.value if row.source_path != required[0])
    assert subject.Code.MISSING_FIXTURE in code_set(
        subject.assess_fixtures(required, index.value, without_first_source)
    )
    source = next(row for row in selected.value if row.source_path == required[0])
    changed = source.model_copy(update={"git_blob": "0" * 40})
    rest = tuple(row for row in selected.value if row.source_path != required[0])
    assert subject.Code.STALE_UPSTREAM in code_set(
        subject.assess_fixtures(required, index.value, (changed, *rest))
    )


def test_stale_upstream_identity_fails_with_preserved_source(tmp_path: Path) -> None:
    owned = tmp_path / "owned"
    _ = shutil.copytree(ROOT, owned, ignore=shutil.ignore_patterns("tooling", "evidence"))
    pin = owned / "pin.toml"
    _ = pin.write_text(
        pin.read_text().replace(
            'upstream_commit = "7342188118e8c24e12b5447f4430730ccc9c1989"',
            'upstream_commit = "0000000000000000000000000000000000000000"',
        )
    )
    report = subject.audit(owned, CATALOGUE)
    assert report.status == "FAIL"
    assert subject.Code.STALE_UPSTREAM in code_set(report.issues)


def test_source_change_fails_before_classification(tmp_path: Path) -> None:
    owned = tmp_path / "owned"
    _ = shutil.copytree(ROOT, owned, ignore=shutil.ignore_patterns("tooling", "evidence"))
    source = owned / "upstream/FirTree.kt"
    _ = source.write_text(source.read_text() + "\nval added: Element by element(Other)\n")
    report = subject.audit(owned, CATALOGUE)
    assert report.status == "FAIL"
    assert subject.Code.SOURCE_CHANGED in code_set(report.issues)


def test_accounting_and_version_correspondence_are_separate() -> None:
    ordinary = subject.audit(ROOT, CATALOGUE, evidence=recorded_evidence())
    assert ordinary.status == "PASS"
    assert ordinary.element_count == 164
    assert ordinary.fixture_source_count == 1332
    strict = subject.audit(
        ROOT, CATALOGUE, gate=subject.InventoryGate.EXACT_SOURCE, evidence=recorded_evidence()
    )
    assert strict.status == "FAIL"
    assert code_set(strict.issues) == {
        subject.Code.CORRESPONDENCE_UNPROVED,
        subject.Code.LIVE_DISTRIBUTION_REQUIRED,
    }


def test_exact_source_label_without_producer_evidence_cannot_pass(tmp_path: Path) -> None:
    owned = tmp_path / "owned"
    _ = shutil.copytree(ROOT, owned, ignore=shutil.ignore_patterns("tooling", "evidence"))
    pin = owned / "pin.toml"
    _ = pin.write_text(
        pin.read_text().replace(
            'correspondence = "ELEMENT_POPULATION_ONLY"', 'correspondence = "EXACT_SOURCE"'
        )
    )
    report = subject.audit(
        owned, CATALOGUE, gate=subject.InventoryGate.EXACT_SOURCE, evidence=recorded_evidence()
    )
    assert report.status == "FAIL"
    assert subject.Code.CORRESPONDENCE_UNPROVED in code_set(report.issues)


def test_success_requires_exact_result_population() -> None:
    required = (case("R1"), case("R2"))
    observed = tuple(
        subject.TestResult(test_result_id=item.test_result_id, status=subject.ResultStatus.PASSED)
        for item in required
    )
    assert subject.assess_execution(required, observed).status == "PASS"
    missing = subject.assess_execution(required, observed[1:])
    assert missing.status == "FAIL"
    assert subject.Code.MISSING_RESULT in code_set(missing.issues)
    assert missing.results[0].status == subject.ResultStatus.NOT_RUN
    extra = (
        *observed,
        subject.TestResult(test_result_id="Unselected#other", status=subject.ResultStatus.PASSED),
    )
    assert subject.Code.UNEXPECTED_RESULT in code_set(
        subject.assess_execution(required, extra).issues
    )
    assert subject.Code.DUPLICATE_RESULT in code_set(
        subject.assess_execution(required, (*observed, observed[0])).issues
    )


def test_zero_results_and_zero_required_population_fail() -> None:
    report = subject.assess_execution((case(),), ())
    assert report.status == "FAIL"
    assert subject.Code.ZERO_RESULTS in code_set(report.issues)
    assert report.results[0].status == subject.ResultStatus.NOT_RUN
    assert subject.Code.EMPTY_REQUIRED_CASES in code_set(subject.assess_execution((), ()).issues)


def test_nonpass_results_never_count_as_success() -> None:
    for status in (
        subject.ResultStatus.FAIL,
        subject.ResultStatus.ERROR,
        subject.ResultStatus.SKIP,
        subject.ResultStatus.NOT_RUN,
    ):
        required = (case(),)
        report = subject.assess_execution(
            required,
            (
                subject.TestResult(
                    test_result_id=required[0].test_result_id,
                    status=status,
                    detail="independent control",
                ),
            ),
        )
        assert report.status == "FAIL"
        assert report.results[0].status == status
        assert subject.Code.NONPASS_RESULT in code_set(report.issues)


def test_shared_test_result_ids_cannot_prove_distinct_cases() -> None:
    first = case("R1")
    second = subject.RequiredCase(case_id="R2", test_result_id=first.test_result_id)
    report = subject.assess_execution(
        (first, second),
        (
            subject.TestResult(
                test_result_id=first.test_result_id, status=subject.ResultStatus.PASSED
            ),
        ),
    )
    assert report.status == "FAIL"
    assert subject.Code.DUPLICATE_CASE in code_set(report.issues)


def test_junit_preserves_skips_failures_and_runner_errors() -> None:
    xml = '<testsuite><testcase classname="C" name="pass"/><testcase classname="C" name="fail"><failure message="edge omitted"/></testcase><testcase classname="C" name="error"><error message="setup unavailable"/></testcase><testcase classname="C" name="skip"><skipped message="platform unavailable"/></testcase></testsuite>'
    parsed = subject.parse_junit(xml, "owned control")
    assert isinstance(parsed, subject.Accepted)
    assert [(row.test_result_id, row.status) for row in parsed.value] == [
        ("C#pass", subject.ResultStatus.PASSED),
        ("C#fail", subject.ResultStatus.FAIL),
        ("C#error", subject.ResultStatus.ERROR),
        ("C#skip", subject.ResultStatus.SKIP),
    ]
    assert parsed.value[2].detail == "setup unavailable"


def test_junit_zero_population_is_not_a_pass() -> None:
    parsed = subject.parse_junit('<testsuite tests="0"/>', "zero control")
    assert isinstance(parsed, subject.Accepted)
    assert subject.assess_execution((case(),), parsed.value).status == "FAIL"
    suite_error = subject.parse_junit(
        '<testsuite><error message="setup failed"/></testsuite>', "setup control"
    )
    assert isinstance(suite_error, subject.Rejected)
    assert subject.Code.SUITE_ERROR in code_set(suite_error.issues)


def test_junit_conflicting_and_malformed_shapes_fail() -> None:
    for xml in (
        "<other/>",
        '<testsuite><testcase name="missing-class"/></testsuite>',
        '<testsuite><testcase classname="C" name="n"><failure/><skipped/></testcase></testsuite>',
        "<testsuite>",
    ):
        assert isinstance(subject.parse_junit(xml, "shape control"), subject.Rejected)


def test_real_execution_adapter_owns_only_literal_paths(tmp_path: Path) -> None:
    manifest = tmp_path / "manifest $(literal); with spaces.tsv"
    _ = manifest.write_text("case_id\ttest_result_id\nR1\tC#n\n")
    report_path = tmp_path / "result $(literal); with spaces.xml"
    _ = report_path.write_text('<testsuite><testcase classname="C" name="n"/></testsuite>')
    assert subject.execution(manifest, (report_path,)).status == "PASS"
    assert subject.execution(manifest, (tmp_path / "missing.xml",)).status == "FAIL"


def test_evidence_needs_passing_exact_execution_population() -> None:
    mappings = tuple(row for row in classifications() if row.element_id != "FirBlock")
    own_elements = tuple(row for row in elements() if row.element_id != "FirBlock")
    first = mappings[0].model_copy(
        update={"disposition": subject.Disposition.TRAVERSAL_ONLY, "case_ids": "R1"}
    )
    declared = (case("R1"),)
    observed = (
        subject.TestResult(
            test_result_id=declared[0].test_result_id, status=subject.ResultStatus.PASSED
        ),
    )
    evidence = subject.case_evidence(subject.assess_execution(declared, observed))
    assert subject.assess_classifications(own_elements, (first, *mappings[1:]), evidence) == ()
    missing = subject.case_evidence(subject.assess_execution(declared, ()))
    assert subject.Code.MISSING_CASE_EVIDENCE in code_set(
        subject.assess_classifications(own_elements, (first, *mappings[1:]), missing)
    )


def test_entity_directives_fail_before_xml_parse() -> None:
    xml = '<!DOCTYPE testsuite [<!ENTITY x "content">]><testsuite><testcase classname="C" name="&x;"/></testsuite>'
    assert isinstance(subject.parse_junit(xml, "entity control"), subject.Rejected)


def test_live_distribution_is_necessary_for_element_correspondence() -> None:
    report = subject.audit(
        ROOT, CATALOGUE, gate=subject.InventoryGate.LIVE_ELEMENTS, evidence=recorded_evidence()
    )
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.LIVE_DISTRIBUTION_REQUIRED}


def test_binary_visitor_parser_rejects_empty_duplicate_and_unknown_methods() -> None:
    valid = "public abstract class org.jetbrains.kotlin.fir.visitors.FirVisitor<R,D> {\n public R visitElement(org.jetbrains.kotlin.fir.FirElement, D);\n public R visitBlock(org.jetbrains.kotlin.fir.expressions.FirBlock, D);\n}"
    parsed = subject.parse_binary_visitor(valid)
    assert isinstance(parsed, subject.Accepted)
    assert parsed.value == ("FirBlock", "FirElement")
    for output in (
        "",
        valid + "\n public R visitAgain(org.jetbrains.kotlin.fir.FirElement, D);",
        valid + "\n public R visitUnknown(java.lang.Object, D);",
    ):
        rejected = subject.parse_binary_visitor(output)
        assert isinstance(rejected, subject.Rejected)
        assert subject.Code.BINARY_POPULATION_MISMATCH in code_set(rejected.issues)


def test_live_extra_and_omitted_elements_fail_the_pure_comparison() -> None:
    pin = subject.read_pin(ROOT)
    assert isinstance(pin, subject.Accepted)
    expected = elements()
    observation = subject.DistributionObservation(
        plugin_sha256=pin.value.plugin_sha256,
        compiler_common_sha256=pin.value.compiler_common_sha256,
        plugin_version=pin.value.plugin_version,
        adapter_compiler_version=pin.value.adapter_compiler_version,
        elements=tuple(item.element_id for item in expected),
    )
    assert subject.distribution_issues(pin.value, observation, expected, required=True) == ()
    extra = observation.model_copy(update={"elements": (*observation.elements, "FirNewVariant")})
    omitted = observation.model_copy(update={"elements": observation.elements[1:]})
    for changed in (extra, omitted):
        assert subject.Code.BINARY_POPULATION_MISMATCH in code_set(
            subject.distribution_issues(pin.value, changed, expected, required=True)
        )


def test_changed_live_artifact_rejects_before_javap(tmp_path: Path) -> None:
    from zipfile import ZipFile  # noqa: PLC0415 -- the owned archive is confined to this case

    pin = subject.read_pin(ROOT)
    assert isinstance(pin, subject.Accepted)
    plugin = tmp_path / "plugin $(literal); archive.jar"
    with ZipFile(plugin, "w") as owned:
        owned.writestr(
            "META-INF/owned-control.txt", "This is a valid archive for an integrity control."
        )
    compiler = tmp_path / "compiler.jar"
    with ZipFile(compiler, "w") as owned:
        owned.writestr("META-INF/compiler.version", "2.4.20-ij262-34")

    class NoJavap:
        calls: int = 0

        def inspect(self, plugin_snapshot: Path) -> subject.Accepted[str] | subject.Rejected:
            self.calls += 1
            return subject.Rejected(
                issues=(subject.Issue(code=subject.Code.JAVAP_FAILED, detail=str(plugin_snapshot)),)
            )

    inspector = NoJavap()
    result = subject.observe_distribution(pin.value, plugin, compiler, inspector)
    assert isinstance(result, subject.Rejected)
    assert code_set(result.issues) == {subject.Code.DISTRIBUTION_CHANGED}
    assert inspector.calls == 0


def test_compiler_upgrade_rejects_even_with_unchanged_inventory(tmp_path: Path) -> None:
    changed = tmp_path / "catalogue.toml"
    _ = changed.write_text(CATALOGUE.read_text().replace('kotlin = "2.4.20"', 'kotlin = "2.4.21"'))
    report = subject.audit(ROOT, changed, evidence=recorded_evidence())
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.COMPILER_PIN_DRIFT}


def test_junit_summary_cannot_hide_failure_or_omit_cases() -> None:
    for xml in (
        '<testsuite tests="1" failures="1"><testcase classname="C" name="n"/></testsuite>',
        '<testsuite tests="2"><testcase classname="C" name="n"/></testsuite>',
        '<testsuite tests="unknown"><testcase classname="C" name="n"/></testsuite>',
    ):
        parsed = subject.parse_junit(xml, "summary control")
        assert isinstance(parsed, subject.Rejected)
        assert code_set(parsed.issues) == {subject.Code.INVALID_INPUT}


def test_future_upstream_category_has_a_finite_rejection() -> None:
    source = "val future: Element by element(FutureCategory) {}\n"
    result = subject.extract_elements(source)
    assert isinstance(result, subject.Rejected)
    assert code_set(result.issues) == {subject.Code.EXTRACTION_MISMATCH}
    assert result.issues[0].detail == "Unknown registration: future"


def owned_resource_inputs(tmp_path: Path) -> Path:
    import csv  # noqa: PLC0415 -- table serialization belongs to the owned fixture
    import hashlib  # noqa: PLC0415 -- expected digests come from declared fixture bytes

    repository = tmp_path / "repository"
    identities: list[tuple[str, str]] = []
    source = b"package fixture\nclass Target\n"
    expected_sha = hashlib.sha256(source).hexdigest()
    r1_source = f"{subject.R1_PUBLIC_RESOURCES}/r1-owned-case.kt"
    r1_control = f"{subject.R1_PUBLIC_RESOURCES}/r1-owned-control.kt"
    r2_source = f"{subject.R2_PUBLIC_RESOURCES}/R2_owned_case.kt"
    r4_sources = (
        f"{subject.R2_PUBLIC_RESOURCES}/R4_bare_constructor_reference.kt",
        f"{subject.R2_PUBLIC_RESOURCES}/R4_uninvoked_outer_lambda.kt",
    )
    for artifact in (*subject.R1_SHARED_INPUTS, r1_source, r1_control, r2_source, *r4_sources):
        path = repository / artifact
        path.parent.mkdir(parents=True, exist_ok=True)
        _ = path.write_bytes(source)
        if not artifact.startswith(subject.R2_PUBLIC_RESOURCES + "/"):
            identities.append((artifact, expected_sha))
    paths = (
        (
            repository / subject.R1_ADAPTER_RESOURCES / "input-identities.tsv",
            ("artifact", "sha256"),
            identities,
        ),
        (
            repository / subject.R1_PUBLIC_RESOURCES / "case-manifest.tsv",
            ("case_id", "public_source_sha256"),
            [("r1-owned-case", expected_sha)],
        ),
        (
            repository / subject.R1_PUBLIC_RESOURCES / "control-manifest.tsv",
            ("case_id", "public_source_sha256"),
            [("r1-owned-control", expected_sha)],
        ),
        (
            repository / subject.R2_PUBLIC_RESOURCES / "manifest.tsv",
            ("case_id", "resource", "sha256"),
            [("R2_owned_case", "semantic-owners/R2_owned_case.kt", expected_sha)],
        ),
    )
    paths = (
        *paths,
        (
            repository / "config/kotlin-semantic-coverage/r4-resource-identities.tsv",
            ("artifact", "sha256"),
            [(source, expected_sha) for source in r4_sources],
        ),
    )
    for path, header, rows in paths:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", newline="", encoding="utf-8") as output:
            writer = csv.writer(output, delimiter="\t", lineterminator="\n")
            writer.writerow(header)
            writer.writerows(rows)
    return repository


def test_actual_53_resources_match_the_independent_manifests() -> None:
    repository = Path(__file__).resolve().parents[3]
    report = subject.resource_input_guard(repository)
    assert report.status == "PASS"
    assert report.required_resource_count == 53
    assert report.observed_resource_count == 53
    assert report.issues == ()


def test_owned_resource_guard_accepts_declared_inputs(tmp_path: Path) -> None:
    report = subject.resource_input_guard(owned_resource_inputs(tmp_path))
    assert report.status == "PASS"
    assert report.required_resource_count == 11
    assert report.observed_resource_count == 11


def test_resource_guard_rejects_valid_utf8_kotlin_source_drift(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    target = repository / subject.R1_PUBLIC_RESOURCES / "r1-owned-case.kt"
    _ = target.write_text("package fixture\nclass ChangedTarget\n")
    report = subject.resource_input_guard(repository)
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.RESOURCE_CHANGED}
    assert report.issues[0].detail == f"{subject.R1_PUBLIC_RESOURCES}/r1-owned-case.kt"


def test_resource_guard_rejects_missing_source_and_mapping(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    (repository / subject.R2_PUBLIC_RESOURCES / "R2_owned_case.kt").unlink()
    missing_source = subject.resource_input_guard(repository)
    assert missing_source.status == "FAIL"
    assert subject.Code.RESOURCE_MISSING in code_set(missing_source.issues)
    identities = repository / subject.R1_ADAPTER_RESOURCES / "input-identities.tsv"
    rows = identities.read_text().splitlines()
    _ = identities.write_text("\n".join(rows[:-1]) + "\n")
    missing_mapping = subject.resource_input_guard(repository)
    assert subject.Code.RESOURCE_MISSING in code_set(missing_mapping.issues)


def test_resource_guard_rejects_duplicate_identity(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    identities = repository / subject.R1_ADAPTER_RESOURCES / "input-identities.tsv"
    raw = identities.read_text()
    _ = identities.write_text(raw + raw.splitlines()[1] + "\n")
    report = subject.resource_input_guard(repository)
    assert report.status == "FAIL"
    assert subject.Code.RESOURCE_DUPLICATE in code_set(report.issues)


def test_resource_guard_rejects_unknown_identity_without_a_case(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    identities = repository / subject.R1_ADAPTER_RESOURCES / "input-identities.tsv"
    _ = identities.write_text(
        identities.read_text() + f"{subject.R1_PUBLIC_RESOURCES}/unknown-required.kt\t{'0' * 64}\n"
    )
    report = subject.resource_input_guard(repository)
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.RESOURCE_UNEXPECTED}


def test_resource_guard_rejects_extra_file_and_omitted_case(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    extra = repository / subject.R2_PUBLIC_RESOURCES / "extra-required.kt"
    _ = extra.write_text("package fixture\nclass Extra\n")
    report = subject.resource_input_guard(repository)
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.RESOURCE_UNEXPECTED}
    cases = repository / subject.R1_PUBLIC_RESOURCES / "case-manifest.tsv"
    _ = cases.write_text(cases.read_text().splitlines()[0] + "\n")
    empty = subject.resource_input_guard(repository)
    assert empty.status == "FAIL"
    assert code_set(empty.issues) == {subject.Code.EMPTY_REQUIRED_CASES}


def test_resource_guard_rejects_source_hash_alias_between_manifests(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    cases = repository / subject.R1_PUBLIC_RESOURCES / "case-manifest.tsv"
    raw = cases.read_text().splitlines()
    _ = cases.write_text(raw[0] + "\nr1-owned-case\t" + "0" * 64 + "\n")
    report = subject.resource_input_guard(repository)
    assert report.status == "FAIL"
    assert {subject.Code.RESOURCE_IDENTITY_MISMATCH, subject.Code.RESOURCE_CHANGED}.issubset(
        code_set(report.issues)
    )


def test_assertion_carrier_edits_do_not_change_resource_identity(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    carrier = repository / "runtime/hosted/src/nativeTest/kotlin/Assertions.kt"
    carrier.parent.mkdir(parents=True)
    _ = carrier.write_text("fun assertOld() = Unit\n")
    before = subject.resource_input_guard(repository)
    _ = carrier.write_text("fun assertMorePrecisely() = Unit\n")
    assert subject.resource_input_guard(repository) == before
    assert before.status == "PASS"


def test_assertion_carrier_cannot_enter_the_semantic_resource_population(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    identities = repository / subject.R1_ADAPTER_RESOURCES / "input-identities.tsv"
    _ = identities.write_text(
        identities.read_text() + f"runtime/hosted/src/nativeTest/kotlin/Assertions.kt\t{'0' * 64}\n"
    )
    report = subject.resource_input_guard(repository)
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.INVALID_INPUT}


def reviewed_carrier(repository: Path) -> tuple[Path, subject.ReviewedCarriers]:
    import hashlib  # noqa: PLC0415 -- independent expected hash for a declared carrier

    source = repository / "runtime/hosted/src/nativeTest/kotlin/Requests.kt"
    source.parent.mkdir(parents=True, exist_ok=True)
    _ = source.write_text("val fixedLimit = 100\n")
    manifest = repository / "reviewed-carriers.tsv"
    _ = manifest.write_text(
        f"artifact\tsha256\nruntime/hosted/src/nativeTest/kotlin/Requests.kt\t{hashlib.sha256(source.read_bytes()).hexdigest()}\n"
    )
    parsed = subject.read_carrier_review(manifest)
    assert isinstance(parsed, subject.Accepted)
    return manifest, parsed.value


def test_reviewed_carrier_matches_its_declared_bytes(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    _, carriers = reviewed_carrier(repository)
    report = subject.resource_input_guard(repository, carriers)
    assert report.status == "PASS"
    assert report.reviewed_carrier_count == 1


def test_changed_reviewed_request_carrier_fails(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    _, carriers = reviewed_carrier(repository)
    source = repository / "runtime/hosted/src/nativeTest/kotlin/Requests.kt"
    _ = source.write_text("val fixedLimit = 101\n")
    report = subject.resource_input_guard(repository, carriers)
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.CARRIER_CHANGED}


def test_missing_reviewed_carrier_fails(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    _, carriers = reviewed_carrier(repository)
    (repository / "runtime/hosted/src/nativeTest/kotlin/Requests.kt").unlink()
    report = subject.resource_input_guard(repository, carriers)
    assert report.status == "FAIL"
    assert code_set(report.issues) == {subject.Code.CARRIER_MISSING}


def test_duplicate_and_empty_reviewed_carrier_tables_fail(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    manifest, _ = reviewed_carrier(repository)
    raw = manifest.read_text()
    _ = manifest.write_text(raw + raw.splitlines()[1] + "\n")
    duplicate = subject.read_carrier_review(manifest)
    assert isinstance(duplicate, subject.Rejected)
    assert code_set(duplicate.issues) == {subject.Code.CARRIER_DUPLICATE}
    _ = manifest.write_text("artifact\tsha256\n")
    empty = subject.read_carrier_review(manifest)
    assert isinstance(empty, subject.Rejected)
    assert code_set(empty.issues) == {subject.Code.EMPTY_REQUIRED_CASES}


def test_resource_guard_rejects_duplicate_r2_case_and_unknown_r4_identity(tmp_path: Path) -> None:
    repository = owned_resource_inputs(tmp_path)
    owners = repository / subject.R2_PUBLIC_RESOURCES / "manifest.tsv"
    raw = owners.read_text()
    _ = owners.write_text(raw + raw.splitlines()[1] + "\n")
    duplicate = subject.resource_input_guard(repository)
    assert duplicate.status == "FAIL"
    assert {subject.Code.DUPLICATE_CASE, subject.Code.RESOURCE_DUPLICATE}.issubset(
        code_set(duplicate.issues)
    )
    _ = owners.write_text(raw)
    r4 = repository / "config/kotlin-semantic-coverage/r4-resource-identities.tsv"
    _ = r4.write_text(r4.read_text() + f"{subject.R2_PUBLIC_RESOURCES}/R4_unknown.kt\t{'0' * 64}\n")
    unexpected = subject.resource_input_guard(repository)
    assert unexpected.status == "FAIL"
    assert subject.Code.RESOURCE_UNEXPECTED in code_set(unexpected.issues)
