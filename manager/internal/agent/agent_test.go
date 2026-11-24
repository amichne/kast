package agent

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

const (
	installation = "/home/u/.local/share/kast/installation"
	tools        = installation + "/share/kast/agent-tools"
	withMCP      = tools + "/compositions/mcp"
)

// Replies in the shape that Claude Code 2.1.331 and Codex 0.172.0 print.
func claudeMarketplaces(path string) string {
	if path == "" {
		return `[]`
	}
	return fmt.Sprintf(`[{"name":"other","source":"github","repo":"a/b"},
		{"name":"kast","source":"directory","path":%q,"installLocation":%q}]`, path, path)
}

func codexMarketplaces(path string) string {
	if path == "" {
		return `{"marketplaces":[{"name":"openai-bundled","root":"/x"}]}`
	}
	return fmt.Sprintf(`{"marketplaces":[{"name":"kast","root":%q,
		"marketplaceSource":{"sourceType":"local","source":%q}}]}`, path, path)
}

const (
	claudeNone      = `[]`
	claudeInstalled = `[{"id":"other@other","version":"1"},{"id":"kast@kast","version":"0.53.0","scope":"user"}]`
	codexNone       = `{"installed":[],"available":[{"pluginId":"kast@kast"}]}`
	codexInstalled  = `{"installed":[{"pluginId":"kast@kast","installed":true,"enabled":true}]}`
)

// script is the complete sequence of commands one invocation may run, with each reply.
type script struct {
	t        *testing.T
	expected []step
	missing  bool
	noFile   bool
	files    []string
}

type step struct {
	command string
	reply   string
	err     error
}

func (s *script) effects() Effects {
	return Effects{
		Look: func(program string) (string, error) {
			if s.missing {
				return "", exec.ErrNotFound
			}
			return "/bin/" + program, nil
		},
		Run: func(_ context.Context, vector []string) ([]byte, error) {
			actual := strings.Join(vector, " ")
			if len(s.expected) == 0 {
				s.t.Fatalf("unexpected command %q", actual)
			}
			next := s.expected[0]
			s.expected = s.expected[1:]
			if next.command != actual {
				s.t.Fatalf("command %q, want %q", actual, next.command)
			}
			return []byte(next.reply), next.err
		},
		File: func(path string) bool { s.files = append(s.files, path); return !s.noFile },
	}
}

func lists(program, marketplaces, plugins string) []step {
	return []step{
		{program + " plugin marketplace list --json", marketplaces, nil},
		{program + " plugin list --json", plugins, nil},
	}
}

func perform(t *testing.T, s *script, root string, arguments ...string) (string, error) {
	t.Helper()
	s.t = t
	var out bytes.Buffer
	err := Run(context.Background(), s.effects(), root, arguments, &out)
	if len(s.expected) != 0 {
		t.Errorf("commands not run: %v", s.expected)
	}
	return out.String(), err
}

func failure(t *testing.T, err error) *Rejection {
	t.Helper()
	var rejection *Rejection
	if !errors.As(err, &rejection) {
		t.Fatalf("not a rejection: %v", err)
	}
	if rejection.Next == "" {
		t.Errorf("%s offers no next action", rejection.Failure)
	}
	return rejection
}

