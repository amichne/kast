package frontend

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func writeCatalog(t *testing.T, root string, document map[string]any) {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(root, "share", "kast"), 0o700); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "share", "kast", "frontend.json"), encoded, 0o600); err != nil {
		t.Fatal(err)
	}
}

func validCatalog() map[string]any {
	return map[string]any{
		"schemaVersion":      1,
		"invocationProtocol": 1,
		"socket":             "state/run/c.sock",
		"socketAlias": map[string]any{
			"maximumPathBytes": 102, "directory": "/tmp", "prefix": "kast-uds-", "digestCharacters": 32,
		},
		"handshakeMilliseconds": 10000,
		"outputSetting":         map[string]any{"variable": "KAST_OUTPUT_TOON", "admittedValues": []string{"", "0", "1"}},
		"publicNamespaces":      []string{"query", "describe"},
		"control": map[string]any{
			"mainClass":       "io.github.amichne.kast.cli.KastControlMain",
			"classPath":       []string{"lib/kast-cli.jar", "lib/kotlin-stdlib.jar"},
			"javaHomes":       map[string]string{platform(): "host/jbr"},
			"sharedArchive":   "lib/control.jsa",
			"archiveTraining": []string{"--internal-entrypoint=management", "--help"},
		},
		"exits": map[string]int{
			"COMPLETE": 0, "DEFECT": 1, "INPUT": 2, "WORKSPACE": 3, "RUNTIME": 4, "IO": 5, "PROTOCOL": 6,
			"BOOTSTRAP": 9, "SEMANTIC": 10, "DELIVERY": 11, "ELAPSED": 124, "INTERRUPTED": 130, "TERMINATED": 143,
		},
		"help": []map[string]any{{"arguments": []string{"query", "--help"}, "text": "kast query"}},
	}
}

func catalogFailure(t *testing.T, err error) CatalogFailure {
	t.Helper()
	var failure *CatalogError
	if !errors.As(err, &failure) {
		t.Fatalf("expected a catalog failure, got %v", err)
	}
	return failure.Failure
}

func TestVersionsAreAdmittedBeforeAnyOtherField(t *testing.T) {
	root := t.TempDir()
	document := validCatalog()
	document["schemaVersion"] = 2
	document["socket"] = "/absolute/elsewhere"
	document["exits"] = "not a table"
	writeCatalog(t, root, document)
	_, err := LoadCatalog(root)
	if got := catalogFailure(t, err); got != CatalogIncompatible {
		t.Fatalf("expected incompatibility to win over shape failures, got %v", got)
	}
	if status := err.(*CatalogError).Status(); status != protocolStatus {
		t.Fatalf("incompatibility status = %d", status)
	}
}

func TestPathsMustStayBeneathTheRoot(t *testing.T) {
	for _, field := range []string{"socket"} {
		root := t.TempDir()
		document := validCatalog()
		document[field] = "../outside/c.sock"
		writeCatalog(t, root, document)
		if _, err := LoadCatalog(root); catalogFailure(t, err) != CatalogMalformed {
			t.Fatalf("%s escaping the root was admitted", field)
		}
	}
}

func TestEveryStatusTheFrontendEmitsIsRequired(t *testing.T) {
	for _, name := range requiredStatuses {
		root := t.TempDir()
		document := validCatalog()
		exits := map[string]int{}
		for key, value := range document["exits"].(map[string]int) {
			if key != name {
				exits[key] = value
			}
		}
		document["exits"] = exits
		writeCatalog(t, root, document)
		if _, err := LoadCatalog(root); catalogFailure(t, err) != CatalogMalformed {
			t.Fatalf("catalog without %s was admitted", name)
		}
	}
}

func TestAdditiveMetadataKeepsAnUnchangedFrontendWorking(t *testing.T) {
	root := t.TempDir()
	document := validCatalog()
	document["completion"] = map[string]any{"version": 1}
	document["help"] = append(document["help"].([]map[string]any),
		map[string]any{"arguments": []string{"impact", "--help"}, "text": "kast impact", "since": "a newer coordinator"})
	writeCatalog(t, root, document)
	catalog, err := LoadCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	if text, ok := catalog.Help([]string{"impact", "--help"}, nil); !ok || text != "kast impact" {
		t.Fatalf("added help entry unavailable: %q %v", text, ok)
	}
}

