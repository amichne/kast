#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct CodexArgs {
    #[command(subcommand)]
    pub command: CodexCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum CodexCommand {
    /// Run one advisory plugin hook event over stdin.
    #[command(hide = true)]
    Hook(CodexHookArgs),
}

#[derive(Debug, Args, Clone)]
pub struct CodexHookArgs {
    #[arg(value_enum)]
    pub event: CodexHookEvent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum CodexHookEvent {
    SessionStart,
    PostToolUse,
}

impl CodexHookEvent {
    pub(crate) fn codex_name(self) -> &'static str {
        match self {
            Self::SessionStart => "SessionStart",
            Self::PostToolUse => "PostToolUse",
        }
    }
}