func TestAnInstallAddsOnlyWhatTheAgentLacks(t *testing.T) {
	for name, test := range map[string]struct {
		arguments []string
		steps     []step
		manifest  string
		says      string
	}{
		"claude, nothing installed": {
			[]string{"install", "claude"},
			append(lists("claude", claudeMarketplaces(""), claudeNone),
				step{command: "claude plugin marketplace add " + withMCP},
				step{command: "claude plugin install kast@kast"}),
			withMCP + "/.claude-plugin/marketplace.json", "Claude Code",
		},
		"claude, again": {
			[]string{"install", "claude"},
			append(lists("claude", claudeMarketplaces(withMCP), claudeInstalled),
				step{command: "claude plugin install kast@kast"}),
			withMCP + "/.claude-plugin/marketplace.json", "Claude Code",
		},
		"claude, other composition": {
			[]string{"install", "claude", "--without-mcp"},
			append(lists("claude", claudeMarketplaces(withMCP), claudeInstalled),
				step{command: "claude plugin marketplace add " + tools},
				step{command: "claude plugin install kast@kast"}),
			tools + "/.claude-plugin/marketplace.json", "Claude Code",
		},
		"codex, nothing installed": {
			[]string{"install", "codex"},
			append(lists("codex", codexMarketplaces(""), codexNone),
				step{command: "codex plugin marketplace add " + withMCP},
				step{command: "codex plugin add kast@kast"}),
			withMCP + "/.agents/plugins/marketplace.json", "/hooks",
		},
		// Codex keeps a copy of the plugin, so a repeat after an upgrade replaces it.
		"codex, after an upgrade": {
			[]string{"install", "codex"},
			append(lists("codex", codexMarketplaces(withMCP), codexInstalled),
				step{command: "codex plugin add kast@kast"}),
			withMCP + "/.agents/plugins/marketplace.json", "Codex",
		},
		// Codex refuses a second source under one name, where Claude Code takes it over.
		"codex, other composition": {
			[]string{"install", "codex", "--without-mcp"},
			append(lists("codex", codexMarketplaces(withMCP), codexInstalled),
				step{command: "codex plugin marketplace remove kast"},
				step{command: "codex plugin marketplace add " + tools},
				step{command: "codex plugin add kast@kast"}),
			tools + "/.agents/plugins/marketplace.json", "Codex",
		},
		"codex, another installation root": {
			[]string{"install", "codex"},
			append(lists("codex", codexMarketplaces("/old/kast/installation/share/kast/agent-tools"), codexInstalled),
				step{command: "codex plugin marketplace remove kast"},
				step{command: "codex plugin marketplace add " + withMCP},
				step{command: "codex plugin add kast@kast"}),
			withMCP + "/.agents/plugins/marketplace.json", "Codex",
		},
	} {
		t.Run(name, func(t *testing.T) {
			s := &script{expected: test.steps}
			out, err := perform(t, s, installation, test.arguments...)
			if err != nil || !strings.Contains(out, test.says) || !slices.Equal(s.files, []string{test.manifest}) {
				t.Errorf("out %q err %v files %v", out, err, s.files)
			}
		})
	}
}

func TestARemovalRemovesOnlyWhatTheAgentHasAndNeedsNoInstallation(t *testing.T) {
	for name, test := range map[string]struct {
		agent string
		steps []step
		says  string
	}{
		"claude": {"claude", append(lists("claude", claudeMarketplaces(withMCP), claudeInstalled),
			step{command: "claude plugin uninstall kast@kast"},
			step{command: "claude plugin marketplace remove kast"}), "Removed"},
		"codex": {"codex", append(lists("codex", codexMarketplaces(tools), codexInstalled),
			step{command: "codex plugin remove kast@kast"},
			step{command: "codex plugin marketplace remove kast"}), "Removed"},
		"marketplace only": {"claude", append(lists("claude", claudeMarketplaces(tools), claudeNone),
			step{command: "claude plugin marketplace remove kast"}), "Removed"},
		"plugin only": {"codex", append(lists("codex", codexMarketplaces(""), codexInstalled),
			step{command: "codex plugin remove kast@kast"}), "Removed"},
		"nothing": {"claude", lists("claude", claudeMarketplaces(""), claudeNone), "has no Kast plugin"},
	} {
		t.Run(name, func(t *testing.T) {
			s := &script{expected: test.steps}
			out, err := perform(t, s, "", "remove", test.agent)
			if err != nil || !strings.Contains(out, test.says) || len(s.files) != 0 {
				t.Errorf("out %q err %v files %v", out, err, s.files)
			}
		})
	}
}

