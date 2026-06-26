use serde_json::Value;
use std::collections::BTreeSet;
use std::path::Path;
use std::process::Command;

fn catalog() -> Value {
    serde_json::from_str(include_str!(
        "../resources/kast-skill/references/commands.json"
    ))
    .expect("commands catalog")
}

fn request_required(request: &Value) -> impl Iterator<Item = &str> {
    request
        .get("required")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .map(|value| value.as_str().expect("required field name"))
}

fn assert_field_shape(field: &Value) {
    let field_type = field
        .get("type")
        .and_then(Value::as_str)
        .expect("field type");
    assert!(
        matches!(
            field_type,
            "array" | "boolean" | "integer" | "object" | "string"
        ),
        "unsupported field type: {field_type}"
    );
    if let Some(items) = field.get("items") {
        if items.is_object() {
            assert_field_shape(items);
        } else {
            assert!(items.is_string(), "items must be a primitive name or field");
        }
    }
    if let Some(fields) = field.get("fields") {
        assert_fields_shape(fields);
        let field_names: BTreeSet<_> = fields
            .as_object()
            .expect("nested fields object")
            .keys()
            .map(String::as_str)
            .collect();
        for required in request_required(field) {
            assert!(
                field_names.contains(required),
                "nested required field {required} must be declared"
            );
        }
    }
}

fn assert_fields_shape(fields: &Value) {
    let fields = fields.as_object().expect("fields object");
    for field in fields.values() {
        assert_field_shape(field);
    }
}

fn assert_request_shape(request: &Value) {
    let fields = request.get("fields").expect("request fields");
    assert_fields_shape(fields);
    let field_names: BTreeSet<_> = fields
        .as_object()
        .expect("fields object")
        .keys()
        .map(String::as_str)
        .collect();
    for required in request_required(request) {
        assert!(
            field_names.contains(required),
            "required field {required} must be declared"
        );
    }
}

fn schema_value(relative_path: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let path = root.join(relative_path);
    let content = std::fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("read schema {}: {error}", path.display()));
    serde_json::from_str(&content)
        .unwrap_or_else(|error| panic!("parse schema {}: {error}", path.display()))
}

fn request_path(root: &Path, catalog: &Value, method: &str) -> std::path::PathBuf {
    let category = catalog["commands"][method]["category"]
        .as_str()
        .unwrap_or_else(|| panic!("{method} category"));
    let mut parts = method.split('/');
    match parts.next() {
        Some(first) if first == category => {
            parts.fold(root.join(category), |base, part| base.join(part))
        }
        _ => method
            .split('/')
            .fold(root.join(category), |base, part| base.join(part)),
    }
}

fn collect_named_files(root: &Path, file_name: &str, paths: &mut Vec<std::path::PathBuf>) {
    let entries = std::fs::read_dir(root)
        .unwrap_or_else(|error| panic!("read directory {}: {error}", root.display()));
    for entry in entries {
        let entry =
            entry.unwrap_or_else(|error| panic!("read entry in {}: {error}", root.display()));
        let path = entry.path();
        if path.is_dir() {
            collect_named_files(&path, file_name, paths);
        } else if path.file_name().and_then(|name| name.to_str()) == Some(file_name) {
            paths.push(path);
        }
    }
}

fn assert_valid(schema: &Value, instance: &Value) {
    let validator = jsonschema::validator_for(schema).expect("schema compiles");
    if let Err(error) = validator.validate(instance) {
        panic!("schema validation failed: {error}\ninstance: {instance}");
    }
}