func TestHelpMatchesOnlyExactArgumentVectors(t *testing.T) {
	root := t.TempDir()
	writeCatalog(t, root, validCatalog())
	catalog, err := LoadCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := catalog.Help([]string{"query", "--help"}, nil); !ok {
		t.Fatal("exact help vector missed")
	}
	for _, near := range [][]string{{"query", "--help", "--json"}, {"query"}, {"--help", "query"}} {
		if _, ok := catalog.Help(near, nil); ok {
			t.Fatalf("%v was answered locally instead of by the coordinator", near)
		}
	}
}

func TestHelpForwardsAnOutputSettingTheShellWouldReject(t *testing.T) {
	root := t.TempDir()
	writeCatalog(t, root, validCatalog())
	catalog, err := LoadCatalog(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{"", "0", "1"} {
		if _, ok := catalog.Help([]string{"query", "--help"}, map[string]string{"KAST_OUTPUT_TOON": value}); !ok {
			t.Fatalf("admitted output setting %q was not answered locally", value)
		}
	}
	if _, ok := catalog.Help([]string{"query", "--help"}, map[string]string{"KAST_OUTPUT_TOON": "yes"}); ok {
		t.Fatal("an unadmitted output setting was answered locally instead of rejected by the coordinator")
	}
}

func TestMissingTransportBoundsAreMalformed(t *testing.T) {
	for _, field := range []string{"handshakeMilliseconds", "outputSetting", "control", "socketAlias"} {
		root := t.TempDir()
		document := validCatalog()
		delete(document, field)
		writeCatalog(t, root, document)
		if _, err := LoadCatalog(root); catalogFailure(t, err) != CatalogMalformed {
			t.Fatalf("catalog without %s was admitted", field)
		}
	}
}

func TestAClassPathOutsideTheInstallationIsMalformed(t *testing.T) {
	for _, entry := range []string{"/elsewhere/kast.jar", "../kast.jar", "lib/kast.class"} {
		root := t.TempDir()
		document := validCatalog()
		document["control"].(map[string]any)["classPath"] = []string{entry}
		writeCatalog(t, root, document)
		if _, err := LoadCatalog(root); catalogFailure(t, err) != CatalogMalformed {
			t.Fatalf("class path entry %q was admitted", entry)
		}
	}
}

// The fixed bootstrap statuses must equal the coordinator's published table. The acceptance run supplies the catalog
// generated by :cli:generateFrontendCatalog; without it this comparison is missing evidence and is reported as a skip.
func TestFixedStatusesMatchTheGeneratedCatalog(t *testing.T) {
	path := os.Getenv("KAST_FRONTEND_CATALOG")
	if path == "" {
		t.Skip("KAST_FRONTEND_CATALOG names no generated catalog")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var document catalogDocument
	if err := json.Unmarshal(raw, &document); err != nil {
		t.Fatal(err)
	}
	if document.SchemaVersion != supportedCatalogSchema || document.InvocationProtocol != supportedInvocationProtocol {
		t.Fatalf("generated catalog speaks schema %d protocol %d", document.SchemaVersion, document.InvocationProtocol)
	}
	if document.Exits["BOOTSTRAP"] != bootstrapStatus || document.Exits["PROTOCOL"] != protocolStatus {
		t.Fatalf("fixed statuses diverge from %v", document.Exits)
	}
	for _, name := range requiredStatuses {
		if _, ok := document.Exits[name]; !ok {
			t.Fatalf("generated catalog lacks %s", name)
		}
	}
	if document.HandshakeMilliseconds <= 0 || document.OutputSetting.Variable != "KAST_OUTPUT_TOON" {
		t.Fatalf("generated catalog lacks transport bounds: %d %+v", document.HandshakeMilliseconds, document.OutputSetting)
	}
}
