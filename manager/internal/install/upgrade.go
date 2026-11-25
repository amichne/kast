package install

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const upgradeUsage = "Usage: kast upgrade [--channel stable|preview | --version X.Y.Z | --commit SHA | --repair] " +
	"[--dry-run] [--json]"

const (
	bridgePath    = "share/kast/install-candidate.sh"
	selectionPath = "share/kast/product-selection.json"
	buildPath     = "share/kast/build-record.json"
	localSchema   = 4
)

// Upgrade replaces the installed build. This process selects the build and presents the result; the installed, verified
// engine activates it, so the engine owns the replacement journal and the retirement of the old build.
type Upgrade struct {
	Install
	Temporary string
	// Activate runs the engine and returns its status. A cancelled context asks it to stop; it ends when the engine does.
	Activate func(ctx context.Context, command, environment []string, out, log io.Writer) (int, error)
	// Refresh updates the plugin of connected agents with the new command.
	Refresh func(ctx context.Context, command string, environment []string, log io.Writer) error
}

// NativeUpgrade performs one upgrade of the installation beneath home.
func NativeUpgrade(ctx context.Context, home, platform string, arguments []string, out, log io.Writer) error {
	upgrade := &Upgrade{
		Install: Install{
			Home: home, Platform: platform,
			Transport: &http.Transport{
				Proxy:                 http.ProxyFromEnvironment,
				DialContext:           (&net.Dialer{Timeout: 30 * time.Second}).DialContext,
				TLSHandshakeTimeout:   30 * time.Second,
				ResponseHeaderTimeout: 30 * time.Second,
				ForceAttemptHTTP2:     true,
				DisableCompression:    true,
			},
			Stall: time.Minute, Pause: time.Sleep, Now: time.Now, Terminal: terminal(log), Environment: os.Environ(),
			Out: out, Log: log,
		},
		Temporary: os.TempDir(), Activate: runEngine, Refresh: refreshAgents,
	}
	return upgrade.Run(ctx, arguments)
}

// runEngine keeps the engine out of the terminal's process group, so one interruption reaches it once. The engine ends
// its own work or leaves a journal; this process never kills it.
func runEngine(ctx context.Context, command, environment []string, out, log io.Writer) (int, error) {
	child := exec.CommandContext(ctx, command[0], command[1:]...)
	child.Env, child.Stdout, child.Stderr = environment, out, log
	child.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	child.Cancel = func() error { return child.Process.Signal(syscall.SIGTERM) }
	err := child.Run()
	var exited *exec.ExitError
	if errors.As(err, &exited) {
		return exited.ExitCode(), nil
	}
	return 0, err
}

func refreshAgents(ctx context.Context, command string, environment []string, log io.Writer) error {
	child := exec.CommandContext(ctx, command, "agent", "refresh")
	child.Env, child.Stdout, child.Stderr = environment, log, log
	return child.Run()
}

type upgradeOptions struct {
	// selected is nil when the recorded policy selects the build.
	selected             selector
	repair, dryRun, json bool
	help                 bool
}

// Run performs one upgrade. With --json, standard output holds exactly one document, also for a failure.
func (u *Upgrade) Run(ctx context.Context, arguments []string) error {
	parsed, err := parseUpgrade(arguments)
	var usage *Rejection
	if errors.As(err, &usage) {
		usage.Next = "Run `kast versions` to list the builds you can select."
	}
	if err == nil && parsed.help {
		fmt.Fprintln(u.Out, upgradeUsage+"\n\nInstalls the latest verified build of the recorded channel, or the selected build.\n"+
			"--repair reinstalls the exact installed build. --dry-run verifies a build without installing it.")
		return nil
	}
	if err == nil {
		err = u.perform(ctx, parsed)
	}
	var rejection *Rejection
	if parsed.json && errors.As(err, &rejection) && !rejection.reported {
		u.encode(rejectionDocument{"REJECTED", rejection.Failure, rejection.Detail, rejection.Next})
	}
	return err
}

func parseUpgrade(arguments []string) (upgradeOptions, error) {
	var parsed upgradeOptions
	selectors := 0
	for index := 0; index < len(arguments); index++ {
		name, value, inline := strings.Cut(arguments[index], "=")
		switch name {
		case "--channel", "--version", "--commit":
			selectors++
			if !inline {
				if index++; index == len(arguments) {
					return parsed, reject(UsageRejected, "%s needs a value\n%s", name, upgradeUsage)
				}
				value = arguments[index]
			}
			selected, ok := selection(name, value)
			if !ok {
				return parsed, reject(UsageRejected, "%s does not accept %q\n%s", name, value, upgradeUsage)
			}
			parsed.selected = selected
			continue
		}
		switch arguments[index] {
		case "--repair":
			selectors++
			parsed.repair = true
		case "--dry-run":
			parsed.dryRun = true
		case "--json":
			parsed.json = true
		case "--help", "-h":
			parsed.help = true
		default:
			return parsed, reject(UsageRejected, "unexpected argument %q\n%s", arguments[index], upgradeUsage)
		}
	}
	if selectors > 1 {
		return parsed, reject(UsageRejected, "select at most one build\n%s", upgradeUsage)
	}
	return parsed, nil
}

