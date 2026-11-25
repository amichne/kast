package agent

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
)

// The MCP server as the plugin's .mcp.json declares it. Pi has no plugin form for a server, so its command adds one.
const mcpScript = `PATH="${HOME}/.local/bin:${PATH}" exec kast --internal-entrypoint=mcp`

var claude = harness{
	key: "claude", name: "Claude Code", program: "claude",
	observe:    observeLists(readClaude),
	connect:    connectClaude,
	disconnect: disconnectMarketplace([]string{"plugin", "uninstall", plugin}),
	after:      "Start a new Claude Code session to load it.",
}

var codex = harness{
	key: "codex", name: "Codex", program: "codex",
	observe:    observeLists(readCodex),
	connect:    connectCodex,
	disconnect: disconnectMarketplace([]string{"plugin", "remove", plugin}),
	after:      "Start a new Codex session, then run /hooks to review the Kast session hook.",
}

var copilot = harness{
	key: "copilot", name: "GitHub Copilot", program: "copilot",
	observe:    observeLists(readCopilot),
	connect:    connectCopilot,
	disconnect: disconnectCopilot,
	after:      "Start a new Copilot session in a trusted folder to load it.",
}

var pi = harness{
	key: "pi", name: "Pi", program: "pi",
	observe:    observePi,
	connect:    connectPi,
	disconnect: disconnectPi,
	after:      "Start a new Pi session to load it.",
}

// Claude Code takes over a marketplace of the same name. Its install command keeps an installed version, and its update
// command moves it. A direct server in the user scope would run beside the plugin's server.
func connectClaude(ctx context.Context, call caller, current state, composition string, _ bool) error {
	if err := call.clear(ctx, "mcp", "remove", "-s", "user", marketplace); err != nil {
		return err
	}
	if current.source != composition {
		if err := call.command(ctx, "plugin", "marketplace", "add", composition); err != nil {
			return err
		}
	}
	if err := call.command(ctx, "plugin", "install", plugin); err != nil || !current.installed {
		return err
	}
	return call.command(ctx, "plugin", "update", plugin)
}

// Codex refuses a second source under one name, and adding the plugin again replaces its copy.
func connectCodex(ctx context.Context, call caller, current state, composition string, _ bool) error {
	if err := call.clear(ctx, "mcp", "remove", marketplace); err != nil {
		return err
	}
	if current.source != "" && current.source != composition {
		if err := call.command(ctx, "plugin", "marketplace", "remove", marketplace); err != nil {
			return err
		}
		current.source = ""
	}
	if current.source == "" {
		if err := call.command(ctx, "plugin", "marketplace", "add", composition); err != nil {
			return err
		}
	}
	return call.command(ctx, "plugin", "add", plugin)
}

// Copilot refuses a second source under one name, and loads a local plugin in place.
func connectCopilot(ctx context.Context, call caller, current state, composition string, _ bool) error {
	if err := call.clear(ctx, "mcp", "remove", marketplace); err != nil {
		return err
	}
	if current.source != "" && current.source != composition {
		if err := call.command(ctx, "plugin", "marketplace", "remove", marketplace, "--force"); err != nil {
			return err
		}
		current.source = ""
	}
	if current.source == "" {
		if err := call.command(ctx, "plugin", "marketplace", "add", composition); err != nil {
			return err
		}
	}
	return call.command(ctx, "plugin", "install", plugin)
}

// Pi identifies a local package by its path, so another layout must leave first. Its server entry replaces itself.
func connectPi(ctx context.Context, call caller, current state, composition string, withMCP bool) error {
	if current.source != "" && current.source != composition {
		if err := call.command(ctx, "remove", packagePath(current.source)); err != nil {
			return err
		}
	}
	if err := call.command(ctx, "install", packagePath(composition)); err != nil {
		return err
	}
	if withMCP {
		return call.command(ctx, "mcp", "add", marketplace, "--", "/bin/bash", "-c", mcpScript)
	}
	return call.clear(ctx, "mcp", "remove", marketplace)
}

func disconnectMarketplace(uninstall []string) func(context.Context, caller, state) error {
	return func(ctx context.Context, call caller, current state) error {
		if current.installed {
			if err := call.command(ctx, uninstall...); err != nil {
				return err
			}
		}
		if current.source == "" {
			return nil
		}
		return call.command(ctx, "plugin", "marketplace", "remove", marketplace)
	}
}

// Copilot keeps a disabled plugin of a live marketplace, so the marketplace removal also uninstalls it.
func disconnectCopilot(ctx context.Context, call caller, current state) error {
	if current.source == "" {
		return call.command(ctx, "plugin", "uninstall", plugin)
	}
	return call.command(ctx, "plugin", "marketplace", "remove", marketplace, "--force")
}

func disconnectPi(ctx context.Context, call caller, current state) error {
	if err := call.command(ctx, "remove", packagePath(current.source)); err != nil {
		return err
	}
	return call.clear(ctx, "mcp", "remove", marketplace)
}

func packagePath(composition string) string {
	return filepath.Join(composition, filepath.FromSlash(pluginPath))
}

