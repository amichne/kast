package install

import (
	"bytes"
	"context"
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

const (
	upgradeUsage = "Usage: kast upgrade [--channel stable|preview | --version X.Y.Z | --commit SHA | --repair] " +
		"[--dry-run] [--json]"
	activateUsage = "Usage: kast install [--channel stable|preview | --version X.Y.Z | --commit SHA] [--json]"
)

const (
	// bridgePath is the engine of an installation that Python installed. It only finishes an upgrade it recorded.
	bridgePath    = "share/kast/install-candidate.sh"
	selectionPath = "share/kast/product-selection.json"
	buildPath     = "share/kast/build-record.json"
	localSchema   = 4
)

// Upgrade installs one build in place of the installed one, or as the first installation. This process verifies,
// unpacks and switches; the outgoing build stops its own workspace Hosts.
type Upgrade struct {
	Install
	Temporary string
	// Engine runs the installed Python engine, which only finishes an upgrade that it recorded. A cancelled context
	// asks it to stop; it ends when the engine does.
	Engine func(ctx context.Context, command, environment []string, out, log io.Writer) (int, error)
	// Child runs the outgoing build's retirement and the class-data training.
	Child runner
	// Refresh updates the plugin of connected agents with the new command.
	Refresh func(ctx context.Context, command string, environment []string, log io.Writer) error
}

// NativeUpgrade performs one `kast upgrade` beneath home.
func NativeUpgrade(ctx context.Context, home, platform string, arguments []string, out, log io.Writer) error {
	return native(home, platform, out, log).Run(ctx, arguments)
}

// NativeActivate performs one `kast install` that installs, beneath home.
func NativeActivate(ctx context.Context, home, platform string, arguments []string, out, log io.Writer) error {
	return native(home, platform, out, log).RunInstall(ctx, arguments)
}

func native(home, platform string, out, log io.Writer) *Upgrade {
	return &Upgrade{
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
		Temporary: os.TempDir(), Engine: runEngine, Child: runChild, Refresh: refreshAgents,
	}
}

// Activates reports whether `kast install` with these arguments installs a build, rather than only planning or
// downloading one.
func Activates(arguments []string) bool {
	for _, argument := range arguments {
		if Mode(argument) {
			return false
		}
	}
	return true
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
	// first means that no installation is required: `kast install`.
	first bool
}

// Run performs one upgrade. With --json, standard output holds exactly one document, also for a failure.
func (u *Upgrade) Run(ctx context.Context, arguments []string) error {
	parsed, err := parseUpgrade(arguments)
	return u.finish(ctx, parsed, err, upgradeUsage+"\n\nInstalls the latest verified build of the recorded channel, or the "+
		"selected build.\n--repair reinstalls the exact installed build. --dry-run verifies a build without installing it.")
}

// RunInstall installs the selected build, the latest stable one by default, over any installation.
func (u *Upgrade) RunInstall(ctx context.Context, arguments []string) error {
	parsed, err := parseUpgrade(arguments)
	var usage *Rejection
	if err == nil && (parsed.repair || parsed.dryRun) {
		err = reject(UsageRejected, "%s", activateUsage)
	} else if errors.As(err, &usage) {
		usage.Detail = strings.ReplaceAll(usage.Detail, upgradeUsage, activateUsage)
	}
	parsed.first = true
	if parsed.selected == nil {
		parsed.selected = channelHead{stable}
	}
	return u.finish(ctx, parsed, err, activateUsage+"\n\nInstalls the latest stable build, or the selected build.\n"+
		"`kast install --dry-run` shows the build without downloading it.")
}

func (u *Upgrade) finish(ctx context.Context, parsed upgradeOptions, err error, help string) error {
	var usage *Rejection
	if errors.As(err, &usage) {
		usage.Next = "Run `kast versions` to list the builds you can select."
	}
	if err == nil && parsed.help {
		fmt.Fprintln(u.Out, help)
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
	if _, supported := platformNames[u.Platform]; !supported {
		return reject(PlatformUnsupported, "Kast is not published for %s", u.Platform)
	}
	installation := filepath.Join(root, "installation")
	// Only the Python engine writes this journal, and only it can finish what the journal records.
	if present(filepath.Join(root, "recovery", "replacement")) {
		return u.recoverJournal(ctx, parsed, root)
	}
	// An interrupted switch can leave no installation, so its leftovers are settled before the installation is read.
	if present(root) || parsed.first {
		if err := settleLeftovers(pathsOf(root)); err != nil {
			return err
		}
	}
	if !present(installation) && !parsed.first {
		return reject(NotInstalled, "%s", installation)
	}
	if present(installation) {
		if err := admitPayload(installation); err != nil {
			return err
		}
	}
	found, policy, err := u.choose(ctx, parsed, installation)
	if err != nil {
		return err
	}
	return u.activate(ctx, parsed, pathsOf(root), found, policy)
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

const (
	trackStable = "TrackStable"
	trackMain   = "TrackMain"
	pinnedBuild = "PinnedBuild"
)

// choose admits the build and the policy that the installation records with it.
func (u *Upgrade) choose(ctx context.Context, parsed upgradeOptions, installation string) (*release, string, error) {
	if parsed.repair {
		return installedRelease(installation, u.Platform)
	}
	policy := pinnedBuild
	switch selected := parsed.selected.(type) {
	case nil:
		recorded, err := recordedPolicy(filepath.Dir(installation))
		if err != nil {
			return nil, "", err
		}
		policy = recorded
		parsed.selected = channelHead{map[string]channel{trackStable: stable, trackMain: preview}[recorded]}
	case channelHead:
		policy = map[channel]string{stable: trackStable, preview: trackMain}[selected.channel]
	}
	found, err := u.resolve(ctx, parsed.selected)
	return found, policy, err
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

// installedRelease admits the exact installed build again, as its own record names it.
func installedRelease(installation, platform string) (*release, string, error) {
	raw, err := readRegular(filepath.Join(installation, selectionPath), maximumRecord)
	if err != nil {
		return nil, "", reject(InstallationDamaged, "the product selection is unreadable: %v", err)
	}
	record, err := readRegular(filepath.Join(installation, buildPath), maximumRecord)
	if err != nil {
		return nil, "", reject(InstallationDamaged, "the build record is unreadable: %v", err)
	}
	var installed productReference
	var schema struct {
		Version int `json:"schemaVersion"`
	}
	if json.Unmarshal(raw, &installed) != nil || json.Unmarshal(record, &schema) != nil ||
		sha256Hex(record) != installed.BuildRecordSHA256 {
		return nil, "", reject(InstallationDamaged, "the product selection does not match the build record")
	}
	if schema.Version == localSchema {
		return nil, "", reject(LocalArtifactRequired, "this build was installed from a local directory")
	}
	found := reference{tag: installed.ReleaseTag, sha: installed.BuildRecordSHA256, channel: preview,
		public: installed.Version, revision: installed.SourceRevision}
	if installed.PublicVersion != nil {
		found = reference{tag: installed.ReleaseTag, sha: installed.BuildRecordSHA256, channel: stable,
			public: *installed.PublicVersion}
	}
	admitted, err := admit(record, found, platform)
	return admitted, installed.Policy, err
}

// installedDocument and plannedDocument are ProductInstalled and ProductPlanned in managed_product_installation.py.
type (
	installedDocument struct {
		Product    productReference `json:"product"`
		Executable string           `json:"executable"`
		Type       string           `json:"type"`
		HostState  string           `json:"hostState"`
	}
	plannedDocument struct {
		Product productReference `json:"product"`
		Type    string           `json:"type"`
	}
)

// activate downloads the build, unpacks it beside the installation, retires the installed build and switches to the
// new one. Until retirement begins, an interruption changes nothing that the installation uses.
func (u *Upgrade) activate(ctx context.Context, parsed upgradeOptions, p paths, found *release, policy string) error {
	if !parsed.json {
		fmt.Fprintf(u.Out, "Kast %s (%s), %s\n", found.version, found.channel, megabytes(found.bytes()))
	}
	files, err := u.obtain(ctx, found, parsed.json)
	if err != nil {
		return err
	}
	defer files.close()
	enter, finish := func(string) {}, func() {}
	if !parsed.json {
		stage := newStages(u.Log, u.Terminal, u.Now)
		enter, finish = stage.enter, stage.end
	}
	reference, installedAt, left, err := u.replace(ctx, parsed, p, found, policy, files, enter)
	finish()
	if err != nil {
		return err
	}
	if parsed.dryRun {
		if parsed.json {
			return u.encode(plannedDocument{reference, "PRODUCT_PLANNED"})
		}
		fmt.Fprintf(u.Out, "Kast %s is verified. Nothing was installed.\n", found.version)
		return nil
	}
	if left != "" {
		fmt.Fprintf(u.Log, "kast: %s remains; the next upgrade removes it.\n", left)
	}
	if parsed.json {
		u.encode(installedDocument{reference, installedAt, "PRODUCT_INSTALLED", "NOT_STARTED"})
	} else {
		fmt.Fprintf(u.Out, "Kast %s is installed. Start a fresh agent session to use it.\n", found.version)
	}
	u.refresh(ctx, p.home)
	return nil
}

// replace performs the activation's effects in order. It returns the installed product, its command, and a retired
// installation that could not be deleted.
func (u *Upgrade) replace(ctx context.Context, parsed upgradeOptions, p paths, found *release, policy string,
	files *store, enter func(string)) (productReference, string, string, error) {
	var reference productReference
	release, err := lockRoot(p.root)
	if err != nil {
		return reference, "", "", err
	}
	defer release()
	if err := recoverLeftovers(p); err != nil {
		return reference, "", "", err
	}
	old, err := admitPrior(p)
	if err != nil {
		return reference, "", "", err
	}
	facts := found.facts
	source := &runtimeSource{runtime: files.path(facts.Runtime), base: files.path(facts.RuntimeBase),
		qualification: files.path(facts.Qualification.Receipt), facts: facts, record: found.record,
		recordSHA: found.sha, target: found.target}
	if err := readRuntimeManifest(source, found.recordVersion); err != nil {
		return reference, "", "", err
	}
	root := runtimeRoot(p.share, facts.Runtime.SHA256)
	reference = productReference{ReleaseTag: found.tag, Version: found.recordVersion, SourceRevision: found.revision,
		SourceTree: found.tree, BuildRecordSHA256: found.sha, Target: found.target, BundleSHA256: facts.Bundle.SHA256,
		QualificationSHA256: facts.Qualification.Receipt.SHA256, RuntimeRoot: root, RuntimeArchiveSHA256: facts.Runtime.SHA256,
		RuntimeManifestSHA256: source.manifestSHA, Policy: policy, SchemaVersion: 1}
	if found.channel == stable {
		reference.PublicVersion = &found.version
	}
	enter("Unpacking the runtime")
	composed, err := prepareRuntime(source, p, root)
	if composed != "" {
		defer removeTree(composed)
	}
	if err != nil {
		return reference, "", "", err
	}
	enter("Unpacking Kast")
	staged := filepath.Join(p.share, ".kast-install-"+newUUID())
	if err := os.Mkdir(staged, 0o700); err != nil {
		return reference, "", "", storageFailure(err)
	}
	defer removeTree(staged)
	control := &controlSource{bundle: files.path(facts.Bundle), version: found.recordVersion, target: found.target,
		management: facts.Qualification.Control, record: found.record, reference: reference, home: p.home}
	if old != nil {
		control.prior = old.installation
	}
	controlSHA, err := extractControl(control, staged)
	if err == nil {
		err = stageRecords(control, staged)
	}
	if err != nil || parsed.dryRun {
		return reference, "", "", err
	}
	if err := interrupted(ctx); err != nil {
		return reference, "", "", err
	}
	if composed != "" {
		if err := os.Rename(composed, root); err != nil {
			return reference, "", "", storageFailure(err)
		}
		if err := syncDirectory(filepath.Dir(root)); err != nil {
			return reference, "", "", storageFailure(err)
		}
	}
	enter("Preparing a faster start")
	if _, err := trainArchive(ctx, u.Child, staged, root, found.target, u.Temporary); err != nil {
		return reference, "", "", err
	}
	if err := sealManifest(control, staged, p.installation, controlSHA); err != nil {
		return reference, "", "", err
	}
	if err := interrupted(ctx); err != nil {
		return reference, "", "", err
	}
	// From here the installed build stops, so the switch runs to its end.
	ctx = context.WithoutCancel(ctx)
	if old != nil {
		enter("Stopping workspace Hosts")
	}
	if err := u.retire(ctx, p, old); err != nil {
		return reference, "", "", err
	}
	enter("Switching to the new build")
	left, err := swap(p, staged, reference)
	return reference, p.command, left, err
}

// prepareRuntime composes the runtime beside the store unless the store already holds it. It returns the composed
// directory, which the caller publishes or removes.
func prepareRuntime(source *runtimeSource, p paths, root string) (string, error) {
	reusable, err := reusableRuntime(root, source)
	if err != nil || reusable {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(root), 0o700); err != nil {
		return "", storageFailure(err)
	}
	composed := filepath.Join(p.share, runtimeStageName())
	return composed, composeRuntime(source, composed, root)
}

// settleLeftovers holds the locks only while it recovers, so a long download never holds off the JVM's workspace edits.
func settleLeftovers(p paths) error {
	release, err := lockRoot(p.root)
	if err != nil {
		return err
	}
	defer release()
	return recoverLeftovers(p)
}

func interrupted(ctx context.Context) error {
	if ctx.Err() != nil {
		return reject(Interrupted, "nothing was changed")
	}
	return nil
}

// obtain downloads what the store lacks, with the same view as `kast install --download-only`.
func (u *Upgrade) obtain(ctx context.Context, found *release, quiet bool) (*store, error) {
	files, err := openStore(u.Home)
	if err != nil {
		return nil, err
	}
	p := newProgress(found.parts, u.Now)
	var plain io.Writer = io.Discard
	finish := p.end
	switch {
	case quiet:
	case u.Terminal:
		finish = u.watch(ctx, p)
	default:
		plain = u.Log
	}
	_, err = u.fill(ctx, files, found, p, plain)
	finish()
	if err == nil {
		err = files.prune(found.parts)
	}
	if err != nil {
		files.close()
		return nil, err
	}
	return files, nil
}

// recoverJournal lets the installed Python engine finish the upgrade that it recorded.
func (u *Upgrade) recoverJournal(ctx context.Context, parsed upgradeOptions, root string) error {
	installation := filepath.Join(root, "installation")
	if err := admitPayload(installation); err != nil || !present(filepath.Join(installation, bridgePath)) {
		return reject(RecoveryRequired, "an earlier upgrade did not finish, and its installation cannot finish it")
	}
	command := []string{"/bin/bash", filepath.Join(installation, bridgePath), "--recover-only", "--install-root", root,
		"--json"}
	if parsed.dryRun {
		command = append(command, "--dry-run")
	}
	var out bytes.Buffer
	log, finish := u.Log, func() {}
	if !parsed.json {
		fmt.Fprintln(u.Out, "Finishing an earlier upgrade")
		stages := newStages(u.Log, u.Terminal, u.Now)
		log, finish = stages, stages.end
	}
	home := filepath.Dir(filepath.Dir(filepath.Dir(root)))
	status, err := u.Engine(ctx, command, withHome(u.Environment, home), &out, log)
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
		fmt.Fprintln(u.Log, "kast: an agent keeps an older plugin. Run `kast agent refresh`.")
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