#[test]
fn command_contract_yaml_and_request_samples_are_current() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    assert!(
        root.join("resources/kast-skill/references/commands.yaml")
            .is_file()
    );
    assert!(
        root.join("resources/kast-skill/references/requests/raw/workspace-symbol/minimal.json")
            .is_file()
    );
    assert!(
        root.join("resources/kast-skill/references/requests/symbol/rename/RENAME_BY_OFFSET_REQUEST/maximal.json")
            .is_file()
    );
    assert!(
        root.join("resources/kast-skill/references/requests/symbol/query/request.schema.json")
            .is_file()
    );

    let generator = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(root)
        .args(["generate", "contract", "--check"])
        .output()
        .expect("contract generator check");
    assert!(
        generator.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&generator.stdout),
        String::from_utf8_lossy(&generator.stderr)
    );

    let validator = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(root)
        .args(["validate", "--all-samples"])
        .output()
        .expect("request sample validation");
    assert!(
        validator.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&validator.stdout),
        String::from_utf8_lossy(&validator.stderr)
    );
}

#[test]
fn generated_request_schemas_validate_every_catalog_sample() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    let requests_root = root.join("resources/kast-skill/references/requests");
    let mut schema_paths = Vec::new();
    collect_named_files(&requests_root, "request.schema.json", &mut schema_paths);
    assert_eq!(
        schema_paths.len(),
        commands.len(),
        "each command should have exactly one generated request schema"
    );

    let mut sample_paths = Vec::new();
    collect_named_files(&requests_root, "minimal.json", &mut sample_paths);
    collect_named_files(&requests_root, "maximal.json", &mut sample_paths);
    sample_paths.sort();
    assert_eq!(
        sample_paths.len(),
        68,
        "31 commands currently expand to 68 minimal/maximal sample payloads"
    );

    for path in sample_paths {
        let request: Value = serde_json::from_str(
            &std::fs::read_to_string(&path)
                .unwrap_or_else(|error| panic!("read sample {}: {error}", path.display())),
        )
        .unwrap_or_else(|error| panic!("parse sample {}: {error}", path.display()));
        let method = request["method"]
            .as_str()
            .unwrap_or_else(|| panic!("sample {} should include method", path.display()));
        let schema_path =
            request_path(&requests_root, &catalog, method).join("request.schema.json");
        let schema: Value = serde_json::from_str(
            &std::fs::read_to_string(&schema_path)
                .unwrap_or_else(|error| panic!("read schema {}: {error}", schema_path.display())),
        )
        .unwrap_or_else(|error| panic!("parse schema {}: {error}", schema_path.display()));
        assert_valid(&schema, &request);
    }
}

#[test]
fn command_catalog_is_schema_backed_and_self_consistent() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    assert!(
        root.join("resources/kast-skill/references/commands.schema.json")
            .is_file()
    );

    let catalog = catalog();
    assert_eq!(catalog["$schema"], "./commands.schema.json");

    let commands = catalog["commands"].as_object().expect("commands object");
    let categories = catalog["categories"]
        .as_object()
        .expect("categories object");
    let mut categorized_methods = BTreeSet::new();
    for (category, methods) in categories {
        for method in methods.as_array().expect("category method list") {
            let method = method.as_str().expect("method name");
            categorized_methods.insert(method);
            let command = commands
                .get(method)
                .unwrap_or_else(|| panic!("category references missing method {method}"));
            assert_eq!(command["category"], *category);
        }
    }

    for (method, command) in commands {
        assert_eq!(command["method"], *method);
        assert!(
            categorized_methods.contains(method.as_str()),
            "method {method} must be listed in a category"
        );
        assert_request_shape(&command["request"]);
        if let Some(variants) = command.get("variants").and_then(Value::as_object) {
            assert!(
                command["request"]["fields"].get("value").is_none(),
                "variant request {method} must not use an untyped value envelope"
            );
            let type_enum: BTreeSet<_> = command["request"]["fields"]["type"]["enum"]
                .as_array()
                .expect("variant type enum")
                .iter()
                .map(|value| value.as_str().expect("variant name"))
                .collect();
            let variant_names: BTreeSet<_> = variants.keys().map(String::as_str).collect();
            assert_eq!(type_enum, variant_names);
            for request in variants.values() {
                assert_request_shape(request);
            }
        }
    }
}

