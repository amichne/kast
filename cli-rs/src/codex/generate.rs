use super::exposure::{CodexCommandDescriptor, CodexSemanticCommand};
use crate::cli::CodexGenerateArgs;
use crate::error::{CliError, Result};
use serde::Serialize;
use serde_json::json;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

const SKILL: &str =
    include_str!("../../resources/codex-plugin/plugins/kast/skills/kast-codex/SKILL.md");
const OPENAI_YAML: &str =
    include_str!("../../resources/codex-plugin/plugins/kast/skills/kast-codex/agents/openai.yaml");
const KAST_SVG: &[u8] = include_bytes!("../../resources/codex-plugin/plugins/kast/assets/kast.svg");
const HOOK_LAUNCHER: &str =
    include_str!("../../resources/codex-plugin/plugins/kast/scripts/kast-codex-hook");

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CodexGenerationReport {
    ok: bool,
    mode: &'static str,
    output_directory: String,
    version: &'static str,
    files: Vec<String>,
    schema_version: u32,
}

struct GeneratedFile {
    relative_path: &'static str,
    contents: Vec<u8>,
    executable: bool,
}

#[derive(Serialize)]
struct ExposureAsset {
    version: &'static str,
    schema_version: u32,
    semantic_commands: Vec<CodexCommandDescriptor>,
    hook_only: Vec<&'static str>,
    not_exposed: Vec<&'static str>,
}

pub(crate) fn run(args: CodexGenerateArgs) -> Result<CodexGenerationReport> {
    let output = args.output_dir.unwrap_or_else(source_marketplace_root);
    let files = generated_files()?;
    if args.check {
        check_files(&output, &files)?;
    } else {
        write_files(&output, &files)?;
    }
    Ok(CodexGenerationReport {
        ok: true,
        mode: if args.check {
            "check"
        } else if args.release {
            "release"
        } else {
            "write"
        },
        output_directory: output.display().to_string(),
        version: crate::cli::version(),
        files: files
            .iter()
            .map(|file| file.relative_path.to_string())
            .collect(),
        schema_version: 1,
    })
}

fn source_marketplace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/codex-plugin")
}

fn generated_files() -> Result<Vec<GeneratedFile>> {
    let descriptors: Vec<_> = CodexSemanticCommand::ALL
        .into_iter()
        .map(CodexSemanticCommand::descriptor)
        .collect();
    let exposure = ExposureAsset {
        version: crate::cli::version(),
        schema_version: 1,
        semantic_commands: descriptors.clone(),
        hook_only: vec!["developer codex hook session-start|post-tool-use"],
        not_exposed: vec![
            "setup",
            "repair --apply",
            "agent lsp",
            "developer runtime",
            "demo",
            "doctor",
            "developer codex generate",
            "agent tools",
            "agent call",
            "agent workflow",
        ],
    };

    Ok(vec![
        json_file("marketplace.json", marketplace())?,
        json_file(".agents/plugins/marketplace.json", marketplace())?,
        json_file("plugins/kast/.codex-plugin/plugin.json", manifest())?,
        json_file("plugins/kast/hooks/hooks.json", hooks())?,
        text_file(
            "plugins/kast/scripts/kast-codex-hook",
            HOOK_LAUNCHER.to_string(),
            true,
        ),
        text_file(
            "plugins/kast/assets/codex-exposure.toon",
            toon_format::encode_default(&exposure)
                .map_err(|error| CliError::new("CODEX_GENERATION_ERROR", error.to_string()))?
                + "\n",
            false,
        ),
        text_file(
            "plugins/kast/skills/kast-codex/SKILL.md",
            SKILL.to_string(),
            false,
        ),
        text_file(
            "plugins/kast/skills/kast-codex/agents/openai.yaml",
            OPENAI_YAML.to_string(),
            false,
        ),
        GeneratedFile {
            relative_path: "plugins/kast/assets/kast.svg",
            contents: KAST_SVG.to_vec(),
            executable: false,
        },
    ])
}

fn marketplace() -> serde_json::Value {
    json!({
        "name": "kast",
        "interface": {"displayName": "Kast"},
        "plugins": [{
            "name": "kast",
            "source": {"source": "local", "path": "./plugins/kast"},
            "policy": {"installation": "AVAILABLE", "authentication": "ON_INSTALL"},
            "category": "Productivity"
        }]
    })
}

