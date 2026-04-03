# Makefile to produce a single distributable unit for the kast CLI.
# Targets:
#   make cli              -> build a clean staged CLI tree and publish dist/kast
#   make cli-zip          -> build a clean staged CLI tree and publish dist/kast.zip
#   make clean-dist       -> remove dist/
#   make run              -> build dist/kast and run --help
#   make stage-cli        -> build kast/build/portable-dist/kast
#   make verify-cli-stage -> fail fast if the staged CLI tree is incomplete

SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c

.PHONY: all cli cli-zip clean-dist run stage-cli verify-cli-stage
all: cli

CLI_NAME := kast
MODULE_DIR := kast
BUILD_DIR := $(MODULE_DIR)/build
PORTABLE_DIST_DIR := $(BUILD_DIR)/portable-dist/$(CLI_NAME)
STAGED_JAR_GLOB := $(PORTABLE_DIST_DIR)/libs/$(CLI_NAME)-*-all.jar
GRADLEW := ./gradlew
GRADLE_ARGS := --no-configuration-cache
GRADLE_STAGE_TASK := stageCliDist
DIST_ROOT := dist
DIST_DIR := $(DIST_ROOT)/$(CLI_NAME)
TMP_DIST_DIR := $(DIST_DIR).tmp
DIST_ZIP := $(DIST_ROOT)/$(CLI_NAME).zip

stage-cli:
	@echo "Building a clean staged CLI tree for $(CLI_NAME)"
	$(GRADLEW) $(GRADLE_STAGE_TASK) $(GRADLE_ARGS)

verify-cli-stage: stage-cli
	@echo "Verifying staged CLI tree in $(PORTABLE_DIST_DIR)"
	test -x "$(PORTABLE_DIST_DIR)/$(CLI_NAME)"
	test -d "$(PORTABLE_DIST_DIR)/bin"
	test -x "$(PORTABLE_DIST_DIR)/bin/kast-helper"
	test -d "$(PORTABLE_DIST_DIR)/runtime-libs"
	test -f "$(PORTABLE_DIST_DIR)/runtime-libs/classpath.txt"
	shopt -s nullglob; jars=( $(STAGED_JAR_GLOB) ); [[ $${#jars[@]} -eq 1 ]]

cli: verify-cli-stage
	@echo "Publishing staged CLI tree into $(DIST_DIR)"
	rm -rf "$(TMP_DIST_DIR)"
	mkdir -p "$(DIST_ROOT)"
	cp -R "$(PORTABLE_DIST_DIR)" "$(TMP_DIST_DIR)"
	rm -rf "$(DIST_DIR)"
	mv "$(TMP_DIST_DIR)" "$(DIST_DIR)"
	@echo "Packaged $(CLI_NAME) into $(DIST_DIR)"

cli-zip: verify-cli-stage
	@echo "Packaging staged CLI tree into $(DIST_ZIP)"
	mkdir -p "$(DIST_ROOT)"
	rm -f "$(DIST_ZIP)"
	cd "$(BUILD_DIR)/portable-dist" && zip -qr "$(abspath $(DIST_ZIP))" "$(CLI_NAME)"
	@echo "Packaged $(CLI_NAME) into $(DIST_ZIP)"

clean-dist:
	rm -rf dist

run: cli
	@echo "Running packaged $(CLI_NAME)"
	"$(DIST_DIR)/$(CLI_NAME)" --help