#[test]
fn symbol_query_catalog_documents_relevance_filters() {
    let catalog = catalog();
    let filters = &catalog["commands"]["symbol/query"]["request"]["fields"]["filters"]["fields"];
    for (field, expected_note) in [
        ("gradleProject", "Gradle project path"),
        ("relativePathPrefix", "workspace-root-relative"),
        ("productionOnly", "sourceSet=main"),
        ("excludePatterns", "module path or relative path"),
        ("usageFacets", "computed declaration facets"),
    ] {
        assert!(
            filters.get(field).is_some(),
            "symbol/query filters should document {field}"
        );
        let note = filters[field]["description"]
            .as_str()
            .unwrap_or_else(|| panic!("symbol/query filter {field} should include a description"));
        assert!(
            note.contains(expected_note),
            "symbol/query filter {field} description should mention {expected_note}: {note}"
        );
    }

    let usage_facets = filters["usageFacets"]["items"]["enum"]
        .as_array()
        .expect("usage facet enum");
    for facet in [
        "PUBLIC_API",
        "INTERNAL_API",
        "MODULE_PRIVATE",
        "BRIDGE",
        "BUILD_LOGIC",
    ] {
        assert!(
            usage_facets.iter().any(|value| value == facet),
            "usageFacets should include {facet}"
        );
    }

    let maximal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/symbol/query/maximal.json"
    ))
    .expect("symbol/query maximal request");
    let maximal_filters = &maximal["params"]["filters"];
    for field in [
        "gradleProject",
        "relativePathPrefix",
        "productionOnly",
        "excludePatterns",
        "usageFacets",
    ] {
        assert!(
            maximal_filters.get(field).is_some(),
            "symbol/query maximal request should include {field}"
        );
    }
}

#[test]
fn symbol_query_catalog_samples_validate_against_shared_schema() {
    let request_schema = schema_value(
        "../analysis-api/src/main/resources/contracts/symbol-query/symbol-query-request.schema.json",
    );
    let generated_request_schema =
        schema_value("resources/kast-skill/references/requests/symbol/query/request.schema.json");
    let canonical_minimal: Value = serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-minimal.json"
    ))
    .expect("canonical minimal request");
    let canonical_maximal: Value = serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-maximal.json"
    ))
    .expect("canonical maximal request");
    let catalog_minimal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/symbol/query/minimal.json"
    ))
    .expect("catalog minimal request");
    let catalog_maximal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/symbol/query/maximal.json"
    ))
    .expect("catalog maximal request");

    assert_valid(&request_schema, &canonical_minimal);
    assert_valid(&request_schema, &canonical_maximal);
    assert_valid(&request_schema, &catalog_minimal);
    assert_valid(&request_schema, &catalog_maximal);
    assert_valid(&generated_request_schema, &canonical_minimal);
    assert_valid(&generated_request_schema, &canonical_maximal);
    assert_valid(&generated_request_schema, &catalog_minimal);
    assert_valid(&generated_request_schema, &catalog_maximal);
    assert_eq!(catalog_minimal, canonical_minimal);
    assert_eq!(catalog_maximal, canonical_maximal);
}

#[test]
fn workspace_files_tool_is_documented_as_secondary_and_bounded() {
    let catalog = catalog();
    let workspace_files = &catalog["commands"]["raw/workspace-files"];
    let description = workspace_files["tool"]["description"]
        .as_str()
        .expect("workspace files tool description");

    assert!(
        description.contains("Secondary"),
        "workspace files should be presented as secondary guidance: {description}"
    );
    assert!(
        description.contains("Prefer symbol/query"),
        "workspace files should steer agents to symbol/query first: {description}"
    );
    assert!(
        description.contains("includeFiles=false"),
        "workspace files should advertise the bounded default: {description}"
    );

    let maximal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/raw/workspace-files/maximal.json"
    ))
    .expect("workspace-files maximal request");
    assert_eq!(maximal["params"]["includeFiles"], Value::Bool(true));
    assert_eq!(
        maximal["params"]["moduleName"],
        Value::String(":analysis-api".to_string())
    );
    assert_eq!(
        maximal["params"]["maxFilesPerModule"],
        Value::Number(25.into())
    );
}

