mod exposure;
mod generate;

use crate::cli::{CodexCommand, OutputFormat};
use crate::error::Result;

pub(crate) use exposure::classify_command;

pub(crate) fn run(command: CodexCommand, output_format: OutputFormat) -> Result<i32> {
    match command {
        CodexCommand::Generate(args) => {
            let report = generate::run(args)?;
            crate::output::print_structured(&report, output_format)?;
            Ok(0)
        }
    }
}
