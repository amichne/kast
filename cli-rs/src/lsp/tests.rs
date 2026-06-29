#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    include!("tests/support.rs");
    include!("tests/protocol.rs");
    include!("tests/initialize_and_routes.rs");
    include!("tests/read_operations.rs");
    include!("tests/rename.rs");
    include!("tests/hierarchy.rs");
    include!("tests/failure_modes.rs");
}
