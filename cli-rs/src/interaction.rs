use crate::cli::OutputFormat;
use crate::error::Result;
use std::io::{self, BufRead, IsTerminal, Write};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PromptPolicy {
    Enabled,
    Disabled,
}

impl PromptPolicy {
    pub(crate) fn current(output_format: OutputFormat) -> Self {
        Self::from_streams(
            output_format,
            io::stdin().is_terminal(),
            io::stdout().is_terminal(),
        )
    }

    pub(crate) fn from_streams(
        output_format: OutputFormat,
        stdin_is_terminal: bool,
        stdout_is_terminal: bool,
    ) -> Self {
        if output_format == OutputFormat::Human && stdin_is_terminal && stdout_is_terminal {
            Self::Enabled
        } else {
            Self::Disabled
        }
    }

    pub(crate) fn is_enabled(self) -> bool {
        matches!(self, Self::Enabled)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Confirmation {
    Accepted,
    Declined,
}

pub(crate) fn confirm_affected_install_apply(action_count: usize) -> Result<Confirmation> {
    let stdin = io::stdin();
    let mut stdin = stdin.lock();
    let stderr = io::stderr();
    let mut stderr = stderr.lock();
    confirm_affected_install_apply_with_io(action_count, &mut stdin, &mut stderr)
}

fn confirm_affected_install_apply_with_io(
    action_count: usize,
    reader: &mut impl BufRead,
    writer: &mut impl Write,
) -> Result<Confirmation> {
    write!(
        writer,
        "Apply {action_count} planned Kast install repair{} now? [y/N] ",
        if action_count == 1 { "" } else { "s" }
    )?;
    writer.flush()?;

    let mut answer = String::new();
    reader.read_line(&mut answer)?;
    if matches!(answer.trim(), "y" | "Y" | "yes" | "YES" | "Yes") {
        Ok(Confirmation::Accepted)
    } else {
        Ok(Confirmation::Declined)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scoped_interactive_prompt_policy_requires_human_tty_streams() {
        assert_eq!(
            PromptPolicy::from_streams(OutputFormat::Human, true, true),
            PromptPolicy::Enabled
        );
        assert_eq!(
            PromptPolicy::from_streams(OutputFormat::Json, true, true),
            PromptPolicy::Disabled
        );
        assert_eq!(
            PromptPolicy::from_streams(OutputFormat::Human, false, true),
            PromptPolicy::Disabled
        );
        assert_eq!(
            PromptPolicy::from_streams(OutputFormat::Human, true, false),
            PromptPolicy::Disabled
        );
    }

    #[test]
    fn affected_install_confirmation_accepts_yes_only() {
        let mut input = std::io::Cursor::new("yes\n");
        let mut output = Vec::new();

        let confirmation = confirm_affected_install_apply_with_io(2, &mut input, &mut output)
            .expect("confirmation");

        assert_eq!(confirmation, Confirmation::Accepted);
        assert_eq!(
            String::from_utf8(output).expect("prompt utf8"),
            "Apply 2 planned Kast install repairs now? [y/N] "
        );
    }

    #[test]
    fn affected_install_confirmation_defaults_to_decline() {
        let mut input = std::io::Cursor::new("\n");
        let mut output = Vec::new();

        let confirmation = confirm_affected_install_apply_with_io(1, &mut input, &mut output)
            .expect("confirmation");

        assert_eq!(confirmation, Confirmation::Declined);
        assert_eq!(
            String::from_utf8(output).expect("prompt utf8"),
            "Apply 1 planned Kast install repair now? [y/N] "
        );
    }
}