func (u *Upgrade) perform(ctx context.Context, parsed upgradeOptions) error {
	root, err := u.admitRoot()
	if err != nil {
		return err
	}
	installation := filepath.Join(root, "installation")
	if present(filepath.Join(root, "recovery", "replacement")) || present(filepath.Join(root, "shutdown.json")) {
		if err := admitPayload(installation); err != nil {
			return reject(RecoveryRequired, "an earlier upgrade did not finish, and its installation cannot finish it: %v", err)
		}
		return u.activate(ctx, parsed, root, "Finishing an earlier upgrade", []string{"--recover-only"})
	}
	if !present(installation) {
		return reject(NotInstalled, "%s", installation)
	}
	if err := admitPayload(installation); err != nil {
		return err
	}
	selected, found, err := u.choose(ctx, parsed, installation)
	if err != nil {
		return err
	}
	captured, err := u.capture(selected)
	if err != nil {
		return err
	}
	defer os.Remove(captured)
	title := "Reinstalling Kast " + selected.Record.version()
	if found != nil {
		title = fmt.Sprintf("Kast %s (%s), %s", found.version, found.channel, megabytes(found.bytes()))
	}
	return u.activate(ctx, parsed, root, title, []string{"--resolved-selection", captured})
}

// admitRoot names the one management root, as ManagementRootResolution does.
func (u *Upgrade) admitRoot() (string, error) {
	if !filepath.IsAbs(u.Home) {
		return "", reject(StorageRejected, "HOME is not an absolute path: %q", u.Home)
	}
	home, err := filepath.EvalSymlinks(u.Home)
	if err != nil {
		return "", reject(StorageRejected, "%v", err)
	}
	root := filepath.Join(home, ".local", "share", "kast")
	for _, entry := range u.Environment {
		if value, set := strings.CutPrefix(entry, "KAST_INSTALL_ROOT="); set && value != root {
			return "", reject(StorageRejected, "KAST_INSTALL_ROOT names another directory: %q", value)
		}
	}
	return root, nil
}

// selectionDocument is ProductSelection in managed_release.py, which admits it again.
type selectionDocument struct {
	Type          string     `json:"type"`
	ReleaseTag    string     `json:"releaseTag"`
	Record        recordText `json:"record"`
	RecordSHA256  string     `json:"recordSha256"`
	Policy        string     `json:"policy"`
	PublicVersion *string    `json:"publicVersion"`
	MainRevision  *string    `json:"mainRevision"`
}

// recordText is one build record's exact text.
type recordText jsontext.Value

func (r recordText) MarshalJSON() ([]byte, error) { return r, nil }

func (r recordText) version() string {
	var record struct {
		Version string `json:"version"`
	}
	_ = json.Unmarshal(r, &record)
	return record.Version
}

const (
	trackStable = "TrackStable"
	trackMain   = "TrackMain"
	pinnedBuild = "PinnedBuild"
)

// choose names the build. Only a repair has no published release to show.
func (u *Upgrade) choose(ctx context.Context, parsed upgradeOptions, installation string) (selectionDocument, *release,
	error) {
	if parsed.repair {
		selected, err := installedSelection(installation)
		return selected, nil, err
	}
	policy := pinnedBuild
	switch selected := parsed.selected.(type) {
	case nil:
		recorded, err := recordedPolicy(filepath.Dir(installation))
		if err != nil {
			return selectionDocument{}, nil, err
		}
		policy = recorded
		parsed.selected = channelHead{map[string]channel{trackStable: stable, trackMain: preview}[recorded]}
	case channelHead:
		policy = map[channel]string{stable: trackStable, preview: trackMain}[selected.channel]
	}
	found, err := u.resolve(ctx, parsed.selected)
	if err != nil {
		return selectionDocument{}, nil, err
	}
	document := selectionDocument{Type: "Product", ReleaseTag: found.tag, Record: recordText(found.record),
		RecordSHA256: found.sha, Policy: policy}
	if found.channel == stable {
		document.PublicVersion = &found.version
	} else {
		document.MainRevision = &found.revision
	}
	return document, found, nil
}

// recordedPolicy reads the update policy that the installation records.
func recordedPolicy(root string) (string, error) {
	raw, err := readRegular(filepath.Join(root, "management.json"), maximumRecord)
	if err != nil {
		return "", reject(InstallationDamaged, "the management record is unreadable: %v", err)
	}
	var document struct {
		Product struct {
			Policy string `json:"policy"`
		} `json:"product"`
	}
	if json.Unmarshal(raw, &document) != nil {
		return "", reject(InstallationDamaged, "the management record is invalid")
	}
	switch document.Product.Policy {
	case trackStable, trackMain:
		return document.Product.Policy, nil
	case pinnedBuild:
		return "", reject(PinnedBuild, "this installation is pinned to one build")
	default:
		return "", reject(InstallationDamaged, "the management record names no update policy")
	}
}

