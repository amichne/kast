package terminal

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestADocumentKeepsEveryFieldInOrder(t *testing.T) {
	got := Document(`{"type":"complete","items":[{"name":"A","startOffset":3},{"name":"B"}],"next_page":null,` +
		`"exhaustive":true,"empty":[],"tags":["x","y"],"text":"one\ntwo\n","nested":{"big":12345678901234567890}}`)
	want := `type: complete
items:
  - name: A
    start offset: 3
  - name: B
next page: none
exhaustive: yes
empty: none
tags:
  - x
  - y
text:
  one
  two
nested:
  big: 12345678901234567890`
	if got != want {
		t.Errorf("got\n%s\nwant\n%s", got, want)
	}
}

func TestOtherTextIsUnchanged(t *testing.T) {
	for _, text := range []string{"", "Usage: kast", "{broken", `{"a":1} trailing`, `"scalar"`, "12"} {
		if got := Document(text); got != text {
			t.Errorf("%q became %q", text, got)
		}
	}
}

func TestAResultCannotDriveTheTerminal(t *testing.T) {
	if got := Document(`{"a":"x\u001b[2Jy","b\u001b":"l\r\n\u0007m"}`); strings.ContainsAny(got, "\x1b\x07\r") {
		t.Errorf("control characters survived: %q", got)
	}
}

func TestLinesRenderDocumentsAndPassOtherText(t *testing.T) {
	var out bytes.Buffer
	lines := &Lines{Out: &out}
	for _, part := range []string{"plain {x}\n{\"stage\":\"SHUT", "DOWN\",\"outcome\":\"STARTED\"}\nContinue? "} {
		if _, err := lines.Write([]byte(part)); err != nil {
			t.Fatal(err)
		}
	}
	want := "plain {x}\nkast: stage: SHUTDOWN, outcome: STARTED\nContinue? "
	if out.String() != want {
		t.Errorf("got %q want %q", out.String(), want)
	}
	if lines.Write([]byte("{not json\n{\"a\":1}")); lines.Flush() != nil {
		t.Fatal("flush failed")
	}
	if want += "{not json\nkast: a: 1"; out.String() != want {
		t.Errorf("got %q want %q", out.String(), want)
	}
}

// container lays out one workspace's progress record.
func container(t *testing.T, generation, record string) Progress {
	t.Helper()
	base, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	workspace := filepath.Join(base, "workspace")
	digest := sha256.Sum256([]byte(workspace))
	root := filepath.Join(base, "store", "containers", hex.EncodeToString(digest[:]))
	state, selected := filepath.Join(root, "state"), "null"
	switch {
	case !filepath.IsLocal(generation) && generation != "":
		selected = `"` + generation + `"`
	case generation != "":
		state, selected = filepath.Join(root, "generations", generation, "state"), `"`+generation+`"`
	}
	for _, directory := range []string{filepath.Join(workspace, "module"), state} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for path, text := range map[string]string{
		filepath.Join(root, "generation.json"):     `{"generation":` + selected + `}`,
		filepath.Join(state, "host-progress.json"): record,
	} {
		if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return Progress{filepath.Join(base, "store"), filepath.Join(workspace, "module"), func(pid int) bool { return pid == 42 }}
}

const indexing = `{"schemaVersion":1,"hostPid":42,"phase":"INDEXING","index":{"type":"indexing","queuedFiles":106835}}`

func TestProgressNamesTheStepOfTheNearestWorkspace(t *testing.T) {
	for name, test := range map[string]struct{ generation, record, want string }{
		"first generation": {"", indexing, "(4/5): indexing ~106,835 files"},
		"later generation": {"g2", indexing, "(4/5): indexing ~106,835 files"},
		"overlapping work": {"", strings.Replace(indexing, `"INDEXING"`, `"IMPORTING"`, 1),
			"(2/5): importing the Gradle model, indexing ~106,835 files"},
		"prepared":        {"", strings.Replace(indexing, `"INDEXING"`, `"READY"`, 1), ""},
		"dead Host":       {"", strings.Replace(indexing, "42", "43", 1), ""},
		"later schema":    {"", strings.Replace(indexing, ":1,", ":2,", 1), ""},
		"unknown phase":   {"", strings.Replace(indexing, "INDEXING", "LATER", 1), ""},
		"unreadable":      {"", "{", ""},
		"escaping record": {"../../elsewhere", indexing, ""},
	} {
		t.Run(name, func(t *testing.T) {
			if got := container(t, test.generation, test.record).line(); got != test.want {
				t.Errorf("got %q want %q", got, test.want)
			}
		})
	}
}

func TestADrawnLineIsReplacedOnlyWhenItChanges(t *testing.T) {
	var out bytes.Buffer
	progress := container(t, "", indexing)
	drawn := progress.draw(&out, "", 2*time.Second)
	if drawn = progress.draw(&out, drawn, 2*time.Second); strings.Count(out.String(), eraseLine) != 1 {
		t.Errorf("redrawn: %q", out.String())
	}
	if want := "Preparing (4/5): indexing ~106,835 files, 2s"; drawn != want {
		t.Errorf("got %q want %q", drawn, want)
	}
}

func TestTheLongestLineKeepsItsElapsedTime(t *testing.T) {
	record := strings.NewReplacer(`"INDEXING"`, `"IMPORTING"`, "106835", "9999999").Replace(indexing)
	drawn := container(t, "", record).draw(&bytes.Buffer{}, "", 1020*time.Second)
	if !strings.HasSuffix(drawn, "indexing ~9,999,999 files, 1020s") {
		t.Errorf("got %q", drawn)
	}
}

func TestOneLineJoinsFields(t *testing.T) {
	if got, want := Line(`{"a":{"b":1},"c":[]}`), "kast: a: b: 1, c: none"; got != want {
		t.Errorf("got %q want %q", got, want)
	}
}
