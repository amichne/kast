// Package frontend is the thin Kast command-line frontend. It owns bootstrap, transport, signals, output delivery and
// process status. The coordinator owns lowering, defaults, workspace decisions and semantic admission.
package frontend

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"time"
)

// Fixed bootstrap statuses. They are the public shell's published BOOTSTRAP and PROTOCOL codes and apply before an
// admitted catalog supplies the full table; tests compare them with the generated catalog.
const (
	bootstrapStatus = 9
	protocolStatus  = 6
)

// supportedCatalogSchema and supportedInvocationProtocol are the only versions this binary speaks. A coordinator that
// adds commands or help keeps both, so this binary needs no rebuild.
const (
	supportedCatalogSchema      = 1
	supportedInvocationProtocol = 1
	maximumCatalogBytes         = 4 << 20
)

// requiredStatuses are the statuses this frontend emits itself.
var requiredStatuses = []string{"DEFECT", "INPUT", "RUNTIME", "IO", "PROTOCOL", "BOOTSTRAP", "INTERRUPTED", "TERMINATED"}

type catalogDocument struct {
	SchemaVersion         int            `json:"schemaVersion"`
	InvocationProtocol    int            `json:"invocationProtocol"`
	Socket                string         `json:"socket"`
	Bootstrap             []string       `json:"bootstrap"`
	HandshakeMilliseconds int64          `json:"handshakeMilliseconds"`
	OutputSetting         outputSetting  `json:"outputSetting"`
	Exits                 map[string]int `json:"exits"`
	Help                  []helpEntry    `json:"help"`
}

// outputSetting is the environment variable the public shell admits before help, and the values it accepts.
type outputSetting struct {
	Variable       string   `json:"variable"`
	AdmittedValues []string `json:"admittedValues"`
}

type helpEntry struct {
	Arguments []string `json:"arguments"`
	Text      string   `json:"text"`
}

// Catalog is admitted coordinator metadata: its paths are resolved beneath the root and its statuses are complete.
type Catalog struct {
	root          string
	socket        string
	bootstrap     []string
	handshake     time.Duration
	outputSetting outputSetting
	exits         map[string]int
	help          []helpEntry
}

// CatalogFailure is a closed set of reasons the installed metadata cannot be used.
type CatalogFailure int

const (
	CatalogUnavailable CatalogFailure = iota
	CatalogMalformed
	CatalogIncompatible
)

// CatalogError carries the failure and, for incompatibility, the versions the coordinator declared.
type CatalogError struct {
	Failure            CatalogFailure
	SchemaVersion      int
	InvocationProtocol int
}

func (e *CatalogError) Error() string {
	switch e.Failure {
	case CatalogIncompatible:
		return fmt.Sprintf(
			"the installed coordinator declares catalog schema %d and invocation protocol %d; "+
				"this frontend speaks schema %d and protocol %d. Reinstall Kast to match the frontend and coordinator.",
			e.SchemaVersion, e.InvocationProtocol, supportedCatalogSchema, supportedInvocationProtocol)
	case CatalogMalformed:
		return "the installed command metadata is malformed. Reinstall Kast."
	default:
		return "the installed command metadata is unavailable. Reinstall Kast."
	}
}

// Status reports the exit status the catalog failure maps to.
func (e *CatalogError) Status() int {
	if e.Failure == CatalogIncompatible {
		return protocolStatus
	}
	return bootstrapStatus
}

// LoadCatalog admits root/share/kast/frontend.json. Versions are checked before any other field is trusted.
func LoadCatalog(root string) (*Catalog, error) {
	file, err := os.Open(filepath.Join(root, "share", "kast", "frontend.json"))
	if err != nil {
		return nil, &CatalogError{Failure: CatalogUnavailable}
	}
	defer file.Close()
	raw, err := io.ReadAll(io.LimitReader(file, maximumCatalogBytes+1))
	if err != nil {
		return nil, &CatalogError{Failure: CatalogUnavailable}
	}
	if len(raw) > maximumCatalogBytes {
		return nil, &CatalogError{Failure: CatalogMalformed}
	}
	var versions struct {
		SchemaVersion      int `json:"schemaVersion"`
		InvocationProtocol int `json:"invocationProtocol"`
	}
	if json.Unmarshal(raw, &versions) != nil {
		return nil, &CatalogError{Failure: CatalogMalformed}
	}
	if versions.SchemaVersion != supportedCatalogSchema || versions.InvocationProtocol != supportedInvocationProtocol {
		return nil, &CatalogError{
			Failure:            CatalogIncompatible,
			SchemaVersion:      versions.SchemaVersion,
			InvocationProtocol: versions.InvocationProtocol,
		}
	}
	// Within one schema version the coordinator may add metadata this frontend does not read; incompatible changes
	// change the version, which is admitted above.
	var document catalogDocument
	if json.Unmarshal(raw, &document) != nil {
		return nil, &CatalogError{Failure: CatalogMalformed}
	}
	socket, socketOK := beneath(root, document.Socket)
	if !socketOK || len(document.Bootstrap) == 0 || document.HandshakeMilliseconds <= 0 ||
		document.OutputSetting.Variable == "" {
		return nil, &CatalogError{Failure: CatalogMalformed}
	}
	launcher, launcherOK := beneath(root, document.Bootstrap[0])
	if !launcherOK {
		return nil, &CatalogError{Failure: CatalogMalformed}
	}
	for _, name := range requiredStatuses {
		if _, present := document.Exits[name]; !present {
			return nil, &CatalogError{Failure: CatalogMalformed}
		}
	}
	return &Catalog{
		root:          root,
		socket:        socket,
		bootstrap:     append([]string{launcher}, document.Bootstrap[1:]...),
		handshake:     time.Duration(document.HandshakeMilliseconds) * time.Millisecond,
		outputSetting: document.OutputSetting,
		exits:         document.Exits,
		help:          document.Help,
	}, nil
}

// Help returns the presented help document for an exact argument vector. The shell admits the output setting before
// help, so an unadmitted value is not answered here; the coordinator presents the same rejection.
func (c *Catalog) Help(arguments []string, environment map[string]string) (string, bool) {
	for _, entry := range c.help {
		if !slices.Equal(entry.Arguments, arguments) {
			continue
		}
		value, present := environment[c.outputSetting.Variable]
		if present && !slices.Contains(c.outputSetting.AdmittedValues, value) {
			return "", false
		}
		return entry.Text, true
	}
	return "", false
}

func (c *Catalog) status(name string) int { return c.exits[name] }

// beneath resolves a relative catalog path and rejects any path that leaves the root.
func beneath(root, relative string) (string, bool) {
	if relative == "" || filepath.IsAbs(relative) || !filepath.IsLocal(relative) {
		return "", false
	}
	return filepath.Join(root, relative), true
}