func TestAForeignMarketplaceIsNeverReplacedOrRemoved(t *testing.T) {
	for agent, foreign := range map[string][]string{
		"claude": {
			claudeMarketplaces("/home/u/kast"),
			claudeMarketplaces("/home/u/share/kast/agent-tools-fork"),
			claudeMarketplaces(tools + "/../agent-tools2"),
			`[{"name":"kast","source":"github","repo":"amichne/kast"}]`,
		},
		"codex": {
			codexMarketplaces("/home/u/kast"),
			codexMarketplaces("https://github.com/a/b/compositions/mcp"),
			`{"marketplaces":[{"name":"kast","root":"/x"}]}`,
		},
	} {
		plugins := map[string]string{"claude": claudeInstalled, "codex": codexInstalled}[agent]
		for _, marketplaces := range foreign {
			for _, action := range []string{"install", "remove"} {
				s := &script{expected: lists(agent, marketplaces, plugins)}
				_, err := perform(t, s, installation, action, agent)
				if rejection := failure(t, err); rejection.Failure != MarketplaceForeign {
					t.Errorf("%s %s %s: %v", action, agent, marketplaces, rejection)
				}
			}
		}
	}
}

func TestAnUnreadableListNeverMeansNothingIsInstalled(t *testing.T) {
	for agent, replies := range map[string][][2]string{
		"claude": {{`null`, claudeNone}, {`[]`, `null`}, {`{}`, claudeNone}, {`[]`, `oops`}, {``, claudeNone}},
		"codex": {{`{}`, codexNone}, {`null`, codexNone}, {codexMarketplaces(""), `{}`},
			{codexMarketplaces(""), `{"installed":null}`}, {`[]`, codexNone}},
	} {
		for _, reply := range replies {
			for _, action := range []string{"install", "remove"} {
				s := &script{expected: lists(agent, reply[0], reply[1])}
				_, err := perform(t, s, installation, action, agent)
				if rejection := failure(t, err); rejection.Failure != ReplyUnreadable {
					t.Errorf("%s %s %v: %v", action, agent, reply, rejection)
				}
			}
		}
	}
}

func TestEachFailureStopsBeforeTheNextEffect(t *testing.T) {
	broken := errors.New("exit status 1: no login")
	for name, test := range map[string]struct {
		script    script
		root      string
		arguments []string
		want      Failure
	}{
		"no arguments":      {script{}, installation, nil, UsageRejected},
		"no agent":          {script{}, installation, []string{"install"}, UsageRejected},
		"unknown agent":     {script{}, installation, []string{"install", "copilot"}, UsageRejected},
		"unknown action":    {script{}, installation, []string{"status", "codex"}, UsageRejected},
		"unknown option":    {script{}, installation, []string{"install", "codex", "--mcp"}, UsageRejected},
		"repeated option":   {script{}, installation, []string{"install", "codex", "--without-mcp", "--without-mcp"}, UsageRejected},
		"removal option":    {script{}, installation, []string{"remove", "codex", "--without-mcp"}, UsageRejected},
		"no installation":   {script{}, "", []string{"install", "claude"}, PluginUnavailable},
		"older plugin":      {script{noFile: true}, installation, []string{"install", "claude"}, PluginUnavailable},
		"no agent program":  {script{missing: true}, installation, []string{"install", "claude"}, AgentUnavailable},
		"remove, no agent":  {script{missing: true}, "", []string{"remove", "codex"}, AgentUnavailable},
		"first list fails":  {script{expected: []step{{"codex plugin marketplace list --json", "", broken}}}, installation, []string{"install", "codex"}, CommandFailed},
		"second list fails": {script{expected: []step{{"claude plugin marketplace list --json", "[]", nil}, {"claude plugin list --json", "", broken}}}, installation, []string{"remove", "claude"}, CommandFailed},
		"add fails": {script{expected: append(lists("codex", codexMarketplaces(""), codexNone),
			step{"codex plugin marketplace add " + withMCP, "", broken})}, installation, []string{"install", "codex"}, CommandFailed},
		"install fails": {script{expected: append(lists("claude", claudeMarketplaces(withMCP), claudeNone),
			step{"claude plugin install kast@kast", "", broken})}, installation, []string{"install", "claude"}, CommandFailed},
		"old source stays": {script{expected: append(lists("codex", codexMarketplaces(tools), codexInstalled),
			step{"codex plugin marketplace remove kast", "", broken})}, installation, []string{"install", "codex"}, CommandFailed},
		"uninstall fails": {script{expected: append(lists("claude", claudeMarketplaces(tools), claudeInstalled),
			step{"claude plugin uninstall kast@kast", "", broken})}, "", []string{"remove", "claude"}, CommandFailed},
	} {
		t.Run(name, func(t *testing.T) {
			out, err := perform(t, &test.script, test.root, test.arguments...)
			rejection := failure(t, err)
			if rejection.Failure != test.want || out != "" {
				t.Errorf("%v with output %q, want %s", rejection, out, test.want)
			}
			if test.want == CommandFailed && !strings.Contains(rejection.Detail, "no login") {
				t.Errorf("the cause is lost: %s", rejection.Detail)
			}
		})
	}
}

