# Makefile to produce a single distributable unit for the analysis-cli module.
# Targets:
#   make cli         -> build fat JAR, emit wrapper, package into dist/analysis-cli
#   make cli-zip     -> build a single portable zip in dist/analysis-cli.zip
#   make clean-dist  -> remove dist/
#   make run         -> run packaged CLI (help)

.PHONY: all cli cli-zip clean-dist run
all: cli

CLI_NAME=analysis-cli
MODULE_DIR=analysis-cli
BUILD_DIR=$(MODULE_DIR)/build
SCRIPTS_DIR=$(BUILD_DIR)/scripts
LIBS_DIR=$(BUILD_DIR)/libs
RUNTIME_LIBS_DIR=$(BUILD_DIR)/runtime-libs
DIST_DIR=dist/$(CLI_NAME)
TMP_DIST_DIR=$(DIST_DIR).tmp
DIST_ZIP=dist/$(CLI_NAME).zip

cli:
	@echo "Building fat jar and wrapper for $(CLI_NAME)"
	./gradlew :$(CLI_NAME):writeWrapperScript --no-configuration-cache
	@echo "Packaging into $(DIST_DIR)"
	rm -rf $(TMP_DIST_DIR)
	mkdir -p $(TMP_DIST_DIR)/libs
	mkdir -p $(TMP_DIST_DIR)/runtime-libs
	cp $(SCRIPTS_DIR)/$(CLI_NAME) $(TMP_DIST_DIR)/$(CLI_NAME)
	chmod +x $(TMP_DIST_DIR)/$(CLI_NAME)
	cp $(LIBS_DIR)/*-all.jar $(TMP_DIST_DIR)/libs/
	cp $(RUNTIME_LIBS_DIR)/* $(TMP_DIST_DIR)/runtime-libs/
	rm -rf $(DIST_DIR)
	mv $(TMP_DIST_DIR) $(DIST_DIR)
	@echo "Packaged $(CLI_NAME) into $(DIST_DIR)"

cli-zip:
	@echo "Building portable zip for $(CLI_NAME)"
	./gradlew :$(CLI_NAME):syncPortableDist --no-configuration-cache
	mkdir -p dist
	rm -f $(DIST_ZIP)
	cd $(BUILD_DIR)/portable-dist && zip -qr $(abspath $(DIST_ZIP)) $(CLI_NAME)
	@echo "Packaged $(CLI_NAME) into $(DIST_ZIP)"

clean-dist:
	rm -rf dist

run:
	@echo "Running packaged $(CLI_NAME) (if present)"
	$(DIST_DIR)/$(CLI_NAME) --help
