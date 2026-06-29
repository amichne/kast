#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lexical_tokens_split_package_punctuation_snake_and_camel_boundaries() {
        let tokens =
            lexical_tokens("io.github.payments.CardPaymentProcessor card_payment_processor.kt");

        assert_eq!(
            tokens,
            vec![
                "io",
                "github",
                "payments",
                "card",
                "payment",
                "processor",
                "kt",
            ]
        );
    }

    #[test]
    fn lexical_tokens_lowercase_ascii_and_deduplicate_per_field() {
        let tokens = lexical_tokens("CardPaymentProcessor card CARD paymentProcessor");

        assert_eq!(tokens, vec!["card", "payment", "processor"]);
    }
}