fn manifest() -> serde_json::Value {
    json!({
        "name": "kast",
        "version": crate::cli::version(),
        "description": "Compiler-backed Kotlin and Gradle semantic operations for Codex through Kast.",
        "author": {
            "name": "Austin Michne",
            "email": "austin@michne.com",
            "url": "https://github.com/amichne"
        },
        "homepage": "https://kast.michne.com/",
        "repository": "https://github.com/amichne/kast",
        "license": "MIT",
        "keywords": ["codex", "gradle", "kotlin", "semantic-analysis"],
        "skills": "./skills/",
        "interface": {
            "displayName": "Kast",
            "shortDescription": "Compiler-backed Kotlin and Gradle operations.",
            "longDescription": "Inspect and change Kotlin or Gradle code through compiler-backed Kast operations.",
            "developerName": "Austin Michne",
            "category": "Productivity",
            "capabilities": ["Read", "Write"],
            "websiteURL": "https://kast.michne.com/",
            "defaultPrompt": [
                "Inspect a Kotlin symbol with compiler-backed evidence.",
                "Apply this Kotlin change and return its terminal diagnostics outcome."
            ],
            "composerIcon": "./assets/kast.svg",
            "logo": "./assets/kast.svg",
            "logoDark": "./assets/kast.svg"
        }
    })
}

fn hooks() -> serde_json::Value {
    json!({
        "hooks": {
            "SessionStart": [{
                "matcher": "startup",
                "hooks": [{
                    "type": "command",
                    "command": "\"$PLUGIN_ROOT/scripts/kast-codex-hook\" session-start",
                    "timeout": 70,
                    "statusMessage": "Opening this worktree for Kast"
                }]
            }],
            "PostToolUse": [{
                "matcher": "apply_patch|Edit|Write",
                "hooks": [{
                    "type": "command",
                    "command": "\"$PLUGIN_ROOT/scripts/kast-codex-hook\" post-tool-use",
                    "timeout": 70,
                    "statusMessage": "Checking changed Kotlin files with Kast"
                }]
            }]
        }
    })
}

fn json_file(relative_path: &'static str, value: serde_json::Value) -> Result<GeneratedFile> {
    let mut contents = serde_json::to_vec_pretty(&value).map_err(|error| {
        CliError::new(
            "CODEX_GENERATION_ERROR",
            format!("failed to render {relative_path}: {error}"),
        )
    })?;
    contents.push(b'\n');
    Ok(GeneratedFile {
        relative_path,
        contents,
        executable: false,
    })
}

fn text_file(relative_path: &'static str, contents: String, executable: bool) -> GeneratedFile {
    GeneratedFile {
        relative_path,
        contents: contents.into_bytes(),
        executable,
    }
}

fn write_files(root: &Path, files: &[GeneratedFile]) -> Result<()> {
    for file in files {
        let path = root.join(file.relative_path);
        let parent = path.parent().ok_or_else(|| {
            CliError::new("CODEX_GENERATION_ERROR", "generated path has no parent")
        })?;
        fs::create_dir_all(parent)?;
        fs::write(&path, &file.contents)?;
        let mode = if file.executable { 0o755 } else { 0o644 };
        fs::set_permissions(&path, fs::Permissions::from_mode(mode))?;
    }
    Ok(())
}

fn check_files(root: &Path, files: &[GeneratedFile]) -> Result<()> {
    let mut drift = Vec::new();
    for file in files {
        let path = root.join(file.relative_path);
        match fs::read(&path) {
            Ok(actual) if actual == file.contents => {
                if file.executable && fs::metadata(&path)?.permissions().mode() & 0o111 == 0 {
                    drift.push(format!("{} is not executable", file.relative_path));
                }
            }
            Ok(_) => drift.push(format!("{} differs", file.relative_path)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                drift.push(format!("{} is missing", file.relative_path));
            }
            Err(error) => return Err(error.into()),
        }
    }
    if drift.is_empty() {
        return Ok(());
    }
    let mut error = CliError::new(
        "CODEX_GENERATED_ASSETS_DRIFT",
        "Committed Codex plugin assets differ from the Rust contract.",
    );
    error.details.insert("files".to_string(), drift.join(", "));
    error.details.insert(
        "nextStep".to_string(),
        "Run `kast developer codex generate`.".to_string(),
    );
    Err(error)
}
