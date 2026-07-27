#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(target_os = "macos")]
    use std::ffi::OsString;

    include!("tests/backend_selection.rs");
    include!("tests/idea_lifecycle.rs");
}
