pub(crate) fn seed_high_cardinality_impact(
    workspace: &std::path::Path,
    target_fq_name: &str,
    source_count: usize,
) {
    let db_path = workspace_database_path_for_test(workspace);
    let mut conn = Connection::open(db_path).expect("sqlite");
    let target_fq_id: i64 = conn
        .query_row(
            "SELECT fq_id FROM fq_names WHERE fq_name = ?",
            params![target_fq_name],
            |row| row.get(0),
        )
        .expect("impact target fq id");
    let tx = conn.transaction().expect("impact seed transaction");
    for index in 0..source_count {
        let fq_id = 1_000 + i64::try_from(index).expect("impact fq id");
        let fq_name = format!("app.ImpactSource{index:04}");
        let filename = format!("ImpactSource{index:04}.kt");
        tx.execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
            params![fq_id, fq_name],
        )
        .expect("impact fq name");
        tx.execute(
            "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (1, ?, ':app', 'main')",
            params![filename],
        )
        .expect("impact file metadata");
        tx.execute(
            "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (1, ?, 1)",
            params![filename],
        )
        .expect("impact file manifest");
        tx.execute(
            "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, 'CLASS', 'PUBLIC', 1, ?, 1, ':app', 'main')",
            params![fq_id, filename],
        )
        .expect("impact declaration");
        tx.execute(
            "INSERT INTO symbol_references(src_prefix_id, src_filename, source_offset, source_fq_id, target_fq_id, tgt_prefix_id, tgt_filename, target_offset, edge_kind) VALUES (1, ?, 1, ?, ?, 2, 'Foo.kt', 1, 'CALL')",
            params![filename, fq_id, target_fq_id],
        )
        .expect("impact reference");
    }
    tx.commit().expect("impact seed commit");
}

pub(crate) fn seed_exact_lookup_symbols(workspace: &std::path::Path) {
    let db_path = workspace_database_path_for_test(workspace);
    let conn = Connection::open(db_path).expect("sqlite");
    for (id, fq_name, filename, kind) in [
        (20, "sample.when", "Keywords.kt", "FUNCTION"),
        (21, "alpha.Parser", "AlphaParser.kt", "CLASS"),
        (22, "beta.Parser", "BetaParser.kt", "CLASS"),
        (
            23,
            "sample.MissingOrderServiceLegacy",
            "MissingOrderServiceLegacy.kt",
            "CLASS",
        ),
    ] {
        std::fs::write(
            workspace.join("lib").join(filename),
            format!("// {fq_name}\n"),
        )
        .expect("exact lookup source");
        conn.execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
            params![id, fq_name],
        )
        .expect("exact lookup fq name");
        conn.execute(
            "INSERT INTO file_metadata(prefix_id, filename, module_path, source_set) VALUES (2, ?, ':lib', 'main')",
            params![filename],
        )
        .expect("exact lookup file metadata");
        conn.execute(
            "INSERT INTO file_manifest(prefix_id, filename, last_modified_millis) VALUES (2, ?, 1)",
            params![filename],
        )
        .expect("exact lookup file manifest");
        conn.execute(
            "INSERT INTO declarations(fq_id, kind, visibility, prefix_id, filename, declaration_offset, module_path, source_set) VALUES (?, ?, 'PUBLIC', 2, ?, 1, ':lib', 'main')",
            params![id, kind, filename],
        )
        .expect("exact lookup declaration");
    }
}

pub(crate) fn source_index_schema_version() -> i64 {
    env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse()
        .expect("numeric source_index_schema_version")
}

pub(crate) fn seed_source_files(workspace: &std::path::Path) {
    std::fs::create_dir_all(workspace.join("app")).expect("app sources");
    std::fs::create_dir_all(workspace.join("lib")).expect("lib sources");
    std::fs::create_dir_all(workspace.join("lib/test")).expect("lib test sources");
    std::fs::create_dir_all(workspace.join("build-logic")).expect("build logic sources");
    std::fs::create_dir_all(workspace.join("lib/payments")).expect("lib payments sources");
    std::fs::create_dir_all(
        workspace.join("analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing"),
    )
    .expect("analysis-api test fixtures sources");
    std::fs::write(
        workspace.join("app/A.kt"),
        r#"package app

import lib.Bar
import lib.Foo

class A {
    fun render() {
        Foo()
        Bar()
    }
}
"#,
    )
    .expect("A.kt");
    std::fs::write(
        workspace.join("app/B.kt"),
        r#"package app

class B {
    fun touch(a: A) {
        a.render()
    }
}
"#,
    )
    .expect("B.kt");
    std::fs::write(
        workspace.join("app/Unused.kt"),
        r#"package app

private fun Unused() = Unit
"#,
    )
    .expect("Unused.kt");
    std::fs::write(
        workspace.join("lib/Foo.kt"),
        r#"package lib

class Foo
"#,
    )
    .expect("Foo.kt");
    std::fs::write(
        workspace.join("lib/Bar.kt"),
        r#"package lib

internal fun Bar() = Unit
"#,
    )
    .expect("Bar.kt");
    std::fs::write(
        workspace.join("lib/FooWidget.kt"),
        r#"package lib

class FooWidget
"#,
    )
    .expect("FooWidget.kt");
    std::fs::write(workspace.join("lib/FooNotes.md"), "# FooNotes\n").expect("FooNotes.md");
    std::fs::write(
        workspace.join("lib/CardPaymentProcessor.kt"),
        r#"package lib

class CardPaymentProcessor
"#,
    )
    .expect("CardPaymentProcessor.kt");
    std::fs::write(
        workspace.join("lib/test/CardPaymentProcessorTest.kt"),
        r#"package lib

class CardPaymentProcessorTest
"#,
    )
    .expect("CardPaymentProcessorTest.kt");
    std::fs::write(
        workspace.join("build-logic/BuildPaymentProcessor.kt"),
        r#"package buildlogic

class BuildPaymentProcessor
"#,
    )
    .expect("BuildPaymentProcessor.kt");
    std::fs::write(
        workspace.join("lib/payments/PaymentBridge.kt"),
        r#"package lib.payments

class PaymentBridge
"#,
    )
    .expect("PaymentBridge.kt");
    std::fs::write(
        workspace.join("analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt"),
        r#"package io.github.amichne.kast.testing

class FakeAnalysisBackend
"#,
    )
    .expect("FakeAnalysisBackend.kt");
}
