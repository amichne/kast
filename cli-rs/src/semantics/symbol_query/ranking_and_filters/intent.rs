#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum SymbolDiscoveryFamily {
    Type,
    Callable,
}

impl SymbolDiscoveryFamily {
    pub(crate) fn from_word(word: &str) -> Option<Self> {
        match word.to_ascii_lowercase().as_str() {
            "type" | "types" | "model" | "models" | "class" | "classes" | "interface"
            | "interfaces" | "object" | "objects" | "enum" | "enums" | "alias" | "aliases" => {
                Some(Self::Type)
            }
            "function" | "functions" | "helper" | "helpers" | "method" | "methods"
            | "callable" | "callables" | "constructor" | "constructors" | "getter" | "getters"
            | "setter" | "setters" => Some(Self::Callable),
            _ => None,
        }
    }

    pub(crate) fn admits(self, declaration_kind: &str) -> bool {
        match self {
            Self::Type => matches!(
                declaration_kind,
                "CLASS" | "ENUM_CLASS" | "INTERFACE" | "OBJECT" | "TYPE_ALIAS"
            ),
            Self::Callable => matches!(
                declaration_kind,
                "FUNCTION" | "MEMBER_FUNCTION" | "CONSTRUCTOR" | "GETTER" | "SETTER"
            ),
        }
    }
}

#[derive(Clone, Copy)]
pub(crate) enum SymbolDiscoveryIntent {
    Unspecified,
    Single(SymbolDiscoveryFamily),
    OrderedMixed { target: SymbolDiscoveryFamily },
}

impl SymbolDiscoveryIntent {
    pub(crate) fn parse(question: &str) -> Self {
        let mut families = question
            .split(|character: char| !character.is_alphanumeric())
            .filter_map(SymbolDiscoveryFamily::from_word);
        let Some(target) = families.next() else {
            return Self::Unspecified;
        };
        if families.any(|family| family != target) {
            Self::OrderedMixed { target }
        } else {
            Self::Single(target)
        }
    }

    pub(crate) fn target_family(self) -> Option<SymbolDiscoveryFamily> {
        match self {
            Self::Unspecified => None,
            Self::Single(target) | Self::OrderedMixed { target } => Some(target),
        }
    }

    pub(crate) fn is_mixed(self) -> bool {
        matches!(self, Self::OrderedMixed { .. })
    }
}