func TestOwnershipNeedsTheExactLayout(t *testing.T) {
	for source, want := range map[string]bool{
		tools: true, withMCP: true, withMCP + "/": true, "/other/share/kast/agent-tools": true,
		installation: false, tools + "/compositions": false, tools + "/compositions/mcp/plugins": false,
		"/myshare/kast/agent-tools": false, "share/kast/agent-tools": false, "": false, "a github source": false,
	} {
		if owned(source) != want {
			t.Errorf("owned(%q) = %v", source, !want)
		}
	}
}

// program writes one executable and returns its path.
func program(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "program")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body), 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestACommandReturnsOnlyStandardOutputAndReadsNoInput(t *testing.T) {
	output, err := runCommand(context.Background(), []string{program(t, "echo warning >&2; printf '[%s]' \"$(cat)\"\n")})
	if err != nil || string(output) != "[]" {
		t.Errorf("%q %v", output, err)
	}
}

func TestAFailedCommandReportsItsLastLineWithoutControlCharacters(t *testing.T) {
	_, err := runCommand(context.Background(), []string{program(t,
		`printf 'first\n\033[31mno login\033[0m\n\n' >&2; exit 3`+"\n")})
	if err == nil || !strings.Contains(err.Error(), "exit status 3") || !strings.HasSuffix(err.Error(), "no login[0m") ||
		strings.ContainsRune(err.Error(), '\033') || strings.Contains(err.Error(), "first") {
		t.Errorf("%q", err)
	}
	if line := lastLine([]byte(strings.Repeat("é", 400))); len([]rune(line)) != maximumMessage+3 {
		t.Errorf("a message of %d characters", len([]rune(line)))
	}
	if line := lastLine(nil, []byte("\n \n")); line != "" {
		t.Errorf("%q", line)
	}
}

func TestACommandEndsWithItsContextEvenWhenAHelperHoldsItsPipes(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	ready := filepath.Join(t.TempDir(), "ready")
	go func() {
		for {
			if _, err := os.Stat(ready); err == nil {
				cancel()
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
	}()
	started := time.Now()
	_, err := runCommand(ctx, []string{program(t, "sleep 30 &\ntouch "+ready+"\nwait\n")})
	if !errors.Is(err, context.Canceled) || time.Since(started) > 10*time.Second {
		t.Errorf("%v after %v", err, time.Since(started))
	}
}

func TestAReplyBeyondItsBoundIsAFailure(t *testing.T) {
	buffer := &bounded{limit: 4}
	for _, part := range []string{"ab", "cd", "ef"} {
		if written, err := buffer.Write([]byte(part)); written != 2 || err != nil {
			t.Fatalf("%d %v", written, err)
		}
	}
	if buffer.buffer.String() != "abcd" || !buffer.exceeded {
		t.Errorf("%q exceeded=%v", buffer.buffer.String(), buffer.exceeded)
	}
	_, err := runCommand(context.Background(), []string{program(t,
		fmt.Sprintf("head -c %d /dev/zero\n", maximumReply+1))})
	if err == nil || !strings.Contains(err.Error(), "too long") {
		t.Errorf("%v", err)
	}
}

func TestTheManifestMustBeARegularFile(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "marketplace.json")
	if regular(directory) || regular(path) {
		t.Error("a directory or an absent path passed")
	}
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if !regular(path) {
		t.Error("a regular file was rejected")
	}
}
