package agent

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"slices"
	"strings"
	"testing"
)

const (
	installation = "/home/u/.local/share/kast/installation"
	tools        = installation + "/share/kast/agent-tools"
	withMCP      = tools + "/compositions/mcp"
	older        = "/old/kast/installation/share/kast/agent-tools"
	foreign      = "/home/u/kast"
	version      = "1.2.3"
)

// Replies in the shapes that Claude Code 2.1.332, Codex 0.172.0, Copilot 1.0.88 and Pi 1.5.2 print.
func claudeLists(source, installed string) []step {
	marketplaces, plugins := `[]`, `[{"id":"other@other","version":"9"}]`
	if source != "" {
		marketplaces = fmt.Sprintf(`[{"name":"other","source":"github","repo":"a/b"},
			{"name":"kast","source":"directory","path":%q,"installLocation":%q}]`, source, source)
	}
	if installed != "" {
		plugins = fmt.Sprintf(`[{"id":"kast@kast","version":%q,"scope":"user"}]`, installed)
	}
	return lists("claude", marketplaces, plugins)
}

func codexLists(source, installed string) []step {
	marketplaces, plugins := `{"marketplaces":[{"name":"openai-bundled","root":"/x"}]}`, `{"installed":[]}`
	if source != "" {
		marketplaces = fmt.Sprintf(`{"marketplaces":[{"name":"kast","marketplaceSource":{"sourceType":"local","source":%q}}]}`, source)
	}
	if installed != "" {
		plugins = fmt.Sprintf(`{"installed":[{"pluginId":"kast@kast","version":%q}]}`, installed)
	}
	return lists("codex", marketplaces, plugins)
}

func copilotLists(source, installed string) []step {
	marketplaces, plugins := `[{"name":"copilot-plugins","source":"GitHub: github/copilot-plugins"}]`, `[]`
	if source != "" {
		marketplaces = fmt.Sprintf(`[{"name":"kast","source":%q}]`, "Local: "+source)
	}
	if installed != "" {
		plugins = fmt.Sprintf(`[{"name":"kast","marketplace":"kast","version":%q,"enabled":true,"source":"live"}]`, installed)
	}
	return lists("copilot", marketplaces, plugins)
}

// Pi reports no version.
func piLists(source, _ string) []step {
	reply := "No packages installed.\n"
	if source != "" {
		reply = "User packages:\n  ../../x\n    /home/u/x\n  ../y\n    " + source + "/plugins/kast\n"
	}
	return []step{{"pi list", reply, nil}}
}

func lists(program, marketplaces, plugins string) []step {
	return []step{
		{program + " plugin marketplace list --json", marketplaces, nil},
		{program + " plugin list --json", plugins, nil},
	}
}

// script is the complete sequence of commands one invocation may run, with each reply.
type script struct {
	t        *testing.T
	expected []step
	// agents are the commands on the PATH.
	agents   []string
	manifest string
	answer   *string
	read     []string
	asked    string
}

type step struct {
	command string
	reply   string
	err     error
}

