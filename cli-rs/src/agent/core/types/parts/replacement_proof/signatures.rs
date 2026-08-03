#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementVisibility {
    Public,
    Protected,
    Internal,
    PackageProtected,
    PackagePrivate,
    Private,
    Local,
}
#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementModality {
    Final,
    Sealed,
    Open,
    Abstract,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum AgentReplacementTypeVariance {
    Invariant,
    In,
    Out,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementTypeParameterSignature {
    name: String,
    upper_bounds: String,
    variance: AgentReplacementTypeVariance,
    reified: bool,
}

impl AgentReplacementTypeParameterSignature {
    fn is_valid(&self) -> bool {
        is_exact_replacement_name(&self.name) && !self.upper_bounds.trim().is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AgentReplacementValueParameterSignature {
    name: String,
    #[serde(rename = "type")]
    parameter_type: String,
    vararg: bool,
    has_default_value: bool,
    noinline: bool,
    crossinline: bool,
}

impl AgentReplacementValueParameterSignature {
    fn is_valid(&self) -> bool {
        is_exact_replacement_name(&self.name) && !self.parameter_type.trim().is_empty()
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all_fields = "camelCase", deny_unknown_fields)]
enum AgentReplacementDeclarationSignature {
    #[serde(rename = "function")]
    Function {
        name: String,
        receiver_type: Option<String>,
        context_receiver_types: Vec<String>,
        type_parameters: Vec<AgentReplacementTypeParameterSignature>,
        value_parameters: Vec<AgentReplacementValueParameterSignature>,
        return_type: String,
        visibility: AgentReplacementVisibility,
        modality: AgentReplacementModality,
        has_stable_parameter_names: bool,
        suspend: bool,
        operator: bool,
        inline: bool,
        #[serde(rename = "override")]
        is_override: bool,
        infix: bool,
        #[serde(rename = "static")]
        is_static: bool,
        tailrec: bool,
        external: bool,
        expect: bool,
        actual: bool,
    },
    #[serde(rename = "property")]
    Property {
        name: String,
        receiver_type: Option<String>,
        context_receiver_types: Vec<String>,
        type_parameters: Vec<AgentReplacementTypeParameterSignature>,
        return_type: String,
        visibility: AgentReplacementVisibility,
        modality: AgentReplacementModality,
        getter_visibility: AgentReplacementVisibility,
        setter_visibility: Option<AgentReplacementVisibility>,
        has_getter: bool,
        has_setter: bool,
        has_backing_field: bool,
        is_val: bool,
        #[serde(rename = "const")]
        is_const: bool,
        lateinit: bool,
        delegated: bool,
        #[serde(rename = "override")]
        is_override: bool,
        #[serde(rename = "static")]
        is_static: bool,
        external: bool,
        expect: bool,
        actual: bool,
    },
}

impl AgentReplacementDeclarationSignature {
    fn is_valid_for(&self, kind: AgentReplacementSymbolKind) -> bool {
        match self {
            Self::Function {
                name,
                receiver_type,
                context_receiver_types,
                type_parameters,
                value_parameters,
                return_type,
                ..
            } => {
                kind == AgentReplacementSymbolKind::Function
                    && is_valid_replacement_signature_header(
                        name,
                        receiver_type.as_deref(),
                        context_receiver_types,
                        type_parameters,
                        return_type,
                    )
                    && value_parameters
                        .iter()
                        .all(AgentReplacementValueParameterSignature::is_valid)
            }
            Self::Property {
                name,
                receiver_type,
                context_receiver_types,
                type_parameters,
                return_type,
                ..
            } => {
                kind == AgentReplacementSymbolKind::Property
                    && is_valid_replacement_signature_header(
                        name,
                        receiver_type.as_deref(),
                        context_receiver_types,
                        type_parameters,
                        return_type,
                    )
            }
        }
    }
}

fn is_valid_replacement_signature_header(
    name: &str,
    receiver_type: Option<&str>,
    context_receiver_types: &[String],
    type_parameters: &[AgentReplacementTypeParameterSignature],
    return_type: &str,
) -> bool {
    is_exact_replacement_name(name)
        && receiver_type.is_none_or(|value| !value.trim().is_empty())
        && context_receiver_types
            .iter()
            .all(|value| !value.trim().is_empty())
        && type_parameters
            .iter()
            .all(AgentReplacementTypeParameterSignature::is_valid)
        && !return_type.trim().is_empty()
}
