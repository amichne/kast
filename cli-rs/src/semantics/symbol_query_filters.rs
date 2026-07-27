use crate::error::{CliError, Result};
use glob::Pattern;
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum UsageFacet {
    PublicApi,
    InternalApi,
    ModulePrivate,
    Bridge,
    BuildLogic,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct SymbolQueryFilterCriteria<'a> {
    pub(crate) kinds: &'a [String],
    pub(crate) visibility: &'a [String],
    pub(crate) module_path: Option<&'a str>,
    pub(crate) source_set: Option<&'a str>,
    pub(crate) file_glob: Option<&'a str>,
    pub(crate) package_prefix: Option<&'a str>,
    pub(crate) fq_name_prefix: Option<&'a str>,
    pub(crate) gradle_project: Option<&'a str>,
    pub(crate) relative_path_prefix: Option<&'a str>,
    pub(crate) production_only: bool,
    pub(crate) exclude_patterns: &'a [String],
    pub(crate) usage_facets: &'a [UsageFacet],
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct DeclarationFilterInput<'a> {
    pub(crate) fq_name: &'a str,
    pub(crate) kind: &'a str,
    pub(crate) visibility: &'a str,
    pub(crate) absolute_path: &'a str,
    pub(crate) relative_path: &'a str,
    pub(crate) filename: &'a str,
    pub(crate) module_path: Option<&'a str>,
    pub(crate) source_set: Option<&'a str>,
    pub(crate) package_fq_name: Option<&'a str>,
}

#[derive(Debug)]
pub(crate) struct CompiledSymbolQueryFilters<'a> {
    criteria: SymbolQueryFilterCriteria<'a>,
    file_glob: Option<Pattern>,
    exclude_patterns: Vec<Pattern>,
}

impl<'a> CompiledSymbolQueryFilters<'a> {
    pub(crate) fn new(criteria: SymbolQueryFilterCriteria<'a>) -> Result<Self> {
        let file_glob = match criteria.file_glob {
            None => None,
            Some(pattern) if pattern.starts_with("regex:") => {
                return Err(CliError::new(
                    "INVALID_FILTER",
                    "regex: file filters are not supported by symbol/query",
                ));
            }
            Some(pattern) => {
                let normalized = pattern.strip_prefix("glob:").unwrap_or(pattern);
                Some(
                    Pattern::new(normalized)
                        .map_err(|error| CliError::new("INVALID_FILTER", error.to_string()))?,
                )
            }
        };
        let exclude_patterns = criteria
            .exclude_patterns
            .iter()
            .map(|pattern| compile_glob_filter("excludePatterns", pattern))
            .collect::<Result<Vec<_>>>()?;

        Ok(Self {
            criteria,
            file_glob,
            exclude_patterns,
        })
    }

    pub(crate) fn matches(&self, declaration: DeclarationFilterInput<'_>) -> bool {
        let filters = self.criteria;
        if !filters.kinds.is_empty() && !filters.kinds.iter().any(|kind| kind == declaration.kind) {
            return false;
        }
        if !filters.visibility.is_empty()
            && !filters
                .visibility
                .iter()
                .any(|visibility| visibility == declaration.visibility)
        {
            return false;
        }
        if let Some(module_path) = filters.module_path
            && declaration.module_path != Some(module_path)
        {
            return false;
        }
        if let Some(source_set) = filters.source_set
            && declaration.source_set != Some(source_set)
        {
            return false;
        }
        if let Some(package_prefix) = filters.package_prefix
            && !declaration
                .package_fq_name
                .is_some_and(|package| package.starts_with(package_prefix))
        {
            return false;
        }
        if let Some(fq_name_prefix) = filters.fq_name_prefix
            && !declaration.fq_name.starts_with(fq_name_prefix)
        {
            return false;
        }
        if let Some(gradle_project) = filters.gradle_project
            && !declaration
                .module_path
                .is_some_and(|module| gradle_project_matches(module, gradle_project))
        {
            return false;
        }
        if let Some(relative_path_prefix) = filters.relative_path_prefix
            && !relative_path_prefix_matches(declaration.relative_path, relative_path_prefix)
        {
            return false;
        }
        if filters.production_only
            && (declaration.source_set != Some("main") || is_build_logic_location(declaration))
        {
            return false;
        }
        if self
            .exclude_patterns
            .iter()
            .any(|pattern| declaration_location_matches(pattern, declaration))
        {
            return false;
        }
        if let Some(pattern) = self.file_glob.as_ref()
            && !pattern.matches_path(Path::new(declaration.absolute_path))
            && !pattern.matches_path(Path::new(declaration.relative_path))
            && !pattern.matches(declaration.relative_path)
            && !pattern.matches(declaration.filename)
        {
            return false;
        }
        true
    }