func (s *script) effects() Effects {
	effects := Effects{
		Look: func(program string) (string, error) {
			if !slices.Contains(s.agents, program) {
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
		Read: func(path string) ([]byte, error) {
			s.read = append(s.read, path)
			if s.manifest == "" {
				return nil, errors.New("absent")
			}
			return []byte(s.manifest), nil
		},
	}
	if s.answer != nil {
		effects.Ask = func(_ context.Context, question string) (string, error) {
			s.asked = question
			return *s.answer, nil
		}
	}
	return effects
}

const current = `{"name":"kast","version":"` + version + `","private":true}`

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

func steps(parts ...any) []step {
	var all []step
	for _, part := range parts {
		switch value := part.(type) {
		case []step:
			all = append(all, value...)
		case string:
			all = append(all, step{command: value})
		}
	}
	return all
}

const piServer = "pi mcp add kast -- /bin/bash -c " + mcpScript

func TestAnInstallReplacesWhateverTheAgentHasAndProvesTheVersion(t *testing.T) {
	for name, test := range map[string]struct {
		arguments []string
		steps     []step
		says      string
	}{
		"claude, nothing": {[]string{"install", "claude"}, steps(claudeLists("", ""),
			"claude mcp remove -s user kast", "claude plugin marketplace add "+withMCP, "claude plugin install kast@kast",
			claudeLists(withMCP, version)), "Installed Kast 1.2.3 in Claude Code"},
		"claude, older version": {[]string{"install", "claude"}, steps(claudeLists(withMCP, "1.0.0"),
			"claude mcp remove -s user kast", "claude plugin install kast@kast", "claude plugin update kast@kast",
			claudeLists(withMCP, version)), "Updated from 1.0.0 to Kast 1.2.3 in Claude Code"},
		"claude, foreign": {[]string{"install", "claude"}, steps(claudeLists(foreign, version),
			"claude mcp remove -s user kast", "claude plugin marketplace add "+withMCP, "claude plugin install kast@kast",
			"claude plugin update kast@kast", claudeLists(withMCP, version)), "Replaced the existing Kast"},
		"claude, without MCP": {[]string{"install", "claude", "--without-mcp"}, steps(claudeLists(withMCP, version),
			"claude mcp remove -s user kast", "claude plugin marketplace add "+tools, "claude plugin install kast@kast",
			"claude plugin update kast@kast", claudeLists(tools, version)), "Replaced the existing Kast"},
		"codex, nothing": {[]string{"install", "codex"}, steps(codexLists("", ""),
			"codex mcp remove kast", "codex plugin marketplace add "+withMCP, "codex plugin add kast@kast",
			codexLists(withMCP, version)), "/hooks"},
		"codex, after an upgrade": {[]string{"install", "codex"}, steps(codexLists(withMCP, "1.0.0"),
			"codex mcp remove kast", "codex plugin add kast@kast", codexLists(withMCP, version)), "Updated from 1.0.0"},
		"codex, foreign": {[]string{"install", "codex"}, steps(codexLists(foreign, ""),
			"codex mcp remove kast", "codex plugin marketplace remove kast", "codex plugin marketplace add "+withMCP,
			"codex plugin add kast@kast", codexLists(withMCP, version)), "Replaced the existing Kast"},
		"copilot, nothing": {[]string{"install", "copilot"}, steps(copilotLists("", ""),
			"copilot mcp remove kast", "copilot plugin marketplace add "+withMCP, "copilot plugin install kast@kast",
			copilotLists(withMCP, version)), "trusted folder"},
		"copilot, another root": {[]string{"install", "copilot"}, steps(copilotLists(older, "1.0.0"),
			"copilot mcp remove kast", "copilot plugin marketplace remove kast --force",
			"copilot plugin marketplace add "+withMCP, "copilot plugin install kast@kast",
			copilotLists(withMCP, version)), "Updated from 1.0.0"},
		"pi, nothing": {[]string{"install", "pi"}, steps(piLists("", ""),
			"pi install "+withMCP+"/plugins/kast", piServer, piLists(withMCP, "")), "Installed Kast 1.2.3 in Pi"},
		"pi, another root": {[]string{"install", "pi"}, steps(piLists(older, ""),
			"pi remove "+older+"/plugins/kast", "pi install "+withMCP+"/plugins/kast", piServer,
			piLists(withMCP, "")), "Replaced the existing Kast"},
		"pi, without MCP": {[]string{"install", "pi", "--without-mcp"}, steps(piLists("", ""),
			"pi install "+tools+"/plugins/kast", "pi mcp remove kast", piLists(tools, "")), "Installed"},
	} {
		t.Run(name, func(t *testing.T) {
			s := &script{expected: test.steps, agents: keys(), manifest: current}
			out, err := perform(t, s, installation, test.arguments...)
			if err != nil || !strings.Contains(out, test.says) {
				t.Errorf("out %q err %v", out, err)
			}
			root := withMCP
			if slices.Contains(test.arguments, "--without-mcp") {
				root = tools
			}
			if !slices.Equal(s.read, []string{root + "/plugins/kast/package.json"}) {
				t.Errorf("read %v", s.read)
			}
		})
	}
}

// An absent direct server is a failure for some agents, and it never stops an installation.
func TestAnAbsentDirectServerDoesNotStopAnInstallation(t *testing.T) {
	absent := errors.New("exit status 1")
	expected := steps(copilotLists("", ""))
	expected = append(expected, step{"copilot mcp remove kast", "", absent})
	expected = append(expected, steps("copilot plugin marketplace add "+withMCP, "copilot plugin install kast@kast",
		copilotLists(withMCP, version))...)
	if _, err := perform(t, &script{expected: expected, agents: keys(), manifest: current}, installation, "install", "copilot"); err != nil {
		t.Error(err)
	}
}

func TestAnInstallationThatKeepsAnOldVersionIsAFailure(t *testing.T) {
	for name, after := range map[string][]step{
		"old version":   claudeLists(withMCP, "1.0.0"),
		"old directory": claudeLists(older, version),
		"not installed": claudeLists(withMCP, ""),
	} {
		expected := steps(claudeLists(withMCP, "1.0.0"), "claude mcp remove -s user kast", "claude plugin install kast@kast",
			"claude plugin update kast@kast", after)
		out, err := perform(t, &script{expected: expected, agents: keys(), manifest: current}, installation, "install", "claude")
		if rejection := failure(t, err); rejection.Failure != VersionStale || out != "" {
			t.Errorf("%s: %v with output %q", name, rejection, out)
		}
	}
}

func connected(agent string) []step {
	switch agent {
	case "codex":
		return steps(codexLists(withMCP, version), "codex mcp remove kast", "codex plugin add kast@kast", codexLists(withMCP, version))
	case "pi":
		return steps(piLists(withMCP, ""), "pi install "+withMCP+"/plugins/kast", piServer, piLists(withMCP, ""))
	}
	panic(agent)
}

func TestTheSelectorOffersOnlyTheAgentsThatWereFound(t *testing.T) {
	for answer, want := range map[string][]string{
		"": {"codex", "pi"}, "1": {"codex"}, "2": {"pi"}, "2, 1": {"codex", "pi"}, "0": nil,
	} {
		var expected []step
		for _, agent := range want {
			expected = append(expected, connected(agent)...)
		}
		s := &script{expected: expected, agents: []string{"codex", "pi"}, manifest: current, answer: &answer}
		if _, err := perform(t, s, installation, "install"); err != nil {
			t.Errorf("answer %q: %v", answer, err)
		}
		if !strings.Contains(s.asked, "1. Codex\n") || !strings.Contains(s.asked, "2. Pi\n") || strings.Contains(s.asked, "Claude") {
			t.Errorf("question %q", s.asked)
		}
	}
	for _, answer := range []string{"3", "x", "1 x", "0 1", "-1", "1-2"} {
		s := &script{agents: []string{"codex", "pi"}, manifest: current, answer: &answer}
		_, err := perform(t, s, installation, "install")
		if rejection := failure(t, err); rejection.Failure != UsageRejected {
			t.Errorf("answer %q: %v", answer, rejection)
		}
	}
}

func TestWithoutATerminalEveryAgentFoundIsConnected(t *testing.T) {
	s := &script{expected: append(connected("codex"), connected("pi")...), agents: []string{"pi", "codex"}, manifest: current}
	if out, err := perform(t, s, installation, "install"); err != nil || strings.Count(out, "Refreshed Kast 1.2.3") != 2 {
		t.Errorf("out %q err %v", out, err)
	}
}

func TestNamedAgentsAreNeverAskedAbout(t *testing.T) {
	answer := "0"
	s := &script{expected: connected("pi"), agents: keys(), manifest: current, answer: &answer}
	if _, err := perform(t, s, installation, "install", "pi"); err != nil || s.asked != "" {
		t.Errorf("asked %q err %v", s.asked, err)
	}
}

func TestOneAgentsFailureDoesNotHideAnother(t *testing.T) {
	expected := append([]step{{"codex plugin marketplace list --json", "", errors.New("no login")}}, connected("pi")...)
	s := &script{expected: expected, agents: []string{"codex", "pi"}, manifest: current}
	out, err := perform(t, s, installation, "install")
	if rejection := failure(t, err); rejection.Failure != CommandFailed || !strings.Contains(out, "in Pi") {
		t.Errorf("out %q err %v", out, err)
	}
}

func TestARefreshMovesOnlyTheAgentsThatHaveThisKast(t *testing.T) {
	expected := steps(claudeLists("", ""), codexLists(tools, "1.0.0"), "codex mcp remove kast", "codex plugin add kast@kast",
		codexLists(tools, version), copilotLists(foreign, version), piLists("", ""))
	s := &script{expected: expected, agents: keys(), manifest: current}
	out, err := perform(t, s, installation, "refresh")
	if err != nil || !strings.Contains(out, "Updated from 1.0.0 to Kast 1.2.3 in Codex") {
		t.Errorf("out %q err %v", out, err)
	}
	// The composition without the server stays the choice.
	if !slices.Equal(s.read, []string{tools + "/plugins/kast/package.json"}) {
		t.Errorf("read %v", s.read)
	}
	nothing := &script{expected: piLists("", ""), agents: []string{"pi"}, manifest: current}
	if out, err := perform(t, nothing, installation, "refresh"); err != nil || !strings.Contains(out, "No agent") {
		t.Errorf("out %q err %v", out, err)
	}
}

func TestARemovalRemovesOnlyWhatTheAgentHasAndNeedsNoInstallation(t *testing.T) {
	for name, test := range map[string]struct {
		agent string
		steps []step
		says  string
	}{
		"claude": {"claude", steps(claudeLists(withMCP, version), "claude plugin uninstall kast@kast",
			"claude plugin marketplace remove kast"), "Removed"},
		"codex": {"codex", steps(codexLists(tools, version), "codex plugin remove kast@kast",
			"codex plugin marketplace remove kast"), "Removed"},
		"copilot":          {"copilot", steps(copilotLists(withMCP, version), "copilot plugin marketplace remove kast --force"), "Removed"},
		"pi":               {"pi", steps(piLists(withMCP, ""), "pi remove "+withMCP+"/plugins/kast", "pi mcp remove kast"), "Removed"},
		"marketplace only": {"claude", steps(claudeLists(tools, ""), "claude plugin marketplace remove kast"), "Removed"},
		"plugin only":      {"codex", steps(codexLists("", version), "codex plugin remove kast@kast"), "Removed"},
		"nothing":          {"pi", piLists("", ""), "has no Kast plugin"},
	} {
		t.Run(name, func(t *testing.T) {
			s := &script{expected: test.steps, agents: keys()}
			out, err := perform(t, s, "", "remove", test.agent)
			if err != nil || !strings.Contains(out, test.says) || len(s.read) != 0 {
				t.Errorf("out %q err %v read %v", out, err, s.read)
			}
		})
	}
}

// An installation replaces a foreign Kast because the user asked for it. A removal has no such request.
func TestAForeignKastIsNeverRemoved(t *testing.T) {
	for agent, reply := range map[string][]step{
		"claude": claudeLists(foreign, version), "codex": codexLists("https://github.com/a/b/compositions/mcp", version),
		"copilot": lists("copilot", `[{"name":"kast","source":"GitHub: a/kast"}]`, `[]`),
	} {
		_, err := perform(t, &script{expected: reply, agents: keys()}, installation, "remove", agent)
		if rejection := failure(t, err); rejection.Failure != MarketplaceForeign {
			t.Errorf("%s: %v", agent, rejection)
		}
	}
}

func TestAnUnreadableListNeverMeansNothingIsInstalled(t *testing.T) {
	for agent, replies := range map[string][][2]string{
		"claude": {{`null`, `[]`}, {`[]`, `null`}, {`{}`, `[]`}, {`[]`, `oops`}, {``, `[]`}},
		"codex": {{`{}`, `{"installed":[]}`}, {`null`, `{"installed":[]}`}, {`{"marketplaces":[]}`, `{}`},
			{`{"marketplaces":[]}`, `{"installed":null}`}, {`[]`, `{"installed":[]}`}},
		"copilot": {{`null`, `[]`}, {`[]`, `{}`}, {``, `[]`}},
	} {
		for _, reply := range replies {
			for _, action := range []string{"install", "remove"} {
				s := &script{expected: lists(agent, reply[0], reply[1]), agents: keys(), manifest: current}
				_, err := perform(t, s, installation, action, agent)
				if rejection := failure(t, err); rejection.Failure != ReplyUnreadable {
					t.Errorf("%s %s %v: %v", action, agent, reply, rejection)
				}
			}
		}
	}
	for _, reply := range []string{"", "[]", "Usage: pi list", "User packages:\n  a\n    " + tools + "/plugins/kast\n  b\n    " + older + "/plugins/kast\n"} {
		s := &script{expected: []step{{"pi list", reply, nil}}, agents: keys(), manifest: current}
		_, err := perform(t, s, installation, "install", "pi")
		if rejection := failure(t, err); rejection.Failure != ReplyUnreadable {
			t.Errorf("%q: %v", reply, rejection)
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
		"no arguments":     {script{}, installation, nil, UsageRejected},
		"unknown agent":    {script{}, installation, []string{"install", "gemini"}, UsageRejected},
		"unknown action":   {script{}, installation, []string{"status", "codex"}, UsageRejected},
		"unknown option":   {script{}, installation, []string{"install", "codex", "--mcp"}, UsageRejected},
		"repeated option":  {script{}, installation, []string{"install", "codex", "--without-mcp", "--without-mcp"}, UsageRejected},
		"repeated agent":   {script{}, installation, []string{"install", "codex", "codex"}, UsageRejected},
		"removal option":   {script{}, installation, []string{"remove", "codex", "--without-mcp"}, UsageRejected},
		"refresh argument": {script{}, installation, []string{"refresh", "codex"}, UsageRejected},
		"no installation":  {script{manifest: current}, "", []string{"install", "claude"}, PluginUnavailable},
		"older plugin":     {script{}, installation, []string{"install", "claude"}, PluginUnavailable},
		"other manifest":   {script{manifest: `{"name":"other","version":"1"}`}, installation, []string{"install", "pi"}, PluginUnavailable},
		"no version":       {script{manifest: `{"name":"kast"}`}, installation, []string{"install", "pi"}, PluginUnavailable},
		"no agent":         {script{manifest: current}, installation, []string{"install"}, AgentUnavailable},
		"agent not found":  {script{manifest: current}, installation, []string{"install", "pi"}, AgentUnavailable},
		"remove, no agent": {script{}, "", []string{"remove", "codex"}, AgentUnavailable},
		"add fails": {script{agents: keys(), manifest: current, expected: append(steps(codexLists("", ""), "codex mcp remove kast"),
			step{"codex plugin marketplace add " + withMCP, "", broken})}, installation, []string{"install", "codex"}, CommandFailed},
		"old source stays": {script{agents: keys(), manifest: current, expected: append(steps(copilotLists(older, ""), "copilot mcp remove kast"),
			step{"copilot plugin marketplace remove kast --force", "", broken})}, installation, []string{"install", "copilot"}, CommandFailed},
		"server fails": {script{agents: keys(), manifest: current, expected: append(steps(piLists("", ""),
			"pi install "+withMCP+"/plugins/kast"), step{piServer, "", broken})}, installation, []string{"install", "pi"}, CommandFailed},
		"uninstall fails": {script{agents: keys(), expected: append(steps(claudeLists(tools, version)),
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
		tools + "/plugins/kast": true, withMCP + "/plugins/kast": true,
		installation: false, tools + "/compositions": false, tools + "/compositions/mcp/plugins": false,
		tools + "/plugins/kast/skills": false, foreign + "/plugins/kast": false,
		"/myshare/kast/agent-tools": false, "share/kast/agent-tools": false, "": false, "a github source": false,
	} {
		if owned(source) != want {
			t.Errorf("owned(%q) = %v", source, !want)
		}
	}
}
