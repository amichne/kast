// Package agent connects coding agents to the installed Kast plugin. Every change goes through an agent's own commands,
// so the agent owns its configuration and Kast writes none of it.
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

// The marketplace, the plugin and the MCP server share one name in every composition that a Control ships.
const (
	marketplace = "kast"
	plugin      = "kast@kast"
)

// toolsPath and mcpPath locate the two compositions beneath an installation. pluginPath locates the plugin in each.
const (
	toolsPath  = "share/kast/agent-tools"
	mcpPath    = "compositions/mcp"
	pluginPath = "plugins/kast"
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
	VersionStale       Failure = "VERSION_STALE"
)

// Rejection is one failure, what it concerns, and the next action.
type Rejection struct {
	Failure Failure
	Detail  string
	Next    string
}

func (r *Rejection) Error() string { return string(r.Failure) + ": " + r.Detail }

// Effects are the only contacts with the machine. Run returns a completed command's standard output. Ask shows a
// question on a terminal and returns one line; it is nil without a terminal.
type Effects struct {
	Look func(program string) (string, error)
	Run  func(ctx context.Context, command []string) ([]byte, error)
	Read func(path string) ([]byte, error)
	Ask  func(ctx context.Context, question string) (string, error)
}

// state is what an agent reports about Kast before an effect.
type state struct {
	// source is the directory that the agent loads Kast from, and is empty when it has none.
	source    string
	installed bool
	// version is empty when the agent does not report one.
	version string
}

// present reports whether the agent has any part of Kast.
func (s state) present() bool { return s.installed || s.source != "" }

// withMCP reports whether the agent loads the composition that holds the MCP server.
func (s state) withMCP() bool {
	return strings.Contains(filepath.ToSlash(s.source)+"/", "/"+toolsPath+"/"+mcpPath+"/")
}

// harness is one agent's vocabulary. Each agent reports its state in its own shape.
type harness struct {
	key     string
	name    string
	program string
	// observe reads the agent's state without an effect.
	observe func(ctx context.Context, call caller) (state, error)
	// connect replaces whatever Kast the agent has with one composition.
	connect func(ctx context.Context, call caller, current state, composition string, withMCP bool) error
	// disconnect removes what observe found.
	disconnect func(ctx context.Context, call caller, current state) error
	after      string
}

// harnesses is the presentation order. A key is the public argument.
var harnesses = []harness{claude, codex, copilot, pi}

var usage = "Run `kast agent install [" + strings.Join(keys(), "|") + "]... [--without-mcp]`, " +
	"`kast agent remove [AGENT]...`, or `kast agent refresh`."

func keys() []string {
	names := make([]string, 0, len(harnesses))
	for _, h := range harnesses {
		names = append(names, h.key)
	}
	return names
}

// Run performs one `kast agent` invocation. The installation is empty when none was found; only a change that adds Kast
// needs it.
func Run(ctx context.Context, effects Effects, installation string, arguments []string, out io.Writer) error {
	if len(arguments) == 0 {
		return reject(UsageRejected, usage, "an action is required")
	}
	named, withMCP, err := targets(arguments[0], arguments[1:])
	if err != nil {
		return err
	}
	operation := invocation{effects, installation, out}
	switch arguments[0] {
	case "install":
		return operation.install(ctx, named, withMCP)
	case "remove":
		return operation.remove(ctx, named)
	default:
		return operation.refresh(ctx)
	}
}

// targets admits the agents and options of one action. No name means every agent on this machine.
func targets(action string, arguments []string) ([]harness, bool, error) {
	if !slices.Contains([]string{"install", "remove", "refresh"}, action) {
		return nil, false, reject(UsageRejected, usage, "unknown action %q", action)
	}
	var named []harness
	withMCP := true
	for _, argument := range arguments {
		index := slices.IndexFunc(harnesses, func(h harness) bool { return h.key == argument })
		switch {
		case action == "install" && argument == "--without-mcp" && withMCP:
			withMCP = false
		case action == "refresh" || strings.HasPrefix(argument, "-"):
			return nil, false, reject(UsageRejected, usage, "unknown option %q", argument)
		case index < 0:
			return nil, false, reject(UsageRejected, usage, "Kast does not support an agent named %q", argument)
		case slices.ContainsFunc(named, func(h harness) bool { return h.key == argument }):
			return nil, false, reject(UsageRejected, usage, "%s is named twice", argument)
		default:
			named = append(named, harnesses[index])
		}
	}
	return named, withMCP, nil
}

type invocation struct {
	effects      Effects
	installation string
	out          io.Writer
}

