.PHONY: help clean test build publish publish-plan publish-local publish-snapshot publish-release publish-github validate-publish compile compile-test detekt detekt-baseline docs-serve docs-build docs-clean docs-install all full-clean rebuild check publish-version-none publish-version-snapshot publish-version-patch publish-version-minor publish-version-major publish-version-patch-snapshot publish-version-minor-snapshot publish-version-major-snapshot publish-validate-local publish-validate-snapshot publish-validate-release publish-validate-github publish-run-local publish-run-snapshot publish-run-release publish-run-github publish-gradle-validate publish-gradle-smoke

.DEFAULT_GOAL := help

VENV_DIR := docs/venv
PYTHON := python3
VENV_BIN := $(VENV_DIR)/bin
PIP := $(VENV_BIN)/pip
ZENSICAL := $(VENV_BIN)/zensical
GRADLEW := ./gradlew
REQUIREMENTS := docs/requirements.txt
GRADLE_FLAGS := --no-daemon --stacktrace

BLUE := \033[0;34m
GREEN := \033[0;32m
YELLOW := \033[0;33m
NC := \033[0m

##@ General

help: ## Display this help message
	@echo "$(BLUE)Konditional Makefile$(NC)"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make $(GREEN)<target>$(NC)\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  $(GREEN)%-28s$(NC) %s\n", $$1, $$2 } /^##@/ { printf "\n$(YELLOW)%s$(NC)\n", substr($$0, 5) } ' $(MAKEFILE_LIST)

all: clean build test docs-build ## Clean, build, test, and generate docs

##@ Gradle Tasks

clean: ## Clean build artifacts
	@echo "$(BLUE)Cleaning build artifacts...$(NC)"
	$(GRADLEW) clean

build: ## Build the project
	@echo "$(BLUE)Building project...$(NC)"
	$(GRADLEW) build

test: ## Run tests
	@echo "$(BLUE)Running tests...$(NC)"
	$(GRADLEW) test

compile: ## Compile Kotlin code
	@echo "$(BLUE)Compiling Kotlin code...$(NC)"
	$(GRADLEW) compileKotlin

compile-test: ## Compile test code
	@echo "$(BLUE)Compiling test code...$(NC)"
	$(GRADLEW) compileTestKotlin

detekt: ## Run Detekt static analysis
	@echo "$(BLUE)Running Detekt...$(NC)"
	@if $(GRADLEW) tasks --all --no-daemon | grep -qE '(^|[[:space:]])detekt([[:space:]]|$$)'; then \
		$(GRADLEW) detekt; \
	else \
		echo "$(YELLOW)No Detekt tasks are currently configured in this extracted module set$(NC)"; \
	fi

detekt-baseline: ## Generate Detekt baseline (suppress existing issues)
	@echo "$(BLUE)Generating Detekt baseline...$(NC)"
	@if $(GRADLEW) tasks --all --no-daemon | grep -qE '(^|[[:space:]])detektBaseline([[:space:]]|$$)'; then \
		$(GRADLEW) detektBaseline; \
		echo "$(GREEN)Detekt baseline generated at detekt-baseline.xml$(NC)"; \
	else \
		echo "$(YELLOW)No Detekt baseline task is currently configured in this extracted module set$(NC)"; \
	fi

##@ Publishing

publish: ## Canonical on-rails publish entrypoint (interactive, uses fzf when available)
	@./scripts/publish-on-rails.sh

publish-plan: ## Non-interactive publish. Usage: make publish-plan PUBLISH_TARGET=release VERSION_CHOICE=patch
	@args=""; \
	if [ -n "$(PUBLISH_TARGET)" ]; then args="$$args --target $(PUBLISH_TARGET)"; fi; \
	if [ -n "$(VERSION_CHOICE)" ]; then args="$$args --version-choice $(VERSION_CHOICE)"; fi; \
	./scripts/publish-on-rails.sh $$args

validate-publish: publish-validate-release ## Validate publish prerequisites for release target

publish-local: publish-run-local ## Publish to local Maven repository (~/.m2)
publish-snapshot: publish-run-snapshot ## Publish SNAPSHOT to Maven Central
publish-release: publish-run-release ## Publish release to Maven Central
publish-github: publish-run-github ## Publish to GitHub Packages

publish-version-none: ## Version node: no version change
	@./scripts/bump-version.sh none

publish-version-snapshot: ## Version node: no bump, force -SNAPSHOT
	@./scripts/bump-version.sh none --snapshot

publish-version-patch: ## Version node: bump patch
	@./scripts/bump-version.sh patch

publish-version-minor: ## Version node: bump minor
	@./scripts/bump-version.sh minor

publish-version-major: ## Version node: bump major
	@./scripts/bump-version.sh major

publish-version-patch-snapshot: ## Version node: bump patch and set -SNAPSHOT
	@./scripts/bump-version.sh patch --snapshot

publish-version-minor-snapshot: ## Version node: bump minor and set -SNAPSHOT
	@./scripts/bump-version.sh minor --snapshot

publish-version-major-snapshot: ## Version node: bump major and set -SNAPSHOT
	@./scripts/bump-version.sh major --snapshot

publish-validate-local: ## Validate prerequisites for local publish
	@./scripts/validate-publish.sh local

publish-validate-snapshot: ## Validate prerequisites for snapshot publish
	@./scripts/validate-publish.sh snapshot

publish-validate-release: ## Validate prerequisites for release publish
	@./scripts/validate-publish.sh release

publish-validate-github: ## Validate prerequisites for GitHub publish
	@./scripts/validate-publish.sh github

publish-run-local: publish-validate-local ## Execute local publish
	@echo "$(BLUE)Publishing to local Maven...$(NC)"
	$(GRADLEW) publishToMavenLocal $(GRADLE_FLAGS) -Pkonditional.publish.target=local

publish-run-snapshot: publish-validate-snapshot ## Execute snapshot publish to Maven Central
	@echo "$(BLUE)Publishing snapshot to Maven Central...$(NC)"
	$(GRADLEW) publishToMavenCentral $(GRADLE_FLAGS) -Pkonditional.publish.target=snapshot

publish-run-release: publish-validate-release ## Execute release publish to Maven Central
	@echo "$(BLUE)Publishing release to Maven Central...$(NC)"
	$(GRADLEW) publishAndReleaseToMavenCentral $(GRADLE_FLAGS) -Pkonditional.publish.target=release

publish-run-github: publish-validate-github ## Execute GitHub Packages publish
	@echo "$(BLUE)Publishing to GitHub Packages...$(NC)"
	$(GRADLEW) publishAllPublicationsToGitHubPackagesRepository $(GRADLE_FLAGS) -Pkonditional.publish.target=github

publish-gradle-validate: ## Internal: resolve target publish task graph via Gradle
	@case "$(TARGET)" in \
		local) task="publishToMavenLocal" ;; \
		snapshot) task="publishToMavenCentral" ;; \
		release) task="publishAndReleaseToMavenCentral" ;; \
		github) task="publishAllPublicationsToGitHubPackagesRepository" ;; \
		*) echo "Invalid TARGET='$(TARGET)'. Expected local|snapshot|release|github." >&2; exit 1 ;; \
	esac; \
	$(GRADLEW) help --task $$task --no-daemon -Pkonditional.publish.target=$(TARGET) >/dev/null

