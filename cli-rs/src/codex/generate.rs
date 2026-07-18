use super::exposure::{CodexCommandDescriptor, CodexSemanticCommand};
use crate::cli::CodexGenerateArgs;
use crate::error::{CliError, Result};
use serde::Serialize;
use serde_json::json;
use std::collections::BTreeSet;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

const SKILL: &str =
    include_str!("../../resources/codex-plugin/plugins/kast/skills/kast-codex/SKILL.md");
const OPENAI_YAML: &str =
    include_str!("../../resources/codex-plugin/plugins/kast/skills/kast-codex/agents/openai.yaml");
const LAUNCHER: &str =
    include_str!("../../resources/codex-plugin/plugins/kast/scripts/kast-codex-hook");
const KAST_SVG: &[u8] = include_bytes!("../../resources/codex-plugin/plugins/kast/assets/kast.svg");

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CodexGenerationReport {
    ok: bool,
    mode: &'static str,
    output_directory: String,
    authority: &'static str,
    version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    generation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    entrypoint: Option<String>,
    files: Vec<String>,
    schema_version: u32,
}

#[derive(Debug, Clone)]
enum CodexProjection {
    SourceTemplate,
    Release,
    LocalDevelopment {
        prefix: PathBuf,
        entrypoint: PathBuf,
        generation_id: String,
    },
}

struct GeneratedFile {
    relative_path: &'static str,
    contents: Vec<u8>,
    executable: bool,
}

