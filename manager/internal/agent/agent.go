// Package agent connects a coding agent to the installed Kast plugin. Every change goes through the agent's own
// commands, so the agent owns its configuration and Kast writes none of it.
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"path/filepath"
	"slices"
	"strings"
)

// The marketplace and the plugin share one name in every composition that a Control ships.
const (
	marketplace = "kast"
	plugin      = "kast@kast"
)

// toolsPath and mcpPath locate the two compositions beneath an installation.
const (
	toolsPath = "share/kast/agent-tools"
	mcpPath   = "compositions/mcp"
)

// Failure is the closed set of reasons an invocation does not finish.
type Failure string

const (
	UsageRejected      Failure = "USAGE_REJECTED"
	AgentUnavailable   Failure = "AGENT_UNAVAILABLE"
	PluginUnavailable  Failure = "PLUGIN_UNAVAILABLE"
	MarketplaceForeign Failure = "MARKETPLACE_FOREIGN"
	ReplyUnreadable    Failure = "REPLY_UNREADABLE"
	CommandFailed      Failure = "COMMAND_FAILED"
)

// Rejection is one failure, what it concerns, and the next action.
type Rejection struct {
	Failure Failure
	Detail  string
	Next    string
}

func (r *Rejection) Error() string { return string(r.Failure) + ": " + r.Detail }

// Effects are the only contacts with the machine. Run returns a completed command's standard output.
type Effects struct {
	Look func(program string) (string, error)
	Run  func(ctx context.Context, command []string) ([]byte, error)
	File func(path string) bool
}

// state is what an agent reports about Kast before an effect.
type state struct {
	// source is the marketplace directory, and is empty when the agent has no Kast marketplace.
	source    string
	installed bool
}

// harness is one agent's command vocabulary. Each agent reports its lists in its own JSON shape.
type harness struct {
	name     string
	program  string
	manifest string
	install  []string
	remove   []string
	observe  func(marketplaces, plugins []byte) (state, error)
	// replaces reports whether adding a marketplace takes over one with the same name.
	replaces bool
	after    string
}

var harnesses = map[string]harness{
	"claude": {
		name: "Claude Code", program: "claude", manifest: ".claude-plugin/marketplace.json",
		install: []string{"plugin", "install", plugin}, remove: []string{"plugin", "uninstall", plugin},
		observe: observeClaude, replaces: true,
		after: "Start a new Claude Code session to load it.",
	},
	"codex": {
		name: "Codex", program: "codex", manifest: ".agents/plugins/marketplace.json",
		install: []string{"plugin", "add", plugin}, remove: []string{"plugin", "remove", plugin},
		observe: observeCodex,
		after:   "Start a new Codex session, then run /hooks to review the Kast session hook.",
	},
}

const usage = "Run `kast agent install <claude|codex> [--without-mcp]` or `kast agent remove <claude|codex>`."

// Run performs one `kast agent` invocation. The installation is empty when none was found; only an install needs it.
func Run(ctx context.Context, effects Effects, installation string, arguments []string, out io.Writer) error {
	if len(arguments) < 2 {
		return reject(UsageRejected, usage, "an action and an agent are required")
	}
	selected, known := harnesses[arguments[1]]
	options := arguments[2:]
	switch {
	case !known:
		return reject(UsageRejected, usage, "no Kast plugin exists for %q", arguments[1])
	case arguments[0] == "install" && len(options) == 0:
		return install(ctx, effects, selected, installation, true, out)
	case arguments[0] == "install" && slices.Equal(options, []string{"--without-mcp"}):
		return install(ctx, effects, selected, installation, false, out)
	case arguments[0] == "remove" && len(options) == 0:
		return remove(ctx, effects, selected, out)
	}
	return reject(UsageRejected, usage, "unknown action or option")
}

// install is also the refresh after an upgrade: every step repeats safely, and Codex replaces its copy.
func install(ctx context.Context, effects Effects, selected harness, installation string, withMCP bool, out io.Writer) error {
	directory := filepath.Join(installation, filepath.FromSlash(toolsPath))
	if withMCP {
		directory = filepath.Join(directory, filepath.FromSlash(mcpPath))
	}
	if installation == "" || !effects.File(filepath.Join(directory, filepath.FromSlash(selected.manifest))) {
		return reject(PluginUnavailable, "Run `kast upgrade`, then run the same command again.",
			"this installation has no plugin for %s", selected.name)
	}
	current, err := observe(ctx, effects, selected)
	if err != nil {
		return err
	}
	if current.source != "" && current.source != directory && !selected.replaces {
		if err := command(ctx, effects, selected, "plugin", "marketplace", "remove", marketplace); err != nil {
			return err
		}
		current.source = ""
	}
	if current.source != directory {
		if err := command(ctx, effects, selected, "plugin", "marketplace", "add", directory); err != nil {
			return err
		}
	}
	if err := command(ctx, effects, selected, selected.install...); err != nil {
		return err
	}
	_, err = fmt.Fprintf(out, "Installed the Kast plugin in %s. %s\n", selected.name, selected.after)
	return err
}