publish-gradle-smoke: ## Internal: smoke-check publish graph without remote push
	@$(GRADLEW) publishToMavenLocal --no-daemon -Pkonditional.publish.target=local >/dev/null

##@ Documentation

docs-install: ## Install Zensical into docs/venv
	@echo "$(BLUE)Installing Zensical docs toolchain...$(NC)"
	@test -d "$(VENV_DIR)" || $(PYTHON) -m venv "$(VENV_DIR)"
	@$(PIP) install --upgrade pip
	@$(PIP) install -r "$(REQUIREMENTS)"
	@echo "$(GREEN)Zensical docs toolchain installed$(NC)"

docs-build: docs-install ## Build the Zensical docs site
	@echo "$(BLUE)Building Zensical site...$(NC)"
	@$(ZENSICAL) build --clean
	@echo "$(GREEN)Zensical site built successfully$(NC)"

docs-serve: docs-install ## Serve docs locally
	@echo "$(BLUE)Starting Zensical docs server...$(NC)"
	@$(ZENSICAL) serve

docs-clean: ## Clean generated documentation
	@echo "$(BLUE)Cleaning documentation...$(NC)"
	@rm -rf site/
	@echo "$(GREEN)Documentation cleaned$(NC)"

##@ Combined Tasks

full-clean: clean docs-clean ## Clean everything (build + docs)
	@echo "$(GREEN)Full clean completed$(NC)"

rebuild: clean build ## Clean and rebuild
	@echo "$(GREEN)Rebuild completed$(NC)"

check: detekt test ## Static analysis + tests
	@echo "$(GREEN)Check completed$(NC)"
