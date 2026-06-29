#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum ShellKind {
    Bash,
    Zsh,
}

impl ShellKind {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Bash => "bash",
            Self::Zsh => "zsh",
        }
    }

    pub fn extension(self) -> &'static str {
        match self {
            Self::Bash => "bash",
            Self::Zsh => "zsh",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum BackendName {
    Idea,
    Headless,
}

impl BackendName {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Idea => "idea",
            Self::Headless => "headless",
        }
    }
}
