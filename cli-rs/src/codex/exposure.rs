use crate::cli::{
    AgentCommand, AgentOperationCommand, CodexCommand, CodexHookEvent, Command, DeveloperCommand,
    GenerateCommand, InspectCommand, LocalDevelopmentCommand, MachineCommand, MetricsCommand,
    PackageCommand, ReleaseActivateCommand, ReleaseCommand, RuntimeCommand,
};
use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CodexExposure {
    AgentVisible(CodexSemanticCommand),
    HookOnly(CodexHookCommand),
    NotExposed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum CodexSemanticCommand {
    WorkspaceFiles,
    Symbol,
    References,
    Callers,
    Callees,
    Implementations,
    Hierarchy,
    Impact,
    Diagnostics,
    Rename,
    AddFile,
    AddDeclaration,
    AddImplementation,
    AddStatement,
    ReplaceDeclaration,
    OperationStatus,
    OperationCancel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CodexHookCommand {
    Version,
    Context,
    Ready,
    RepairPlan,
    Status,
    Verify,
    Event(CodexHookEvent),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum CodexCommandMode {
    Read,
    PlanFirstMutation,
    OperationControl,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CodexCommandDescriptor {
    pub command: CodexSemanticCommand,
    pub path: &'static str,
    pub mode: CodexCommandMode,
    pub plan_apply: bool,
    pub evidence: &'static str,
    pub example: &'static str,
}

impl CodexSemanticCommand {
    pub(crate) const ALL: [Self; 17] = [
        Self::WorkspaceFiles,
        Self::Symbol,
        Self::References,
        Self::Callers,
        Self::Callees,
        Self::Implementations,
        Self::Hierarchy,
        Self::Impact,
        Self::Diagnostics,
        Self::Rename,
        Self::AddFile,
        Self::AddDeclaration,
        Self::AddImplementation,
        Self::AddStatement,
        Self::ReplaceDeclaration,
        Self::OperationStatus,
        Self::OperationCancel,
    ];

    pub(crate) fn descriptor(self) -> CodexCommandDescriptor {
        match self {
            Self::WorkspaceFiles => descriptor(
                self,
                "agent workspace-files",
                CodexCommandMode::Read,
                false,
                "typed workspace paths and coverage",
                "kast --output toon agent workspace-files --workspace-root <root>",
            ),
            Self::Symbol => descriptor(
                self,
                "agent symbol",
                CodexCommandMode::Read,
                false,
                "compiler-resolved symbol identity",
                "kast --output toon agent symbol --workspace-root <root> --query <name>",
            ),
            Self::References => relationship(
                self,
                "agent references",
                "bounded reference identities",
                "kast --output toon agent references --workspace-root <root> --symbol <fq-name>",
            ),
            Self::Callers => relationship(
                self,
                "agent callers",
                "bounded incoming caller identities",
                "kast --output toon agent callers --workspace-root <root> --symbol <fq-name>",
            ),
            Self::Callees => relationship(
                self,
                "agent callees",
                "bounded outgoing callee identities",
                "kast --output toon agent callees --workspace-root <root> --symbol <fq-name>",
            ),
            Self::Implementations => relationship(
                self,
                "agent implementations",
                "bounded implementation identities",
                "kast --output toon agent implementations --workspace-root <root> --symbol <fq-name>",
            ),
            Self::Hierarchy => relationship(
                self,
                "agent hierarchy",
                "bounded hierarchy identities",
                "kast --output toon agent hierarchy --workspace-root <root> --symbol <fq-name>",
            ),
            Self::Impact => descriptor(
                self,
                "agent impact",
                CodexCommandMode::Read,
                false,
                "source-index impact evidence",
                "kast --output toon agent impact --workspace-root <root> --symbol <fq-name>",
            ),
            Self::Diagnostics => descriptor(
                self,
                "agent diagnostics",
                CodexCommandMode::Read,
                false,
                "diagnostics bound to current file contents",
                "kast --output toon agent diagnostics --workspace-root <root> --file-path <path>",
            ),
            Self::Rename => mutation(
                self,
                "agent rename",
                "kast --output toon agent rename --workspace-root <root> --symbol <fq-name> --new-name <name>",
            ),
            Self::AddFile => mutation(
                self,
                "agent add-file",
                "kast --output toon agent add-file --workspace-root <root> --file-path <path> --content-file <file>",
            ),
            Self::AddDeclaration => mutation(
                self,
                "agent add-declaration",
                "kast --output toon agent add-declaration --workspace-root <root> --inside-file <path> --at file-bottom --content-file <file>",
            ),
            Self::AddImplementation => mutation(
                self,
                "agent add-implementation",
                "kast --output toon agent add-implementation --workspace-root <root> --inside-file <path> --at file-bottom --content-file <file>",
            ),
            Self::AddStatement => mutation(
                self,
                "agent add-statement",
                "kast --output toon agent add-statement --workspace-root <root> --inside-scope <fq-name> --at body-end --content-file <file>",
            ),
            Self::ReplaceDeclaration => mutation(
                self,
                "agent replace-declaration",
                "kast --output toon agent replace-declaration --workspace-root <root> --symbol <fq-name> --content-file <file>",
            ),
            Self::OperationStatus => descriptor(
                self,
                "agent operation status",
                CodexCommandMode::OperationControl,
                false,
                "latest retained operation state",
                "kast --output toon agent operation status --workspace-root <root> --idempotency-key <key>",
            ),
            Self::OperationCancel => descriptor(
                self,
                "agent operation cancel",
                CodexCommandMode::OperationControl,
                false,
                "cooperative cancellation outcome",
                "kast --output toon agent operation cancel --workspace-root <root> --operation-id <id>",
            ),
        }
    }
}

fn descriptor(
    command: CodexSemanticCommand,
    path: &'static str,
    mode: CodexCommandMode,
    plan_apply: bool,
    evidence: &'static str,
    example: &'static str,
) -> CodexCommandDescriptor {
    CodexCommandDescriptor {
        command,
        path,
        mode,
        plan_apply,
        evidence,
        example,
    }
}

fn relationship(
    command: CodexSemanticCommand,
    path: &'static str,
    evidence: &'static str,
    example: &'static str,
) -> CodexCommandDescriptor {
    descriptor(
        command,
        path,
        CodexCommandMode::Read,
        false,
        evidence,
        example,
    )
}

fn mutation(
    command: CodexSemanticCommand,
    path: &'static str,
    example: &'static str,
) -> CodexCommandDescriptor {
    descriptor(
        command,
        path,
        CodexCommandMode::PlanFirstMutation,
        true,
        "typed plan or applied operation with idempotency evidence",
        example,
    )
}

pub(crate) fn classify_command(command: &Command) -> CodexExposure {
    match command {
        Command::Help { .. } => CodexExposure::NotExposed,
        Command::Version => CodexExposure::HookOnly(CodexHookCommand::Version),
        Command::Context(_) => CodexExposure::HookOnly(CodexHookCommand::Context),
        Command::Setup(_) => CodexExposure::NotExposed,
        Command::Ready(_) => CodexExposure::HookOnly(CodexHookCommand::Ready),
        Command::Repair(args) if args.apply => CodexExposure::NotExposed,
        Command::Repair(_) => CodexExposure::HookOnly(CodexHookCommand::RepairPlan),
        Command::Status(_) => CodexExposure::HookOnly(CodexHookCommand::Status),
        Command::Demo(_) => CodexExposure::NotExposed,
        Command::Developer(args) => classify_developer(&args.command),
        Command::Doctor(_) => CodexExposure::NotExposed,
        Command::Agent(args) => classify_agent(&args.command),
    }
}

pub(crate) fn classify_developer(command: &DeveloperCommand) -> CodexExposure {
    match command {
        DeveloperCommand::Local(args) => classify_local_development(&args.command),
        DeveloperCommand::Runtime(args) => classify_runtime(&args.command),
        DeveloperCommand::Inspect(args) => classify_inspect(&args.command),
        DeveloperCommand::Machine(args) => classify_machine(&args.command),
        DeveloperCommand::Release(args) => classify_release(&args.command),
        DeveloperCommand::Codex(args) => match &args.command {
            CodexCommand::Generate(_) => CodexExposure::NotExposed,
            CodexCommand::Hook(args) => {
                CodexExposure::HookOnly(CodexHookCommand::Event(args.event))
            }
        },
    }
}

fn classify_local_development(command: &LocalDevelopmentCommand) -> CodexExposure {
    match command {
        LocalDevelopmentCommand::Snapshot(_)
        | LocalDevelopmentCommand::Attest(_)
        | LocalDevelopmentCommand::Prepare(_)
        | LocalDevelopmentCommand::Verify(_)
        | LocalDevelopmentCommand::Activate(_)
        | LocalDevelopmentCommand::Refresh(_)
        | LocalDevelopmentCommand::Rollback(_)
        | LocalDevelopmentCommand::Remove(_) => CodexExposure::NotExposed,
    }
}

fn classify_runtime(command: &RuntimeCommand) -> CodexExposure {
    match command {
        RuntimeCommand::Up(_)
        | RuntimeCommand::Status(_)
        | RuntimeCommand::Stop(_)
        | RuntimeCommand::Restart(_)
        | RuntimeCommand::Capabilities(_) => CodexExposure::NotExposed,
    }
}

fn classify_inspect(command: &InspectCommand) -> CodexExposure {
    match command {
        InspectCommand::Paths(_) => CodexExposure::NotExposed,
        InspectCommand::Metrics { command } => classify_metrics(command),
        InspectCommand::Demo(_) | InspectCommand::Catalog(_) => CodexExposure::NotExposed,
    }
}

fn classify_metrics(command: &MetricsCommand) -> CodexExposure {
    match command {
        MetricsCommand::FanIn(_)
        | MetricsCommand::FanOut(_)
        | MetricsCommand::DeadCode(_)
        | MetricsCommand::Impact(_)
        | MetricsCommand::Coupling(_)
        | MetricsCommand::Search(_) => CodexExposure::NotExposed,
    }
}

fn classify_machine(command: &MachineCommand) -> CodexExposure {
    match command {
        MachineCommand::Defaults(_) | MachineCommand::Shell(_) | MachineCommand::Completion(_) => {
            CodexExposure::NotExposed
        }
    }
}

fn classify_release(command: &ReleaseCommand) -> CodexExposure {
    match command {
        ReleaseCommand::Package(args) => classify_package(&args.command),
        ReleaseCommand::Activate(args) => classify_release_activate(&args.command),
        ReleaseCommand::Generate(args) => classify_generate(&args.command),
        ReleaseCommand::Validate(_) => CodexExposure::NotExposed,
    }
}

fn classify_package(command: &PackageCommand) -> CodexExposure {
    match command {
        PackageCommand::UbuntuDebianBundle(_) => CodexExposure::NotExposed,
    }
}

fn classify_release_activate(command: &ReleaseActivateCommand) -> CodexExposure {
    match command {
        ReleaseActivateCommand::Bundle(_) => CodexExposure::NotExposed,
    }
}

fn classify_generate(command: &GenerateCommand) -> CodexExposure {
    match command {
        GenerateCommand::Contract(_) => CodexExposure::NotExposed,
    }
}

fn classify_agent(command: &AgentCommand) -> CodexExposure {
    match command {
        AgentCommand::Lsp(_) => CodexExposure::NotExposed,
        AgentCommand::Verify(_) => CodexExposure::HookOnly(CodexHookCommand::Verify),
        AgentCommand::WorkspaceFiles(_) => visible(CodexSemanticCommand::WorkspaceFiles),
        AgentCommand::Symbol(_) => visible(CodexSemanticCommand::Symbol),
        AgentCommand::References(_) => visible(CodexSemanticCommand::References),
        AgentCommand::Callers(_) => visible(CodexSemanticCommand::Callers),
        AgentCommand::Callees(_) => visible(CodexSemanticCommand::Callees),
        AgentCommand::Implementations(_) => visible(CodexSemanticCommand::Implementations),
        AgentCommand::Hierarchy(_) => visible(CodexSemanticCommand::Hierarchy),
        AgentCommand::Impact(_) => visible(CodexSemanticCommand::Impact),
        AgentCommand::Diagnostics(_) => visible(CodexSemanticCommand::Diagnostics),
        AgentCommand::Rename(_) => visible(CodexSemanticCommand::Rename),
        AgentCommand::AddFile(_) => visible(CodexSemanticCommand::AddFile),
        AgentCommand::AddDeclaration(_) => visible(CodexSemanticCommand::AddDeclaration),
        AgentCommand::AddImplementation(_) => visible(CodexSemanticCommand::AddImplementation),
        AgentCommand::AddStatement(_) => visible(CodexSemanticCommand::AddStatement),
        AgentCommand::ReplaceDeclaration(_) => visible(CodexSemanticCommand::ReplaceDeclaration),
        AgentCommand::Operation(args) => classify_operation(&args.command),
        AgentCommand::Tools(_) => CodexExposure::NotExposed,
        AgentCommand::Call(_) => CodexExposure::NotExposed,
        AgentCommand::Workflow(_) => CodexExposure::NotExposed,
    }
}

fn classify_operation(command: &AgentOperationCommand) -> CodexExposure {
    match command {
        AgentOperationCommand::Status(_) => visible(CodexSemanticCommand::OperationStatus),
        AgentOperationCommand::Cancel(_) => visible(CodexSemanticCommand::OperationCancel),
    }
}

fn visible(command: CodexSemanticCommand) -> CodexExposure {
    CodexExposure::AgentVisible(command)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::Cli;
    use clap::Parser;

    #[test]
    fn semantic_contract_contains_exactly_the_fixed_codex_commands() {
        let paths: Vec<_> = CodexSemanticCommand::ALL
            .into_iter()
            .map(|command| command.descriptor().path)
            .collect();
        assert_eq!(
            paths,
            [
                "agent workspace-files",
                "agent symbol",
                "agent references",
                "agent callers",
                "agent callees",
                "agent implementations",
                "agent hierarchy",
                "agent impact",
                "agent diagnostics",
                "agent rename",
                "agent add-file",
                "agent add-declaration",
                "agent add-implementation",
                "agent add-statement",
                "agent replace-declaration",
                "agent operation status",
                "agent operation cancel",
            ]
        );
    }

    #[test]
    fn parsed_commands_follow_the_exposure_policy() {
        assert_eq!(
            parsed_exposure(&["version"]),
            CodexExposure::HookOnly(CodexHookCommand::Version)
        );
        assert_eq!(
            parsed_exposure(&["repair"]),
            CodexExposure::HookOnly(CodexHookCommand::RepairPlan)
        );
        assert_eq!(
            parsed_exposure(&["repair", "--apply"]),
            CodexExposure::NotExposed
        );
        assert_eq!(
            parsed_exposure(&["agent", "workspace-files"]),
            CodexExposure::AgentVisible(CodexSemanticCommand::WorkspaceFiles)
        );
        assert_eq!(
            parsed_exposure(&["agent", "verify"]),
            CodexExposure::HookOnly(CodexHookCommand::Verify)
        );
        assert_eq!(
            parsed_exposure(&["developer", "codex", "generate"]),
            CodexExposure::NotExposed
        );
        assert_eq!(
            parsed_exposure(&["developer", "codex", "hook", "stop"]),
            CodexExposure::HookOnly(CodexHookCommand::Event(CodexHookEvent::Stop))
        );
        assert_eq!(
            parsed_exposure(&["agent", "operation", "status", "--operation-id", "id"]),
            CodexExposure::AgentVisible(CodexSemanticCommand::OperationStatus)
        );
    }

    fn parsed_exposure(args: &[&str]) -> CodexExposure {
        let cli = Cli::try_parse_from(std::iter::once("kast").chain(args.iter().copied()))
            .expect("valid test command");
        classify_command(cli.command.as_ref().expect("test command"))
    }
}