func remove(ctx context.Context, effects Effects, selected harness, out io.Writer) error {
	current, err := observe(ctx, effects, selected)
	if err != nil {
		return err
	}
	if !current.installed && current.source == "" {
		_, err = fmt.Fprintf(out, "%s has no Kast plugin.\n", selected.name)
		return err
	}
	if current.installed {
		if err := command(ctx, effects, selected, selected.remove...); err != nil {
			return err
		}
	}
	if current.source != "" {
		if err := command(ctx, effects, selected, "plugin", "marketplace", "remove", marketplace); err != nil {
			return err
		}
	}
	_, err = fmt.Fprintf(out, "Removed the Kast plugin from %s.\n", selected.name)
	return err
}

// observe reads both lists and rejects a marketplace that only shares the Kast name.
func observe(ctx context.Context, effects Effects, selected harness) (state, error) {
	if _, err := effects.Look(selected.program); err != nil {
		return state{}, reject(AgentUnavailable, "Install "+selected.name+", or add its command to your PATH.",
			"the %s command was not found", selected.program)
	}
	marketplaces, err := run(ctx, effects, selected, "plugin", "marketplace", "list", "--json")
	if err != nil {
		return state{}, err
	}
	plugins, err := run(ctx, effects, selected, "plugin", "list", "--json")
	if err != nil {
		return state{}, err
	}
	current, err := selected.observe(marketplaces, plugins)
	if err != nil {
		return state{}, reject(ReplyUnreadable, "Update "+selected.name+", then run the same command again.",
			"%s printed an unknown plugin list: %v", selected.name, err)
	}
	if current.source != "" && !owned(current.source) {
		return state{}, reject(MarketplaceForeign,
			"Remove it with `"+selected.program+" plugin marketplace remove "+marketplace+"`, then run the same command again.",
			"%s has another marketplace named %s at %s", selected.name, marketplace, current.source)
	}
	return current, nil
}

// owned admits exactly the two directories that a Control lays out, in any installation root.
func owned(source string) bool {
	clean := filepath.ToSlash(filepath.Clean(source))
	return strings.HasSuffix(clean, "/"+toolsPath) || strings.HasSuffix(clean, "/"+toolsPath+"/"+mcpPath)
}

func observeClaude(marketplaces, plugins []byte) (state, error) {
	var listed []struct {
		Name   string `json:"name"`
		Source string `json:"source"`
		Path   string `json:"path"`
	}
	var added []struct {
		ID string `json:"id"`
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
			// Only a directory has a path, so a Git source keeps a name that no Kast layout ends with.
			current.source = entry.Path
			if entry.Path == "" {
				current.source = "a " + entry.Source + " source"
			}
		}
	}
	for _, entry := range added {
		current.installed = current.installed || entry.ID == plugin
	}
	return current, nil
}

func observeCodex(marketplaces, plugins []byte) (state, error) {
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
			ID string `json:"pluginId"`
		} `json:"installed"`
	}
	if err := decode(marketplaces, &listed); err != nil {
		return state{}, err
	}
	if err := decode(plugins, &added); err != nil {
		return state{}, err
	}
	if listed.Marketplaces == nil || added.Installed == nil {
		return state{}, fmt.Errorf("a list is missing")
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
		current.installed = current.installed || entry.ID == plugin
	}
	return current, nil
}

// decode ignores additive fields, and rejects an absent list, which would read as "nothing installed".
func decode(raw []byte, value any) error {
	if strings.TrimSpace(string(raw)) == "null" {
		return fmt.Errorf("a list is missing")
	}
	return json.Unmarshal(raw, value)
}

func command(ctx context.Context, effects Effects, selected harness, arguments ...string) error {
	_, err := run(ctx, effects, selected, arguments...)
	return err
}

func run(ctx context.Context, effects Effects, selected harness, arguments ...string) ([]byte, error) {
	vector := append([]string{selected.program}, arguments...)
	output, err := effects.Run(ctx, vector)
	if err != nil {
		return nil, reject(CommandFailed, "Run `"+strings.Join(vector, " ")+"` yourself to see the cause.",
			"`%s` failed: %v", strings.Join(vector, " "), err)
	}
	return output, nil
}

func reject(failure Failure, next, format string, values ...any) error {
	return &Rejection{failure, fmt.Sprintf(format, values...), next}
}