func (i invocation) install(ctx context.Context, named []harness, withMCP bool) error {
	product, err := i.product(withMCP)
	if err != nil {
		return err
	}
	selected, err := i.selected(ctx, named)
	if err != nil {
		return err
	}
	return i.each(selected, func(h harness) error {
		current, err := h.observe(ctx, i.caller(h))
		if err != nil {
			return err
		}
		return i.connect(ctx, h, current, product, withMCP)
	})
}

// refresh moves each agent that already has this Kast to the installed version, and adds it to no other agent.
func (i invocation) refresh(ctx context.Context) error {
	connected := 0
	err := i.each(i.found(), func(h harness) error {
		current, err := h.observe(ctx, i.caller(h))
		if err != nil || !current.installed || !owned(current.source) {
			return err
		}
		connected++
		product, err := i.product(current.withMCP())
		if err != nil {
			return err
		}
		return i.connect(ctx, h, current, product, current.withMCP())
	})
	if err == nil && connected == 0 {
		_, err = fmt.Fprintln(i.out, "No agent has the Kast plugin.")
	}
	return err
}

func (i invocation) remove(ctx context.Context, named []harness) error {
	everything := len(named) == 0
	if everything {
		named = i.found()
	}
	return i.each(named, func(h harness) error {
		current, err := h.observe(ctx, i.caller(h))
		if err != nil {
			return err
		}
		if !current.present() {
			_, err = fmt.Fprintf(i.out, "%s has no Kast plugin.\n", h.name)
			return err
		}
		if current.source != "" && !owned(current.source) {
			return reject(MarketplaceForeign, "Remove it with "+h.name+"'s own commands.",
				"%s has another Kast at %s", h.name, current.source)
		}
		if err := h.disconnect(ctx, i.caller(h), current); err != nil {
			return err
		}
		_, err = fmt.Fprintf(i.out, "Removed the Kast plugin from %s.\n", h.name)
		return err
	})
}

// each visits every agent, so one agent's failure does not hide another's result. The first failure is the outcome.
func (i invocation) each(agents []harness, visit func(harness) error) error {
	var first error
	for _, h := range agents {
		err := visit(h)
		if err == nil {
			continue
		}
		if first == nil {
			first = err
			continue
		}
		if _, writeErr := fmt.Fprintf(i.out, "%s: %v\n", h.name, err); writeErr != nil {
			return writeErr
		}
	}
	return first
}

// connect succeeds only when the agent itself then reports this composition at this version.
func (i invocation) connect(ctx context.Context, h harness, before state, product composition, withMCP bool) error {
	call := i.caller(h)
	if err := h.connect(ctx, call, before, product.directory, withMCP); err != nil {
		return err
	}
	after, err := h.observe(ctx, call)
	if err != nil {
		return err
	}
	if !after.installed || after.source != product.directory || (after.version != "" && after.version != product.version) {
		return reject(VersionStale, "Run `kast agent remove "+h.key+"`, then `kast agent install "+h.key+"`.",
			"%s reports Kast %s at %s instead of %s", h.name, orNone(after.version), orNone(after.source), product.version)
	}
	_, err = fmt.Fprintf(i.out, "%s Kast %s in %s. %s\n", verb(before, after), product.version, h.name, h.after)
	return err
}

func verb(before, after state) string {
	switch {
	case !before.present():
		return "Installed"
	case before.version != "" && before.version != after.version:
		return "Updated from " + before.version + " to"
	case before.source != after.source:
		return "Replaced the existing Kast with"
	default:
		return "Refreshed"
	}
}

func orNone(value string) string {
	if value == "" {
		return "nothing"
	}
	return value
}

// composition is one plugin layout of this installation, at one version.
type composition struct {
	directory string
	version   string
}

func (i invocation) product(withMCP bool) (composition, error) {
	directory := filepath.Join(i.installation, filepath.FromSlash(toolsPath))
	if withMCP {
		directory = filepath.Join(directory, filepath.FromSlash(mcpPath))
	}
	var manifest struct {
		Name    string `json:"name"`
		Version string `json:"version"`
	}
	// The Pi package is the newest file of the layout, so it proves that every supported agent has its part.
	raw, err := i.effects.Read(filepath.Join(directory, filepath.FromSlash(pluginPath), "package.json"))
	if i.installation == "" || err != nil || json.Unmarshal(raw, &manifest) != nil ||
		manifest.Name != marketplace || manifest.Version == "" {
		return composition{}, reject(PluginUnavailable, "Run `kast upgrade`, then run the same command again.",
			"this installation has no complete agent plugin")
	}
	return composition{directory, manifest.Version}, nil
}

