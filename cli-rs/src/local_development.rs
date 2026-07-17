use crate::cli::{LocalDevelopmentCommand, LocalDevelopmentSnapshotArgs, OutputFormat};
use crate::error::{CliError, Result};

include!("local_development/source_snapshot.rs");
include!("local_development/types.rs");
include!("local_development/filesystem.rs");
include!("local_development/provenance.rs");
include!("local_development/prepared_generation.rs");
include!("local_development/refresh.rs");
include!("local_development/lifecycle.rs");

pub fn run(command: LocalDevelopmentCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        LocalDevelopmentCommand::Snapshot(args) => snapshot(args, output_format),
        LocalDevelopmentCommand::Attest(args) => {
            let kind = match args.artifact_kind {
                crate::cli::LocalArtifactKindArg::Cli => LocalArtifactKind::Cli,
                crate::cli::LocalArtifactKindArg::HeadlessBackend => {
                    LocalArtifactKind::HeadlessBackend
                }
            };
            let result = attest_local_artifact(LocalArtifactAttestationRequest {
                source_root: args.source_root,
                expected_source_snapshot: args.expected_source_snapshot,
                kind,
                artifact: args.artifact,
                output_file: args.output_file,
            })?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
        LocalDevelopmentCommand::Prepare(args) => {
            let skill_source = args
                .source_root
                .join("cli-rs/resources/kast-skill/SKILL.md");
            let result = prepare_local_development_generation(LocalDevelopmentPrepareRequest {
                source_root: args.source_root,
                expected_source_snapshot: args.expected_source_snapshot,
                cli_binary: args.cli_binary,
                cli_provenance: args.cli_provenance,
                backend_directory: args.backend_directory,
                backend_provenance: args.backend_provenance,
                skill_source,
                output_directory: args.output_directory,
            })?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
        LocalDevelopmentCommand::Verify(args) => {
            let result =
                verify_local_development_generation(&args.source_root, &args.prepared_generation)?;
            require_exact_controller(
                &result.directory.join(PREPARED_CLI_PATH),
                "LOCAL_VERIFY_CONTROLLER_MISMATCH",
            )?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
        LocalDevelopmentCommand::Activate(args) => {
            let prefix = args
                .prefix
                .unwrap_or_else(|| args.source_root.join(".kast/local-development"));
            let result = activate_local_development_generation(LocalDevelopmentActivateRequest {
                source_root: args.source_root,
                workspace_root: args.workspace_root,
                prefix,
                prepared_generation: args.prepared_generation,
            })?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
        LocalDevelopmentCommand::Refresh(args) => {
            let skill_source = args
                .source_root
                .join("cli-rs/resources/kast-skill/SKILL.md");
            let config_source = args
                .source_root
                .join("cli-rs/resources/local-development/config.toml");
            let prefix = args
                .prefix
                .unwrap_or_else(|| args.source_root.join(".kast/local-development"));
            let result = refresh_local_development(LocalDevelopmentRefreshRequest {
                source_root: args.source_root,
                workspace_root: args.workspace_root,
                prefix,
                expected_source_snapshot: args.expected_source_snapshot,
                cli_binary: args.cli_binary,
                cli_provenance: args.cli_provenance,
                backend_directory: args.backend_directory,
                backend_provenance: args.backend_provenance,
                skill_source,
                config_source,
            })?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
        LocalDevelopmentCommand::Rollback(args) => {
            let result = rollback_local_development(LocalDevelopmentRollbackRequest {
                prefix: args.prefix,
                to_generation: LocalGenerationId::try_from(args.to_generation)?,
            })?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
        LocalDevelopmentCommand::Remove(args) => {
            let result = remove_local_development(LocalDevelopmentRemoveRequest {
                prefix: args.prefix,
                workspace_root: args.workspace_root,
            })?;
            crate::output::print_structured(&result, output_format)?;
            Ok(0)
        }
    }
}

fn snapshot(args: LocalDevelopmentSnapshotArgs, output_format: OutputFormat) -> Result<i32> {
    let snapshot = SourceSnapshot::capture(&args.source_root)?;
    snapshot.write_atomic(&args.output_file)?;
    crate::output::print_structured(&snapshot, output_format)?;
    Ok(0)
}

#[cfg(test)]
include!("local_development/tests.rs");
