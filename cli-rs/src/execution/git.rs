use std::ffi::OsStr;
use std::io;
use std::path::Path;
use std::process::{Command, Output};

pub(crate) struct ReadOnlyGitCommand {
    command: Command,
}

impl ReadOnlyGitCommand {
    pub(crate) fn new() -> Self {
        Self::with_executable("git")
    }

    fn with_executable(executable: impl AsRef<OsStr>) -> Self {
        Self::from_command(Command::new(executable))
    }

    pub(crate) fn from_command(mut command: Command) -> Self {
        command.env("GIT_OPTIONAL_LOCKS", "0");
        Self { command }
    }

    pub(crate) fn arg(&mut self, argument: impl AsRef<OsStr>) -> &mut Self {
        self.command.arg(argument);
        self
    }

    pub(crate) fn args<I, S>(&mut self, arguments: I) -> &mut Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        self.command.args(arguments);
        self
    }

    pub(crate) fn current_dir(&mut self, directory: impl AsRef<Path>) -> &mut Self {
        self.command.current_dir(directory);
        self
    }

    pub(crate) fn env_remove(&mut self, name: impl AsRef<OsStr>) -> &mut Self {
        self.command.env_remove(name);
        self
    }

    pub(crate) fn output(&mut self) -> io::Result<Output> {
        self.command.output()
    }
}

#[cfg(test)]
#[path = "git/tests.rs"]
mod tests;