    pub(crate) fn usage_facets_match(&self, usage_facets: &[UsageFacet]) -> bool {
        self.criteria.usage_facets.is_empty()
            || self
                .criteria
                .usage_facets
                .iter()
                .any(|requested| usage_facets.contains(requested))
    }
}

pub(crate) fn is_build_logic_location(declaration: DeclarationFilterInput<'_>) -> bool {
    let module_path = declaration.module_path.unwrap_or_default();
    let relative_path = normalize_relative_filter_path(declaration.relative_path);
    module_path == ":build-logic"
        || module_path.starts_with(":build-logic:")
        || module_path == ":buildSrc"
        || module_path.starts_with(":buildSrc:")
        || relative_path == "build-logic"
        || relative_path.starts_with("build-logic/")
        || relative_path == "buildSrc"
        || relative_path.starts_with("buildSrc/")
}

fn compile_glob_filter(field: &str, pattern: &str) -> Result<Pattern> {
    if pattern.starts_with("regex:") {
        return Err(CliError::new(
            "INVALID_FILTER",
            format!("regex: {field} filters are not supported by symbol/query"),
        ));
    }
    let normalized = pattern.strip_prefix("glob:").unwrap_or(pattern);
    Pattern::new(normalized)
        .map_err(|error| CliError::new("INVALID_FILTER", format!("{field}: {error}")))
}

fn gradle_project_matches(module_path: &str, gradle_project: &str) -> bool {
    let module_path = module_path
        .split_once('[')
        .map_or(module_path, |(project, _)| project);
    module_path == gradle_project
        || module_path
            .strip_prefix(gradle_project)
            .is_some_and(|suffix| suffix.starts_with(':'))
}

fn relative_path_prefix_matches(relative_path: &str, prefix: &str) -> bool {
    let relative_path = normalize_relative_filter_path(relative_path);
    let prefix = normalize_relative_filter_path(prefix);
    if prefix.ends_with('/') {
        relative_path.starts_with(&prefix)
    } else {
        relative_path == prefix || relative_path.starts_with(&format!("{prefix}/"))
    }
}

fn declaration_location_matches(
    pattern: &Pattern,
    declaration: DeclarationFilterInput<'_>,
) -> bool {
    declaration
        .module_path
        .is_some_and(|module| pattern.matches(module))
        || pattern.matches(&normalize_relative_filter_path(declaration.relative_path))
        || pattern.matches_path(Path::new(declaration.relative_path))
}