// observeLists reads both lists before any effect.
func observeLists(read func(marketplaces, plugins []byte) (state, error)) func(context.Context, caller) (state, error) {
	return func(ctx context.Context, call caller) (state, error) {
		if err := call.available(); err != nil {
			return state{}, err
		}
		marketplaces, err := call.run(ctx, "plugin", "marketplace", "list", "--json")
		if err != nil {
			return state{}, err
		}
		plugins, err := call.run(ctx, "plugin", "list", "--json")
		if err != nil {
			return state{}, err
		}
		current, err := read(marketplaces, plugins)
		if err != nil {
			return state{}, call.unreadable(err)
		}
		return current, nil
	}
}

func readClaude(marketplaces, plugins []byte) (state, error) {
	var listed []struct {
		Name   string `json:"name"`
		Source string `json:"source"`
		Path   string `json:"path"`
	}
	var added []struct {
		ID      string `json:"id"`
		Version string `json:"version"`
	}
	if err := decode(marketplaces, &listed); err != nil {
		return state{}, err
	}
	if err := decode(plugins, &added); err != nil {
		return state{}, err
	}
	var current state
	for _, entry := range listed {
		if entry.Name == marketplace {
			// Only a directory has a path, so another source keeps a name that no Kast layout ends with.
			current.source = entry.Path
			if entry.Path == "" {
				current.source = "a " + entry.Source + " source"
			}
		}
	}
	for _, entry := range added {
		if entry.ID == plugin {
			current.installed, current.version = true, entry.Version
		}
	}
	return current, nil
}

func readCodex(marketplaces, plugins []byte) (state, error) {
	var listed struct {
		Marketplaces []struct {
			Name   string `json:"name"`
			Source struct {
				Source string `json:"source"`
			} `json:"marketplaceSource"`
		} `json:"marketplaces"`
	}
	var added struct {
		Installed []struct {
			ID      string `json:"pluginId"`
			Version string `json:"version"`
		} `json:"installed"`
	}
	if err := decode(marketplaces, &listed); err != nil {
		return state{}, err
	}
	if err := decode(plugins, &added); err != nil {
		return state{}, err
	}
	if listed.Marketplaces == nil || added.Installed == nil {
		return state{}, errors.New("a list is missing")
	}
	var current state
	for _, entry := range listed.Marketplaces {
		if entry.Name == marketplace {
			current.source = entry.Source.Source
			if current.source == "" {
				current.source = "an unnamed source"
			}
		}
	}
	for _, entry := range added.Installed {
		if entry.ID == plugin {
			current.installed, current.version = true, entry.Version
		}
	}
	return current, nil
}

// copilotLocal precedes the directory of a local marketplace.
const copilotLocal = "Local: "

func readCopilot(marketplaces, plugins []byte) (state, error) {
	var listed []struct {
		Name   string `json:"name"`
		Source string `json:"source"`
	}
	var added []struct {
		Name        string `json:"name"`
		Marketplace string `json:"marketplace"`
		Version     string `json:"version"`
		Enabled     bool   `json:"enabled"`
	}
	if err := decode(marketplaces, &listed); err != nil {
		return state{}, err
	}
	if err := decode(plugins, &added); err != nil {
		return state{}, err
	}
	var current state
	for _, entry := range listed {
		if entry.Name == marketplace {
			directory, local := strings.CutPrefix(entry.Source, copilotLocal)
			current.source = directory
			if !local {
				current.source = "the source " + entry.Source
			}
		}
	}
	for _, entry := range added {
		if entry.Name == marketplace && entry.Marketplace == marketplace && entry.Enabled {
			current.installed, current.version = true, entry.Version
		}
	}
	return current, nil
}

// observePi reads the only list that Pi prints. Each package has a source line, then its resolved directory with a
// deeper indent. A list without that shape is unreadable, never empty.
func observePi(ctx context.Context, call caller) (state, error) {
	if err := call.available(); err != nil {
		return state{}, err
	}
	raw, err := call.run(ctx, "list")
	if err != nil {
		return state{}, err
	}
	current, err := readPi(string(raw))
	if err != nil {
		return state{}, call.unreadable(err)
	}
	return current, nil
}

const (
	piEmpty     = "No packages installed."
	piHeader    = " packages:"
	piDirectory = "    "
)

func readPi(list string) (state, error) {
	if strings.TrimSpace(list) == piEmpty {
		return state{}, nil
	}
	var current state
	headers := 0
	for _, line := range strings.Split(list, "\n") {
		switch {
		case strings.HasSuffix(line, piHeader):
			headers++
		case strings.HasPrefix(line, piDirectory):
			directory := strings.TrimSpace(line)
			composition, found := strings.CutSuffix(filepath.ToSlash(directory), "/"+pluginPath)
			if found && owned(directory) {
				if current.source != "" && current.source != composition {
					return state{}, fmt.Errorf("two Kast packages: %s and %s", current.source, composition)
				}
				current.source, current.installed = filepath.FromSlash(composition), true
			}
		}
	}
	if headers == 0 {
		return state{}, errors.New("a list is missing")
	}
	return current, nil
}