// installedSelection names the exact installed build.
func installedSelection(installation string) (selectionDocument, error) {
	raw, err := readRegular(filepath.Join(installation, selectionPath), maximumRecord)
	if err != nil {
		return selectionDocument{}, reject(InstallationDamaged, "the product selection is unreadable: %v", err)
	}
	record, err := readRegular(filepath.Join(installation, buildPath), maximumRecord)
	if err != nil {
		return selectionDocument{}, reject(InstallationDamaged, "the build record is unreadable: %v", err)
	}
	var installed struct {
		ReleaseTag        string  `json:"releaseTag"`
		PublicVersion     *string `json:"publicVersion"`
		SourceRevision    string  `json:"sourceRevision"`
		BuildRecordSHA256 string  `json:"buildRecordSha256"`
		Policy            string  `json:"policy"`
	}
	var schema struct {
		Version int `json:"schemaVersion"`
	}
	sum := sha256.Sum256(record)
	if json.Unmarshal(raw, &installed) != nil || json.Unmarshal(record, &schema) != nil ||
		hex.EncodeToString(sum[:]) != installed.BuildRecordSHA256 {
		return selectionDocument{}, reject(InstallationDamaged, "the product selection does not match the build record")
	}
	if schema.Version == localSchema {
		return selectionDocument{}, reject(LocalArtifactRequired, "this build was installed from a local directory")
	}
	document := selectionDocument{Type: "Product", ReleaseTag: installed.ReleaseTag, Record: recordText(record),
		RecordSHA256: installed.BuildRecordSHA256, Policy: installed.Policy, PublicVersion: installed.PublicVersion}
	if installed.PublicVersion == nil {
		document.MainRevision = &installed.SourceRevision
	}
	return document, nil
}

func (u *Upgrade) capture(selected selectionDocument) (string, error) {
	raw, err := json.Marshal(selected)
	if err != nil {
		return "", reject(RecordInvalid, "%v", err)
	}
	directory, err := filepath.EvalSymlinks(u.Temporary)
	if err != nil {
		return "", reject(StorageRejected, "%v", err)
	}
	file, err := os.CreateTemp(directory, "kast-selection-*.json")
	if err != nil {
		return "", reject(StorageRejected, "%v", err)
	}
	_, err = file.Write(raw)
	if err = errors.Join(err, file.Close()); err != nil {
		os.Remove(file.Name())
		return "", reject(StorageRejected, "%v", err)
	}
	return file.Name(), nil
}

// activate runs the engine and presents what it reports.
func (u *Upgrade) activate(ctx context.Context, parsed upgradeOptions, root, title string, options []string) error {
	command := append([]string{"/bin/bash", filepath.Join(root, "installation", bridgePath)}, options...)
	command = append(command, "--install-root", root, "--json")
	if parsed.dryRun {
		command = append(command, "--dry-run")
	}
	var out bytes.Buffer
	log, finish := u.Log, func() {}
	if !parsed.json {
		fmt.Fprintln(u.Out, title)
		stages := newStages(u.Log, u.Terminal, u.Now)
		log, finish = stages, stages.end
	}
	home := filepath.Dir(filepath.Dir(filepath.Dir(root)))
	status, err := u.Activate(ctx, command, withHome(u.Environment, home), &out, log)
	finish()
	if err != nil {
		return reject(ActivationRejected, "the installed engine could not run: %v", err)
	}
	if parsed.json {
		if _, err := u.Out.Write(out.Bytes()); err != nil {
			return reject(StorageRejected, "%v", err)
		}
	}
	result, err := admitResult(out.Bytes(), status)
	if err != nil {
		var rejection *Rejection
		if errors.As(err, &rejection) {
			rejection.reported = parsed.json && out.Len() > 0
		}
		return err
	}
	if !parsed.json {
		fmt.Fprintln(u.Out, result.text(parsed.dryRun))
	}
	if !parsed.dryRun {
		u.refresh(ctx, home)
	}
	return nil
}

// refresh reports on stderr. Its failure never changes the upgrade's result.
func (u *Upgrade) refresh(ctx context.Context, home string) {
	if err := u.Refresh(ctx, filepath.Join(home, ".local", "bin", "kast"), u.Environment, u.Log); err != nil {
		fmt.Fprintln(u.Log, "kast: upgrade: an agent keeps an older plugin. Run `kast agent refresh`.")
	}
}

func withHome(environment []string, home string) []string {
	selected := []string{"HOME=" + home}
	for _, entry := range environment {
		if !strings.HasPrefix(entry, "HOME=") {
			selected = append(selected, entry)
		}
	}
	return selected
}

func present(path string) bool {
	_, err := os.Lstat(path)
	return !errors.Is(err, os.ErrNotExist)
}

func readRegular(path string, limit int64) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > limit {
		return nil, errors.New("not a small regular file")
	}
	return os.ReadFile(path)
}
