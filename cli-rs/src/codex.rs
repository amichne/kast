mod hook;

use crate::cli::{CodexCommand, OutputFormat};
use crate::error::Result;

pub(crate) fn run(command: CodexCommand, output_format: OutputFormat) -> Result<i32> {
    let _ = output_format;
    match command {
        CodexCommand::Hook(args) => hook::run(args.event),
    }
}