#[test]
fn command_catalog_owns_copilot_tool_surface() {
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    let tool_names: BTreeSet<_> = commands
        .values()
        .filter_map(|command| command.get("tool"))
        .map(|tool| tool["name"].as_str().expect("tool name"))
        .collect();
    let expected = BTreeSet::from([
        "kast_callers",
        "kast_diagnostics",
        "kast_file_outline",
        "kast_metrics",
        "kast_references",
        "kast_rename",
        "kast_resolve",
        "kast_scaffold",
        "kast_symbol_discover",
        "kast_workspace_files",
        "kast_workspace_search",
        "kast_workspace_symbol",
        "kast_write_and_validate",
    ]);
    assert_eq!(tool_names, expected);
}

#[test]
fn copilot_plugin_source_stays_inside_cli_resources_plugin() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let plugin_root = manifest_dir.join("resources/plugin");
    let manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(plugin_root.join("plugin.json")).expect("plugin manifest"),
    )
    .expect("plugin manifest json");
    assert_eq!(
        manifest["name"],
        Value::String("kast-copilot-lsp".to_string())
    );
    assert!(
        plugin_root.join("lsp.json").is_file(),
        "plugin source must own the LSP entrypoint"
    );

    let primitive_schema: Value = serde_json::from_str(
        &std::fs::read_to_string(plugin_root.join("primitive-manifest.schema.json"))
            .expect("primitive manifest schema"),
    )
    .expect("primitive manifest schema json");
    let primitive_manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(plugin_root.join("primitive-manifest.json"))
            .expect("primitive manifest"),
    )
    .expect("primitive manifest json");
    assert_valid(&primitive_schema, &primitive_manifest);

    let outputs = primitive_manifest["outputs"]
        .as_array()
        .expect("primitive outputs");
    let targets: BTreeSet<_> = outputs
        .iter()
        .map(|output| output["target"].as_str().expect("output target"))
        .collect();
    assert_eq!(
        targets,
        BTreeSet::from([
            "extensions/kast/_shared/commands.json",
            "extensions/kast/_shared/kast-trace.mjs",
            "extensions/kast/_shared/kast-tools.mjs",
            "extensions/kast/extension.mjs",
            "instructions/Kotlin.instructions.md",
            "lsp.json",
        ])
    );
    assert!(
        plugin_root
            .join("instructions/Kotlin.instructions.md")
            .is_file(),
        "plugin source must distribute top-level GitHub Kotlin instructions"
    );
    assert!(
        !plugin_root
            .join("instructions/kast-kotlin.instructions.md")
            .exists(),
        "plugin source must not expose the retired static Kotlin instruction filename"
    );
    assert!(
        plugin_root.join("extensions/kast/extension.mjs").is_file(),
        "plugin source must own the catalog-backed Copilot extension entrypoint"
    );
    assert!(
        !plugin_root
            .join("extensions/kast/_shared/kast-agents.mjs")
            .exists(),
        "plugin source must not expose custom agent helpers"
    );
    let extension = std::fs::read_to_string(plugin_root.join("extensions/kast/extension.mjs"))
        .expect("extension source");
    assert!(
        !extension.contains("\"bash\""),
        "extension must resolve kast without shelling through bash"
    );
    assert!(
        extension.contains("RECOVERABLE_WARMUP_CODES")
            && extension.contains("\"INDEX_UNAVAILABLE\"")
            && extension.contains("\"up\"")
            && extension.contains("createTraceEmitter"),
        "extension must warm the IDEA backend for missing backend/index results"
    );
    assert!(
        extension.contains("\"agent\"")
            && extension.contains("\"call\"")
            && extension.contains("formattedAgentResult")
            && !extension.contains("rpcArgs("),
        "extension tools must use the shared `kast agent call` envelope instead of raw rpc"
    );
    let install_local = std::fs::read_to_string(plugin_root.join("scripts/install-local.sh"))
        .expect("local plugin installer");
    assert!(
        !install_local.contains("python3"),
        "local plugin installer must delegate to Rust instead of inline Python"
    );
    let repo_root = manifest_dir.parent().expect("repo root");
    let package_contract =
        std::fs::read_to_string(repo_root.join(".github/scripts/test-kast-copilot-plugin.sh"))
            .expect("copilot plugin package contract");
    assert!(
        !package_contract.contains("python3"),
        "copilot plugin package contract must not depend on Python"
    );
    assert!(
        package_contract.contains("cargo build --manifest-path"),
        "copilot plugin package contract must supply a Rust-built kast binary"
    );
    let lsp_pivot_contract =
        std::fs::read_to_string(repo_root.join(".github/scripts/test-lsp-pivot-gates.sh"))
            .expect("LSP pivot contract");
    assert!(
        !lsp_pivot_contract.contains("python3"),
        "LSP pivot contract must not depend on Python"
    );
}