#[derive(Serialize)]
struct ExposureAsset {
    version: String,
    schema_version: u32,
    semantic_commands: Vec<CodexCommandDescriptor>,
    hook_only: [&'static str; 7],
    not_exposed: [&'static str; 10],
}

#[derive(Serialize)]
struct RecoveryAsset {
    version: String,
    schema_version: u32,
    messages: Vec<RecoveryMessage>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RecoveryMessage {
    code: &'static str,
    message: &'static str,
    next_step: &'static str,
}

pub(crate) fn run(args: CodexGenerateArgs) -> Result<CodexGenerationReport> {
    let projection = projection(&args)?;
    let output = args
        .output_dir
        .unwrap_or_else(|| projection.default_output_directory());
    let files = generated_files(&projection)?;
    if args.check {
        check_files(&output, &files)?;
    } else if matches!(projection, CodexProjection::LocalDevelopment { .. }) {
        write_local_files_atomically(&output, &files)?;
    } else {
        write_files(&output, &files)?;
    }
    Ok(CodexGenerationReport {
        ok: true,
        mode: if args.local {
            "local"
        } else if args.check {
            "check"
        } else if args.release {
            "release"
        } else {
            "write"
        },
        output_directory: output.display().to_string(),
        authority: projection.authority_name(),
        version: projection.plugin_version(),
        generation_id: projection.generation_id().map(str::to_string),
        entrypoint: projection
            .entrypoint()
            .map(|path| path.display().to_string()),
        files: files
            .iter()
            .map(|file| file.relative_path.to_string())
            .collect(),
        schema_version: 2,
    })
}

fn projection(args: &CodexGenerateArgs) -> Result<CodexProjection> {
    if args.release {
        return Ok(CodexProjection::Release);
    }
    if !args.local {
        return Ok(CodexProjection::SourceTemplate);
    }
    let receipt = crate::local_development::verified_active_local_development_receipt()?
        .ok_or_else(|| {
            CliError::new(
                "CODEX_LOCAL_AUTHORITY_REQUIRED",
                "Local Codex projection must run through the active worktree-local Kast selector.",
            )
        })?;
    Ok(CodexProjection::LocalDevelopment {
        prefix: receipt.prefix,
        entrypoint: receipt.entrypoint.effective_target,
        generation_id: receipt.generation_id.as_str().to_string(),
    })
}

impl CodexProjection {
    fn default_output_directory(&self) -> PathBuf {
        match self {
            Self::SourceTemplate | Self::Release => source_marketplace_root(),
            Self::LocalDevelopment {
                prefix,
                generation_id,
                ..
            } => prefix.join("codex-marketplaces").join(generation_id),
        }
    }

    fn authority_name(&self) -> &'static str {
        match self {
            Self::SourceTemplate => "source-template",
            Self::Release => "release",
            Self::LocalDevelopment { .. } => "local-development",
        }
    }

    fn plugin_version(&self) -> String {
        match self {
            Self::SourceTemplate | Self::Release => crate::cli::version().to_string(),
            Self::LocalDevelopment { generation_id, .. } => {
                format!("{}+codex.{generation_id}", crate::cli::version())
            }
        }
    }

    fn generation_id(&self) -> Option<&str> {
        match self {
            Self::SourceTemplate | Self::Release => None,
            Self::LocalDevelopment { generation_id, .. } => Some(generation_id),
        }
    }

    fn entrypoint(&self) -> Option<&Path> {
        match self {
            Self::SourceTemplate | Self::Release => None,
            Self::LocalDevelopment { entrypoint, .. } => Some(entrypoint),
        }
    }

    fn command(&self) -> String {
        self.entrypoint().map_or_else(
            || "kast".to_string(),
            |path| shell_single_quote(&path.display().to_string()),
        )
    }
}

fn source_marketplace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/codex-plugin")
}

fn generated_files(projection: &CodexProjection) -> Result<Vec<GeneratedFile>> {
    let descriptors: Vec<_> = CodexSemanticCommand::ALL
        .into_iter()
        .map(CodexSemanticCommand::descriptor)
        .collect();
    let exposure = ExposureAsset {
        version: projection.plugin_version(),
        schema_version: 1,
        semantic_commands: descriptors.clone(),
        hook_only: [
            "version",
            "context",
            "ready",
            "repair (plan only)",
            "status",
            "agent verify",
            "developer codex hook <event>",
        ],
        not_exposed: [
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
    let recovery = RecoveryAsset {
        version: projection.plugin_version(),
        schema_version: 1,
        messages: vec![
            RecoveryMessage {
                code: "KAST_CODEX_BINARY_MISSING",
                message: "The Kast binary is unavailable to the Codex plugin.",
                next_step: "Install Kast or put the active binary on PATH, then start a new task.",
            },
            RecoveryMessage {
                code: "KAST_CODEX_VERSION_MISMATCH",
                message: "The plugin and active Kast binary are from different releases.",
                next_step: "Update Kast and reinstall kast@kast from the same release.",
            },
            RecoveryMessage {
                code: "KAST_TYPED_ROUTE_REQUIRED",
                message: "Try the corresponding typed Kast mutation before a generic Kotlin edit.",
                next_step: "Run the plan-first kast --output toon agent mutation and preserve its typed outcome.",
            },
            RecoveryMessage {
                code: "KAST_DIAGNOSTICS_REQUIRED",
                message: "New Kotlin changes do not have diagnostics for their current contents.",
                next_step: "Run kast --output toon agent diagnostics for each changed Kotlin file.",
            },
        ],
    };

    Ok(vec![
        json_file("marketplace.json", marketplace())?,
        json_file(".agents/plugins/marketplace.json", marketplace())?,
        json_file(
            "plugins/kast/.codex-plugin/plugin.json",
            manifest(projection),
        )?,
        json_file("plugins/kast/hooks/hooks.json", hooks(projection))?,
        json_file(
            "plugins/kast/assets/kast-authority.json",
            authority_manifest(projection),
        )?,
        text_file(
            "plugins/kast/skills/kast-codex/references/commands.md",
            commands_markdown(&descriptors, projection),
            false,
        ),
        text_file(
            "plugins/kast/skills/kast-codex/references/examples.md",
            examples_markdown(&descriptors, projection),
            false,
        ),
        text_file(
            "plugins/kast/assets/codex-exposure.toon",
            toon_format::encode_default(&exposure)
                .map_err(|error| CliError::new("CODEX_GENERATION_ERROR", error.to_string()))?
                + "\n",
            false,
        ),
        text_file(
            "plugins/kast/assets/hook-recovery-messages.toon",
            toon_format::encode_default(&recovery)
                .map_err(|error| CliError::new("CODEX_GENERATION_ERROR", error.to_string()))?
                + "\n",
            false,
        ),
        text_file(
            "plugins/kast/skills/kast-codex/SKILL.md",
            skill(projection),
            false,
        ),
        text_file(
            "plugins/kast/skills/kast-codex/agents/openai.yaml",
            OPENAI_YAML.to_string(),
            false,
        ),
        text_file(
            "plugins/kast/scripts/kast-codex-hook",
            LAUNCHER.to_string(),
            true,
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

fn manifest(projection: &CodexProjection) -> serde_json::Value {
    json!({
        "name": "kast",
        "version": projection.plugin_version(),
        "description": "Compiler-backed Kotlin semantics for Codex through the typed Kast CLI.",
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
            "shortDescription": "Compiler-backed Kotlin semantics for Codex.",
            "longDescription": "Inspect and change Kotlin through Kast's fixed, typed, plan-first CLI surface.",
            "developerName": "Austin Michne",
            "category": "Productivity",
            "capabilities": ["Read", "Write"],
            "websiteURL": "https://kast.michne.com/",
            "privacyPolicyURL": "https://kast.michne.com/privacy/",
            "termsOfServiceURL": "https://kast.michne.com/terms/",
            "defaultPrompt": [
                "Find a Kotlin symbol and show its callers.",
                "Plan a safe Kotlin rename and run diagnostics.",
                "Add a Kotlin implementation through Kast."
            ],
            "composerIcon": "./assets/kast.svg",
            "logo": "./assets/kast.svg",
            "logoDark": "./assets/kast.svg"
        }
    })
}

fn hooks(projection: &CodexProjection) -> serde_json::Value {
    let mut events = serde_json::Map::new();
    for (codex, event) in [
        ("SessionStart", "session-start"),
        ("SubagentStart", "subagent-start"),
        ("PreToolUse", "pre-tool-use"),
        ("PostToolUse", "post-tool-use"),
        ("Stop", "stop"),
    ] {
        let command = match projection {
            CodexProjection::SourceTemplate | CodexProjection::Release => {
                format!("\"$PLUGIN_ROOT/scripts/kast-codex-hook\" {event}")
            }
            CodexProjection::LocalDevelopment {
                entrypoint,
                generation_id,
                ..
            } => format!(
                "KAST_CODEX_BINARY={} KAST_CODEX_GENERATION={} \"$PLUGIN_ROOT/scripts/kast-codex-hook\" {event}",
                shell_single_quote(&entrypoint.display().to_string()),
                shell_single_quote(generation_id),
            ),
        };
        events.insert(
            codex.to_string(),
            json!([{"hooks": [{
                "type": "command",
                "command": command
            }]}]),
        );
    }
    json!({"hooks": events})
}

fn authority_manifest(projection: &CodexProjection) -> serde_json::Value {
    match projection {
        CodexProjection::SourceTemplate => json!({
            "schemaVersion": 1,
            "authority": {
                "kind": "source-template",
                "command": "kast",
                "pluginVersion": projection.plugin_version(),
                "cliVersion": crate::cli::version(),
            }
        }),
        CodexProjection::Release => json!({
            "schemaVersion": 1,
            "authority": {
                "kind": "release",
                "command": "kast",
                "pluginVersion": projection.plugin_version(),
                "cliVersion": crate::cli::version(),
                "releaseRevision": crate::cli::release_revision(),
            }
        }),
        CodexProjection::LocalDevelopment {
            entrypoint,
            generation_id,
            ..
        } => json!({
            "schemaVersion": 1,
            "authority": {
                "kind": "local-development",
                "command": entrypoint,
                "pluginVersion": projection.plugin_version(),
                "cliVersion": crate::cli::version(),
                "generationId": generation_id,
            }
        }),
    }
}

fn skill(projection: &CodexProjection) -> String {
    let command = projection.command();
    SKILL.replace("`kast ", &format!("`{command} "))
}

fn commands_markdown(
    descriptors: &[CodexCommandDescriptor],
    projection: &CodexProjection,
) -> String {
    let mut output = String::from(
        "# Kast Codex command reference\n\nGenerated from the exhaustive Rust exposure contract. Do not edit.\n\n| Command | Mode | Plan/apply | Evidence |\n| --- | --- | --- | --- |\n",
    );
    for descriptor in descriptors {
        output.push_str(&format!(
            "| `{} {}` | `{:?}` | {} | {} |\n",
            projection.command(),
            descriptor.path,
            descriptor.mode,
            if descriptor.plan_apply { "yes" } else { "no" },
            descriptor.evidence
        ));
    }
    output
}

fn examples_markdown(
    descriptors: &[CodexCommandDescriptor],
    projection: &CodexProjection,
) -> String {
    let mut output = String::from(
        "# Kast Codex examples\n\nGenerated from the exhaustive Rust exposure contract. Replace angle-bracket placeholders with exact values.\n",
    );
    for descriptor in descriptors {
        let source_example = descriptor
            .example
            .replacen("kast", &projection.command(), 1);
        let example = if descriptor.plan_apply {
            format!(
                "{}\n{} --apply --idempotency-key <key>",
                source_example, source_example
            )
        } else {
            source_example
        };
        output.push_str(&format!(
            "\n## `{}`\n\n```console\n{}\n```\n",
            descriptor.path, example
        ));
    }
    output
}

fn shell_single_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
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

fn write_local_files_atomically(root: &Path, files: &[GeneratedFile]) -> Result<()> {
    let output_exists = match fs::symlink_metadata(root) {
        Ok(_) => true,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
        Err(error) => return Err(error.into()),
    };
    if output_exists {
        if local_output_matches(root, files)? {
            return Ok(());
        }
        return Err(CliError::new(
            "CODEX_LOCAL_OUTPUT_CONFLICT",
            format!(
                "Local Codex output {} already exists but is not the exact generated marketplace; preserve it or choose a fresh --output-dir.",
                root.display(),
            ),
        ));
    }
    let parent = root
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    let name = root.file_name().ok_or_else(|| {
        CliError::new(
            "CODEX_LOCAL_OUTPUT_INVALID",
            "Local Codex output must name a marketplace directory.",
        )
    })?;
    fs::create_dir_all(parent)?;
    let staged = parent.join(format!(
        ".{}-staging-{}",
        name.to_string_lossy(),
        uuid::Uuid::new_v4(),
    ));
    let result = (|| {
        fs::create_dir(&staged)?;
        write_files(&staged, files)?;
        fs::rename(&staged, root)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staged);
    }
    result
}

fn local_output_matches(root: &Path, files: &[GeneratedFile]) -> Result<bool> {
    if !fs::symlink_metadata(root)?.file_type().is_dir() {
        return Ok(false);
    }
    let mut expected = BTreeSet::new();
    for file in files {
        let relative = PathBuf::from(file.relative_path);
        expected.insert(relative.clone());
        let mut parent = relative.parent();
        while let Some(path) = parent.filter(|path| !path.as_os_str().is_empty()) {
            expected.insert(path.to_path_buf());
            parent = path.parent();
        }
    }
    let mut actual = BTreeSet::new();
    collect_local_output_entries(root, root, &mut actual)?;
    if actual != expected {
        return Ok(false);
    }
    for file in files {
        let path = root.join(file.relative_path);
        let metadata = fs::symlink_metadata(&path)?;
        if !metadata.file_type().is_file()
            || fs::read(&path)? != file.contents
            || (file.executable && metadata.permissions().mode() & 0o111 == 0)
            || (!file.executable && metadata.permissions().mode() & 0o111 != 0)
        {
            return Ok(false);
        }
    }
    Ok(true)
}

fn collect_local_output_entries(
    root: &Path,
    current: &Path,
    entries: &mut BTreeSet<PathBuf>,
) -> Result<()> {
    for entry in fs::read_dir(current)? {
        let entry = entry?;
        let path = entry.path();
        let file_type = entry.file_type()?;
        entries.insert(
            path.strip_prefix(root)
                .map_err(|_| {
                    CliError::new(
                        "CODEX_LOCAL_OUTPUT_INVALID",
                        "Local Codex output escaped its marketplace root.",
                    )
                })?
                .to_path_buf(),
        );
        if file_type.is_dir() {
            collect_local_output_entries(root, &path, entries)?;
        }
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