fn normalize_relative_filter_path(path: &str) -> String {
    path.replace('\\', "/").trim_start_matches("./").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gradle_project_filter_matches_exact_project_and_children_only() {
        let filters = filters(|criteria| {
            criteria.gradle_project = Some(":lib");
        });

        assert!(filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":lib");
        })));
        assert!(filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":lib:payments");
        })));
        assert!(filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":lib[main]");
        })));
        assert!(filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":lib:payments[test]");
        })));
        assert!(!filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":library");
        })));
        assert!(!filters.matches(declaration(|declaration| {
            declaration.module_path = None;
        })));
    }

    #[test]
    fn relative_path_filter_matches_normalized_path_segments() {
        let filters = filters(|criteria| {
            criteria.relative_path_prefix = Some("./lib/payments");
        });

        assert!(filters.matches(declaration(|declaration| {
            declaration.relative_path = "lib/payments/CardPaymentProcessor.kt";
        })));
        assert!(filters.matches(declaration(|declaration| {
            declaration.relative_path = "lib\\payments\\CardPaymentProcessor.kt";
        })));
        assert!(!filters.matches(declaration(|declaration| {
            declaration.relative_path = "lib/payment/CardPaymentProcessor.kt";
        })));
    }

    #[test]
    fn production_only_requires_main_sources_and_excludes_build_logic() {
        let filters = filters(|criteria| {
            criteria.production_only = true;
        });

        assert!(filters.matches(declaration(|declaration| {
            declaration.source_set = Some("main");
            declaration.module_path = Some(":lib");
        })));
        assert!(!filters.matches(declaration(|declaration| {
            declaration.source_set = Some("test");
            declaration.module_path = Some(":lib");
        })));
        assert!(!filters.matches(declaration(|declaration| {
            declaration.source_set = Some("main");
            declaration.module_path = Some(":build-logic");
        })));
    }

    #[test]
    fn exclude_patterns_reject_module_paths_and_relative_paths() {
        let exclude_patterns = vec![":build-logic".to_string(), "lib/internal/**".to_string()];
        let mut criteria = default_criteria();
        criteria.exclude_patterns = &exclude_patterns;
        let filters = CompiledSymbolQueryFilters::new(criteria).expect("compiled filters");

        assert!(!filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":build-logic");
            declaration.relative_path = "build-logic/Task.kt";
        })));
        assert!(!filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":lib");
            declaration.relative_path = "lib/internal/Hidden.kt";
        })));
        assert!(filters.matches(declaration(|declaration| {
            declaration.module_path = Some(":lib");
            declaration.relative_path = "lib/Public.kt";
        })));
    }

    #[test]
    fn usage_facet_filter_accepts_any_requested_facet() {
        let filters = filters(|criteria| {
            criteria.usage_facets = &[UsageFacet::Bridge, UsageFacet::BuildLogic];
        });

        assert!(filters.usage_facets_match(&[UsageFacet::PublicApi, UsageFacet::Bridge]));
        assert!(!filters.usage_facets_match(&[UsageFacet::InternalApi]));
    }

    fn filters(
        configure: impl FnOnce(&mut SymbolQueryFilterCriteria<'static>),
    ) -> CompiledSymbolQueryFilters<'static> {
        let mut criteria = default_criteria();
        configure(&mut criteria);
        CompiledSymbolQueryFilters::new(criteria).expect("compiled filters")
    }

    fn default_criteria() -> SymbolQueryFilterCriteria<'static> {
        SymbolQueryFilterCriteria {
            kinds: &[],
            visibility: &[],
            module_path: None,
            source_set: None,
            file_glob: None,
            package_prefix: None,
            fq_name_prefix: None,
            gradle_project: None,
            relative_path_prefix: None,
            production_only: false,
            exclude_patterns: &[],
            usage_facets: &[],
        }
    }

    fn declaration(
        configure: impl FnOnce(&mut DeclarationFilterInput<'static>),
    ) -> DeclarationFilterInput<'static> {
        let mut declaration = DeclarationFilterInput {
            fq_name: "lib.CardPaymentProcessor",
            kind: "CLASS",
            visibility: "PUBLIC",
            absolute_path: "/workspace/lib/CardPaymentProcessor.kt",
            relative_path: "lib/CardPaymentProcessor.kt",
            filename: "CardPaymentProcessor.kt",
            module_path: Some(":lib"),
            source_set: Some("main"),
            package_fq_name: Some("lib"),
        };
        configure(&mut declaration);
        declaration
    }
}