#[test]
fn copilot_install_receives_the_manifest_declared_package_outputs() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let target = temp.path().join("github");
    std::fs::create_dir_all(&home).expect("home");

    let output = Command::new(env!("CARGO_BIN_EXE_kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args([
            "install",
            "copilot",
            "--target-dir",
            target.to_str().expect("target path"),
        ])
        .output()
        .expect("install copilot plugin");
    assert!(
        output.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );

    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let source = std::fs::read_to_string(manifest_dir.join("resources/plugin/lsp.json"))
        .expect("plugin lsp");
    let installed = std::fs::read_to_string(target.join("lsp.json")).expect("installed lsp");
    assert_eq!(installed, source);

    let source_instructions = std::fs::read_to_string(
        manifest_dir.join("resources/plugin/instructions/Kotlin.instructions.md"),
    )
    .expect("plugin Kotlin instructions");
    let installed_instructions =
        std::fs::read_to_string(target.join("instructions/Kotlin.instructions.md"))
            .expect("installed Kotlin instructions");
    assert_eq!(installed_instructions, source_instructions);

    assert!(
        !target
            .join("instructions/kast-kotlin.instructions.md")
            .exists()
    );
    assert!(!target.join("agents/kast-reader.agent.md").exists());
    assert!(!target.join("agents/kast-writer.agent.md").exists());

    let extension_source = std::fs::read_to_string(
        manifest_dir.join("resources/plugin/extensions/kast/extension.mjs"),
    )
    .expect("plugin extension");
    let installed_extension = std::fs::read_to_string(target.join("extensions/kast/extension.mjs"))
        .expect("installed extension");
    assert_eq!(installed_extension, extension_source);
    assert!(installed_extension.contains("KAST_TOOLING_CONTEXT"));
    assert!(installed_extension.contains("onUserPromptSubmitted"));
    assert!(installed_extension.contains("additionalContext"));

    let trace_source = std::fs::read_to_string(
        manifest_dir.join("resources/plugin/extensions/kast/_shared/kast-trace.mjs"),
    )
    .expect("plugin trace helper");
    let installed_trace =
        std::fs::read_to_string(target.join("extensions/kast/_shared/kast-trace.mjs"))
            .expect("installed trace helper");
    assert_eq!(installed_trace, trace_source);
    assert!(
        !target
            .join("extensions/kast/_shared/kast-agents.mjs")
            .exists()
    );

    let catalog_source =
        std::fs::read_to_string(manifest_dir.join("resources/kast-skill/references/commands.json"))
            .expect("command catalog");
    let installed_catalog =
        std::fs::read_to_string(target.join("extensions/kast/_shared/commands.json"))
            .expect("installed command catalog");
    assert_eq!(installed_catalog, catalog_source);
}