// found is every supported agent whose command is on the PATH.
func (i invocation) found() []harness {
	var found []harness
	for _, h := range harnesses {
		if _, err := i.effects.Look(h.program); err == nil {
			found = append(found, h)
		}
	}
	return found
}

// selected is the named agents, or the answer to one question about the agents found. Without a terminal it is every
// agent found.
func (i invocation) selected(ctx context.Context, named []harness) ([]harness, error) {
	if len(named) != 0 {
		return named, nil
	}
	found := i.found()
	if len(found) == 0 {
		return nil, reject(AgentUnavailable, "Install one of them, or add its command to your PATH.",
			"no supported agent was found: %s", strings.Join(keys(), ", "))
	}
	if i.effects.Ask == nil {
		return found, nil
	}
	answer, err := i.effects.Ask(ctx, question(found))
	if err != nil {
		return nil, err
	}
	return choose(found, answer)
}

func question(found []harness) string {
	var text strings.Builder
	text.WriteString("Connect Kast to these agents:\n")
	for index, h := range found {
		fmt.Fprintf(&text, "  %d. %s\n", index+1, h.name)
	}
	text.WriteString("Enter numbers to choose, Enter for all, or 0 for none: ")
	return text.String()
}

// choose admits only listed numbers. Any other answer selects nothing and changes nothing.
func choose(found []harness, answer string) ([]harness, error) {
	fields := strings.FieldsFunc(answer, func(r rune) bool { return r == ',' || r == ' ' || r == '\t' })
	if len(fields) == 0 {
		return found, nil
	}
	if slices.Equal(fields, []string{"0"}) {
		return nil, nil
	}
	var chosen []harness
	for index, h := range found {
		number := fmt.Sprint(index + 1)
		if slices.Contains(fields, number) {
			chosen = append(chosen, h)
			fields = slices.DeleteFunc(fields, func(field string) bool { return field == number })
		}
	}
	if len(fields) != 0 {
		return nil, reject(UsageRejected, "Enter only numbers from the list.", "%q is not in the list", fields[0])
	}
	return chosen, nil
}

// owned admits exactly the layouts that a Control ships, in any installation root. Pi loads the plugin itself.
func owned(source string) bool {
	clean := strings.TrimSuffix(filepath.ToSlash(filepath.Clean(source)), "/"+pluginPath)
	return strings.HasSuffix(clean, "/"+toolsPath) || strings.HasSuffix(clean, "/"+toolsPath+"/"+mcpPath)
}

// caller runs one agent's commands.
type caller struct {
	effects Effects
	h       harness
}

func (i invocation) caller(h harness) caller { return caller{i.effects, h} }

// available rejects before the first command of an agent that is not on the PATH.
func (c caller) available() error {
	if _, err := c.effects.Look(c.h.program); err != nil {
		return reject(AgentUnavailable, "Install "+c.h.name+", or add its command to your PATH.",
			"the %s command was not found", c.h.program)
	}
	return nil
}

func (c caller) run(ctx context.Context, arguments ...string) ([]byte, error) {
	vector := append([]string{c.h.program}, arguments...)
	output, err := c.effects.Run(ctx, vector)
	if err != nil {
		return nil, reject(CommandFailed, "Run `"+strings.Join(vector, " ")+"` yourself to see the cause.",
			"`%s` failed: %v", strings.Join(vector, " "), err)
	}
	return output, nil
}

func (c caller) command(ctx context.Context, arguments ...string) error {
	_, err := c.run(ctx, arguments...)
	return err
}

// clear removes an entry that may be absent. An agent reports an absent entry as a failure, and the later observation
// decides the outcome. A cancelled invocation still ends here.
func (c caller) clear(ctx context.Context, arguments ...string) error {
	_, _ = c.effects.Run(ctx, append([]string{c.h.program}, arguments...))
	return ctx.Err()
}

// unreadable is a reply that this version of Kast does not know. It never means that nothing is installed.
func (c caller) unreadable(err error) error {
	return reject(ReplyUnreadable, "Update "+c.h.name+", then run the same command again.",
		"%s printed an unknown plugin list: %v", c.h.name, err)
}

// decode ignores additive fields, and rejects an absent list, which would read as "nothing installed".
func decode(raw []byte, value any) error {
	if strings.TrimSpace(string(raw)) == "null" {
		return fmt.Errorf("a list is missing")
	}
	return json.Unmarshal(raw, value)
}

func reject(failure Failure, next, format string, values ...any) error {
	return &Rejection{failure, fmt.Sprintf(format, values...), next}
}
