// Package lifecycle_test enforces the effect boundary of the lifecycle core: its pure packages import nothing that
// reaches the file system, processes, the network, the clock, randomness, or the environment.
package lifecycle_test

import (
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
)

const lifecycleModule = "github.com/amichne/kast/frontend/internal/lifecycle/"

// pure lists the packages that must stay free of effects. Every directory below internal/lifecycle must be listed
// here or in effectful, so a new package needs an explicit decision.
var pure = []string{"codec", "envelope", "failure", "layout", "plan", "record", "selection"}

// effectful lists the effect adapters and the interpreter. Stage 1 has none.
var effectful []string

// banned are import paths, or prefixes ending in "/", that perform or expose effects. path/filepath is banned
// because it walks and resolves the live file system; pure code uses package path.
var banned = []string{
	"os", "os/", "net", "net/", "syscall", "time", "io/fs", "io/ioutil", "path/filepath", "crypto/rand",
	"math/rand", "math/rand/", "unsafe", "runtime", "runtime/", "log", "log/", "plugin", "C", "golang.org/x/sys/",
}

func isBanned(path string) bool {
	for _, ban := range banned {
		if path == ban || (strings.HasSuffix(ban, "/") && strings.HasPrefix(path, ban)) {
			return true
		}
	}
	return false
}

// violation names why an import is not allowed in a pure package, or returns "".
func violation(path string) string {
	switch {
	case isBanned(path):
		return "effectful standard package"
	case strings.HasPrefix(path, lifecycleModule):
		if !slices.Contains(pure, strings.TrimPrefix(path, lifecycleModule)) {
			return "lifecycle package that is not pure"
		}
	case strings.Contains(strings.SplitN(path, "/", 2)[0], "."):
		return "package outside the standard library"
	}
	return ""
}

// importsOf parses the import declarations of a package directory's non-test files.
func importsOf(t *testing.T, directory string) map[string][]string {
	t.Helper()
	entries, err := os.ReadDir(directory)
	if err != nil {
		t.Fatal(err)
	}
	imports := map[string][]string{}
	files := token.NewFileSet()
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		parsed, err := parser.ParseFile(files, filepath.Join(directory, name), nil, parser.ImportsOnly)
		if err != nil {
			t.Fatal(err)
		}
		for _, spec := range parsed.Imports {
			path, err := strconv.Unquote(spec.Path.Value)
			if err != nil {
				t.Fatal(err)
			}
			imports[name] = append(imports[name], path)
		}
	}
	return imports
}

func TestPurePackagesImportNoEffects(t *testing.T) {
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	var found []string
	for _, entry := range entries {
		if entry.IsDir() {
			found = append(found, entry.Name())
		}
	}
	for _, name := range found {
		if !slices.Contains(pure, name) && !slices.Contains(effectful, name) {
			t.Errorf("internal/lifecycle/%s is neither declared pure nor effectful", name)
		}
	}
	for _, name := range pure {
		if !slices.Contains(found, name) {
			t.Errorf("declared pure package %s does not exist", name)
			continue
		}
		imports := importsOf(t, name)
		if len(imports) == 0 {
			t.Errorf("%s has no source files", name)
		}
		for file, paths := range imports {
			for _, path := range paths {
				if reason := violation(path); reason != "" {
					t.Errorf("%s/%s imports %q: %s", name, file, path, reason)
				}
			}
		}
	}
}

// TestTheBanRecognizesEveryEffect proves the rule itself: each banned import is caught, and the imports the core
// needs are not.
func TestTheBanRecognizesEveryEffect(t *testing.T) {
	for _, path := range []string{"os", "os/exec", "os/signal", "net", "net/http", "syscall", "time", "io/fs",
		"path/filepath", "crypto/rand", "math/rand/v2", "runtime/debug", "golang.org/x/sys/unix", "C",
		"github.com/coder/websocket", lifecycleModule + "effect/fs", lifecycleModule + "run"} {
		if violation(path) == "" {
			t.Errorf("%q is admitted", path)
		}
	}
	for _, path := range []string{"encoding/json", "crypto/sha256", "path", "strings", "slices", "io",
		lifecycleModule + "record"} {
		if reason := violation(path); reason != "" {
			t.Errorf("%q is rejected: %s", path, reason)
		}
	}
	source := "package probe\nimport (\n\t\"os\"\n\tclock \"time\"\n)\n"
	parsed, err := parser.ParseFile(token.NewFileSet(), "probe.go", source, parser.ImportsOnly)
	if err != nil {
		t.Fatal(err)
	}
	caught := 0
	for _, spec := range parsed.Imports {
		path, _ := strconv.Unquote(spec.Path.Value)
		if violation(path) != "" {
			caught++
		}
	}
	if caught != 2 {
		t.Errorf("caught %d of 2 banned imports, including a renamed one", caught)
	}
}
