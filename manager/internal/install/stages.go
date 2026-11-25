package install

import (
	"bytes"
	"encoding/json/v2"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"
)

// stageWords name what the engine does, for a reader. Its stages are Stage in managed_release.py and
// managed_update_contract.py.
var stageWords = map[string]string{
	"STAGING": "Preparing the download", "DOWNLOAD": "Downloading and verifying", "RECORDS": "Checking the release records",
	"ARCHIVE": "Unpacking", "CONTRACT": "Checking the components", "PREPARATION": "Preparing the runtime",
	"RE_ADMISSION": "Checking the installed build", "JOURNAL": "Recording the change", "FENCE": "Holding new requests",
	"REQUEST_RETIREMENT": "Stopping workspace Hosts", "HOST_SETTLEMENT": "Waiting for workspace Hosts",
	"CONTROL_REPLACEMENT": "Switching to the new build", "CONTROL_ARCHIVE": "Preparing a faster start",
	"HOST_ACTIVATION": "Activating the Host", "RECEIPTS": "Writing the receipts", "FINALIZATION": "Finishing",
	"RECOVERY": "Finishing an earlier upgrade", "SELECTION": "Selecting the build",
}

func stageText(stage string) string {
	if text, known := stageWords[stage]; known {
		return text
	}
	return spoken(stage)
}

// spoken turns a code into words: "UNSETTLED_INVOCATIONS" reads as "unsettled invocations".
func spoken(code string) string { return strings.ToLower(strings.ReplaceAll(code, "_", " ")) }

const (
	stageRedraw = time.Second
	eraseLine   = "\r\x1b[2K"
	// maximumStageLine bounds what one incomplete line of the engine can hold.
	maximumStageLine = 64 << 10
)

// stages presents the engine's stage lines. Every other line of the engine is dropped: its result names each failure.
type stages struct {
	log      io.Writer
	terminal bool
	now      func() time.Time

	mutex   sync.Mutex
	held    bytes.Buffer
	current string
	began   time.Time
	stopped chan struct{}
	ended   chan struct{}
}

func newStages(log io.Writer, terminal bool, now func() time.Time) *stages {
	s := &stages{log: log, terminal: terminal, now: now, stopped: make(chan struct{}), ended: make(chan struct{})}
	if !terminal {
		close(s.ended)
		return s
	}
	go s.redraw()
	return s
}

// redraw shows how long the current stage runs, so a long one does not look stuck.
func (s *stages) redraw() {
	defer close(s.ended)
	ticker := time.NewTicker(stageRedraw)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopped:
			return
		case <-ticker.C:
			s.mutex.Lock()
			if elapsed := int(s.now().Sub(s.began).Seconds()); s.current != "" && elapsed > 0 {
				fmt.Fprintf(s.log, "%s  %s  %ds", eraseLine, s.current, elapsed)
			}
			s.mutex.Unlock()
		}
	}
}

func (s *stages) Write(data []byte) (int, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.held.Write(data)
	for {
		end := bytes.IndexByte(s.held.Bytes(), '\n')
		if end < 0 {
			break
		}
		s.accept(s.held.Next(end + 1))
	}
	if s.held.Len() > maximumStageLine {
		s.held.Reset()
	}
	return len(data), nil
}

func (s *stages) accept(line []byte) {
	var document struct {
		Stage   string `json:"stage"`
		Outcome string `json:"outcome"`
	}
	if json.Unmarshal(line, &document) != nil || document.Stage == "" || document.Outcome != "STARTED" {
		return
	}
	text := stageText(document.Stage)
	if text == s.current {
		return
	}
	s.settle()
	s.current, s.began = text, s.now()
	if s.terminal {
		fmt.Fprint(s.log, eraseLine+"  "+text)
		return
	}
	fmt.Fprintln(s.log, "  "+text)
}

// settle ends the current line on a terminal.
func (s *stages) settle() {
	if s.terminal && s.current != "" {
		fmt.Fprintln(s.log, eraseLine+"  "+s.current)
	}
	s.current = ""
}

func (s *stages) end() {
	close(s.stopped)
	<-s.ended
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.settle()
}

// resultDocument is what this process reads of the engine's one terminal result.
type resultDocument struct {
	Type    string `json:"type"`
	Stage   string `json:"stage"`
	Reason  string `json:"reason"`
	Adapter string `json:"adapterCode"`
	State   *struct {
		Component string   `json:"component"`
		Entries   []string `json:"entries"`
	} `json:"state"`
	Transfer *struct {
		Cause      string `json:"cause"`
		HTTPStatus int    `json:"httpStatus"`
	} `json:"transfer"`
	Failure *resultDocument `json:"failure"`
	Product struct {
		Version       string  `json:"version"`
		PublicVersion *string `json:"publicVersion"`
	} `json:"product"`
}

func (r *resultDocument) text(dryRun bool) string {
	version := r.Product.Version
	if r.Product.PublicVersion != nil {
		version = *r.Product.PublicVersion
	}
	if dryRun {
		return "Kast " + version + " is verified. Nothing was installed."
	}
	return "Kast " + version + " is installed. Start a fresh agent session to use it."
}

// admitResult needs a success document and a success status. Either one alone is not success.
func admitResult(raw []byte, status int) (*resultDocument, error) {
	var result resultDocument
	if json.Unmarshal(bytes.TrimSpace(raw), &result) != nil {
		return nil, reject(ActivationRejected, "the engine ended with status %d and no result", status)
	}
	switch result.Type {
	case "PRODUCT_INSTALLED", "PRODUCT_PLANNED":
		if status != 0 {
			return nil, reject(ActivationRejected, "the engine reported success but ended with status %d", status)
		}
		return &result, nil
	case "REJECTED":
		return nil, result.rejection(ActivationRejected)
	case "RECOVERY_REQUIRED":
		cause := result.Failure
		if cause == nil {
			cause = &result
		}
		return nil, cause.rejection(RecoveryRequired)
	default:
		return nil, reject(ActivationRejected, "the engine reported an unknown result %q", result.Type)
	}
}

// nextByReason overrides a failure's general action when the engine's reason has a better one.
var nextByReason = map[string]string{
	"STORAGE_EXHAUSTED":              "Free disk space, then run the same command again.",
	"UNSETTLED_INVOCATIONS":          "Wait for running Kast requests to end, then run the same command again.",
	"UNRETIRED_WORKSPACE_ENTRY":      "Run `kast stop --all`, then run the same command again.",
	"CONCURRENT_CHANGE":              "Wait for the other Kast command to end, then run the same command again.",
	"PRIOR_INSTALLATION_UNSUPPORTED": "Run `kast reset` to see what it would remove, then install again.",
}

func (r *resultDocument) rejection(failure Failure) error {
	detail := spoken(r.Reason)
	if r.Stage != "" {
		detail = strings.ToLower(stageText(r.Stage)) + ": " + detail
	}
	if r.Adapter != "" {
		detail += " (" + spoken(r.Adapter) + ")"
	}
	if r.State != nil {
		detail += " in " + r.State.Component + ": " + strings.Join(r.State.Entries, ", ")
	}
	if r.Transfer != nil {
		detail += ", " + spoken(r.Transfer.Cause)
		if r.Transfer.HTTPStatus != 0 {
			detail += fmt.Sprintf(" %d", r.Transfer.HTTPStatus)
		}
	}
	rejection := reject(failure, "%s", detail).(*Rejection)
	if action, better := nextByReason[r.Reason]; better {
		rejection.Next = action
	}
	return rejection
}
