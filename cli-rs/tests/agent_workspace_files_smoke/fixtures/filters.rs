fn seed_structured_filter_evidence(index: &workspace_files::WorkspaceIndexFixture) {
    let connection = index.connection();
    connection
        .execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (2, 'sample.target'), (3, 'sample.other')",
            [],
        )
        .expect("filter package names");
    for (filename, package_id, legacy_module, legacy_source_set, project, source_set) in [
        (
            "Good.kt",
            Some(2),
            None,
            None,
            Some(":app"),
            Some("integrationTest"),
        ),
        (
            "WrongModule.kt",
            Some(2),
            None,
            None,
            Some(":other"),
            Some("integrationTest"),
        ),
        (
            "WrongSourceSet.kt",
            Some(2),
            None,
            None,
            Some(":app"),
            Some("main"),
        ),
        (
            "WrongPackage.kt",
            Some(3),
            None,
            None,
            Some(":app"),
            Some("integrationTest"),
        ),
        (
            "LegacyOnly.kt",
            None,
            Some("gradle:.#:app"),
            Some("integrationTest"),
            None,
            None,
        ),
    ] {
        index.insert_manifest_file(1, "src/main/kotlin/sample", filename, true);
        if let Some(package_id) = package_id {
            connection
                .execute(
                    "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (1, ?, ?, 'PROVEN_NAMED', NULL, ?, ?)",
                    rusqlite::params![filename, package_id, legacy_module, legacy_source_set],
                )
                .expect("proven filter metadata");
        } else {
            connection
                .execute(
                    "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (1, ?, NULL, 'UNPROVEN', 'LEGACY_TEXT_ONLY', ?, ?)",
                    rusqlite::params![filename, legacy_module, legacy_source_set],
                )
                .expect("legacy-only filter metadata");
        }
        if let (Some(project), Some(source_set)) = (project, source_set) {
            index.insert_project_evidence(1, filename, ".", project, source_set);
        }
    }
    index.seed_progress("app", "COMPLETE", 5, 5);
}

fn grouped_cardinality<'a>(
    output: &'a serde_json::Value,
    group: &str,
    value: &str,
) -> &'a serde_json::Value {
    output["result"]["groupedCardinalities"][group]
        .as_array()
        .expect("grouped cardinalities")
        .iter()
        .find(|entry| entry["value"] == value)
        .unwrap_or_else(|| panic!("missing {group}={value} group: {output:#}"))
}
