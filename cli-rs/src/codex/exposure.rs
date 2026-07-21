use crate::cli::{
    AgentCommand, AgentLeaseCommand, CodexCommand, Command, DeveloperCommand, GenerateCommand,
    InspectCommand, MetricsCommand, PackageCommand, ReleaseCommand, RuntimeCommand,
};
use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CodexExposure {
    AgentVisible(CodexSemanticCommand),
    NotExposed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum CodexSemanticCommand {
    LeaseAcquire,
    LeaseStatus,
    LeaseRelease,
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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum CodexCommandMode {
    Read,
    Lifecycle,
    PlanFirstMutation,
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
    pub(crate) const ALL: [Self; 18] = [
        Self::LeaseAcquire,
        Self::LeaseStatus,
        Self::LeaseRelease,
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
    ];

    pub(crate) fn descriptor(self) -> CodexCommandDescriptor {
        match self {
            Self::LeaseAcquire => descriptor(
                self,
                "agent lease acquire",
                CodexCommandMode::Lifecycle,
                false,
                "READY exact-root runtime and install-generation lease",
                "kast --output toon agent lease acquire --workspace-root <root> --backend <backend>",
            ),
            Self::LeaseStatus => descriptor(
                self,
                "agent lease status",
                CodexCommandMode::Lifecycle,
                false,
                "authenticated lease lifecycle and exact runtime identity",
                "kast --output toon agent lease status --workspace-root <root> --backend <backend> --lease-id <id>",
            ),
            Self::LeaseRelease => descriptor(
                self,
                "agent lease release",
                CodexCommandMode::Lifecycle,
                false,
                "idempotent release receipt and exact ownership cleanup",
                "kast --output toon agent lease release --workspace-root <root> --backend <backend> --lease-id <id>",
            ),
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
        Command::Version => CodexExposure::NotExposed,
        Command::Context(_) => CodexExposure::NotExposed,
        Command::Setup(_) => CodexExposure::NotExposed,
        Command::Ready(_) => CodexExposure::NotExposed,
        Command::Status(_) => CodexExposure::NotExposed,
        Command::Demo(_) => CodexExposure::NotExposed,
        Command::Developer(args) => classify_developer(&args.command),
        Command::Doctor(_) => CodexExposure::NotExposed,
        Command::Agent(args) => classify_agent(args.command.as_ref()),
    }
}

pub(crate) fn classify_developer(command: &DeveloperCommand) -> CodexExposure {
    match command {
        DeveloperCommand::Runtime(args) => classify_runtime(&args.command),
        DeveloperCommand::Inspect(args) => classify_inspect(&args.command),
        DeveloperCommand::Release(args) => classify_release(&args.command),
        DeveloperCommand::Codex(args) => match &args.command {
            CodexCommand::Generate(_) => CodexExposure::NotExposed,
            CodexCommand::Hook(_) => CodexExposure::NotExposed,
        },
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

fn classify_release(command: &ReleaseCommand) -> CodexExposure {
    match command {
        ReleaseCommand::Package(args) => classify_package(&args.command),
        ReleaseCommand::Generate(args) => classify_generate(&args.command),
        ReleaseCommand::Validate(_) => CodexExposure::NotExposed,
    }
}

fn classify_package(command: &PackageCommand) -> CodexExposure {
    match command {
        PackageCommand::UbuntuDebianBundle(_) => CodexExposure::NotExposed,
    }
}

fn classify_generate(command: &GenerateCommand) -> CodexExposure {
    match command {
        GenerateCommand::Contract(_) => CodexExposure::NotExposed,
    }
}

fn classify_agent(command: Option<&AgentCommand>) -> CodexExposure {
    match command {
        None => CodexExposure::NotExposed,
        Some(AgentCommand::Lsp(_)) => CodexExposure::NotExposed,
        Some(AgentCommand::Lease(args)) => match &args.command {
            AgentLeaseCommand::Acquire(_) => visible(CodexSemanticCommand::LeaseAcquire),
            AgentLeaseCommand::Status(_) => visible(CodexSemanticCommand::LeaseStatus),
            AgentLeaseCommand::Release(_) => visible(CodexSemanticCommand::LeaseRelease),
        },
        Some(AgentCommand::Verify(_)) => CodexExposure::NotExposed,
        Some(AgentCommand::WorkspaceFiles(_)) => visible(CodexSemanticCommand::WorkspaceFiles),
        Some(AgentCommand::Symbol(_)) => visible(CodexSemanticCommand::Symbol),
        Some(AgentCommand::References(_)) => visible(CodexSemanticCommand::References),
        Some(AgentCommand::Callers(_)) => visible(CodexSemanticCommand::Callers),
        Some(AgentCommand::Callees(_)) => visible(CodexSemanticCommand::Callees),
        Some(AgentCommand::Implementations(_)) => visible(CodexSemanticCommand::Implementations),
        Some(AgentCommand::Hierarchy(_)) => visible(CodexSemanticCommand::Hierarchy),
        Some(AgentCommand::Impact(_)) => visible(CodexSemanticCommand::Impact),
        Some(AgentCommand::Diagnostics(_)) => visible(CodexSemanticCommand::Diagnostics),
        Some(AgentCommand::Rename(_)) => visible(CodexSemanticCommand::Rename),
        Some(AgentCommand::AddFile(_)) => visible(CodexSemanticCommand::AddFile),
        Some(AgentCommand::AddDeclaration(_)) => visible(CodexSemanticCommand::AddDeclaration),
        Some(AgentCommand::AddImplementation(_)) => {
            visible(CodexSemanticCommand::AddImplementation)
        }
        Some(AgentCommand::AddStatement(_)) => visible(CodexSemanticCommand::AddStatement),
        Some(AgentCommand::ReplaceDeclaration(_)) => {
            visible(CodexSemanticCommand::ReplaceDeclaration)
        }
        Some(AgentCommand::Tools(_)) => CodexExposure::NotExposed,
        Some(AgentCommand::Call(_)) => CodexExposure::NotExposed,
        Some(AgentCommand::Workflow(_)) => CodexExposure::NotExposed,
    }
}

fn visible(command: CodexSemanticCommand) -> CodexExposure {
    CodexExposure::AgentVisible(command)
}
