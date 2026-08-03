#[cfg(test)]
mod tests {
    use super::*;

    #[path = "schema_basics.rs"]
    mod schema_basics;
    #[path = "variant_contracts.rs"]
    mod variant_contracts;
}
