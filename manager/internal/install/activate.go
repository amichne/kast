package install

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"time"
)

// An activation replaces the installation in place. Every step before retirement only writes beside the management
// root, so an interruption there leaves the installed build untouched; the steps after it are short renames and
// receipts. Running the command again removes what an interrupted run left and finishes or undoes its switch.
const (
	retireTimeout = 5 * time.Minute
	fenceName     = "shutdown.json"
)

// runner runs one child to its end, or stops it after the timeout. Its status is the child's exit status.
type runner func(ctx context.Context, command, environment []string, timeout time.Duration) (int, error)

// runChild ends a child and everything it started when it outlives its timeout. A cancelled context does not end it:
// the caller decides when an effect may stop. Its output goes to the null device, so no descendant can hold a pipe
// that the wait reads.
func runChild(_ context.Context, command, environment []string, timeout time.Duration) (int, error) {
	child := exec.Command(command[0], command[1:]...)
	child.Env = environment
	child.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := child.Start(); err != nil {
		return 0, err
	}
	group := -child.Process.Pid
	timer := time.AfterFunc(timeout, func() { _ = syscall.Kill(group, syscall.SIGTERM) })
	defer timer.Stop()
	err := child.Wait()
	var exited *exec.ExitError
	if errors.As(err, &exited) {
		return exited.ExitCode(), nil
	}
	return 0, err
}

// shutdownRequest is InstallationShutdownRequest in InstallationShutdownFence.kt: while it exists, the JVM admits no
// new request against the installation.
type shutdownRequest struct {
	InstallationRoot string `json:"installationRoot"`
	SchemaVersion    int    `json:"schemaVersion"`
}

// managementReceipt is ProductManagement in managed_product_installation.py. The Control executable reads its runtime
// root, and the next upgrade its policy and command digest.
type managementReceipt struct {
	InstallationRoot string           `json:"installationRoot"`
	Executable       string           `json:"executable"`
	ExecutableSHA256 string           `json:"executableSha256"`
	Product          productReference `json:"product"`
	SchemaVersion    int              `json:"schemaVersion"`
}

// paths are the locations one activation reads and writes beneath an admitted home.
type paths struct {
	home, share, root, installation, command, receipt, fence string
}

func pathsOf(root string) paths {
	share := filepath.Dir(root)
	home := filepath.Dir(filepath.Dir(share))
	return paths{home: home, share: share, root: root, installation: filepath.Join(root, "installation"),
		command: filepath.Join(home, ".local", "bin", "kast"), receipt: filepath.Join(root, "management.json"),
		fence: filepath.Join(root, fenceName)}
}

// Interrupted work beside the management root, by the names `kast reset` also removes.
var (
	uuidPart    = `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`
	stagedName  = regexp.MustCompile(`^\.kast-(install-` + uuidPart + `|runtime-[a-z0-9_]{8})$`)
	retiredForm = `^\.kast-retiring-%s-` + uuidPart + `$`
)

func (p paths) retiredName() *regexp.Regexp {
	return regexp.MustCompile(fmt.Sprintf(retiredForm, sha256Hex([]byte(p.root))[:32]))
}

// lockRoot takes the two record locks that the JVM's workspace edits and uninstall take, without waiting.
func lockRoot(root string) (release func(), err error) {
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, storageFailure(err)
	}
	var held []*os.File
	release = func() {
		for _, file := range held {
			file.Close()
		}
	}
	for _, name := range []string{"management.lock", "activation.lock"} {
		file, err := os.OpenFile(filepath.Join(root, name), os.O_CREATE|os.O_RDWR|syscall.O_NOFOLLOW, 0o600)
		if err != nil {
			release()
			return nil, storageFailure(err)
		}
		held = append(held, file)
		lock := syscall.Flock_t{Type: syscall.F_WRLCK, Whence: io.SeekStart}
		if err := syscall.FcntlFlock(file.Fd(), syscall.F_SETLK, &lock); err != nil {
			release()
			return nil, reject(ActivationBusy, "another Kast command holds %s", file.Name())
		}
	}
	return release, nil
}

// recoverLeftovers removes what an interrupted activation left. A switch interrupted between its two renames is undone,
// so the installation that was retired returns.
func recoverLeftovers(p paths) error {
	entries, err := os.ReadDir(p.share)
	if err != nil {
		return storageFailure(err)
	}
	retired := p.retiredName()
	var retiring []string
	for _, entry := range entries {
		path := filepath.Join(p.share, entry.Name())
		switch {
		case retired.MatchString(entry.Name()):
			retiring = append(retiring, path)
		case stagedName.MatchString(entry.Name()):
			if err := removeTree(path); err != nil {
				return err
			}
		}
	}
	if !present(p.installation) && len(retiring) > 0 {
		if len(retiring) > 1 {
			return reject(RecoveryRequired, "the installation is missing and %d retired ones remain", len(retiring))
		}
		if err := os.Rename(retiring[0], p.installation); err != nil {
			return storageFailure(err)
		}
		retiring = nil
	}
	for _, path := range retiring {
		if err := removeTree(path); err != nil {
			return err
		}
	}
	return nil
}

// removeTree deletes a tree that this package wrote, including the read-only directories of a sealed runtime.
func removeTree(root string) error {
	_ = filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err == nil && entry.IsDir() {
			_ = os.Chmod(path, 0o700)
		}
		return nil
	})
	return storageFailure(os.RemoveAll(root))
}

// prior is the installation an activation replaces.
type prior struct {
	installation string
}

// admitPrior proves what the activation may replace. Without an installation, nothing of Kast's may exist: no receipt,
// no command, and no workspace that a Host could still run.
func admitPrior(p paths) (*prior, error) {
	if !present(p.installation) {
		switch {
		case present(p.receipt):
			return nil, reject(InstallationDamaged, "%s exists without an installation", p.receipt)
		case present(p.command):
			return nil, reject(CommandForeign, "%s exists and is not part of an installation", p.command)
		}
		containers := filepath.Join(p.share, "kast-workspaces", "containers")
		if names, err := entryNames(containers); err != nil || len(names) > 0 {
			return nil, reject(InstallationDamaged, "%s holds workspaces without an installation", containers)
		}
		return nil, nil
	}
	if err := admitPayload(p.installation); err != nil {
		return nil, err
	}
	raw, err := readRegular(p.receipt, maximumRecord)
	if err != nil {
		return nil, reject(InstallationDamaged, "the management record is unreadable: %v", err)
	}
	var receipt managementReceipt
	if json.Unmarshal(raw, &receipt) != nil || receipt.SchemaVersion != 3 || receipt.InstallationRoot != p.root ||
		receipt.Executable != p.command {
		return nil, reject(InstallationDamaged, "the management record names another installation")
	}
	// A switch interrupted after its command was written has the installation's command and the earlier receipt.
	command, err := fileDigest(p.command)
	installed, failure := fileDigest(filepath.Join(p.installation, managementPath))
	if err != nil || failure != nil || (command != receipt.ExecutableSHA256 && command != installed) {
		return nil, reject(CommandForeign, "%s is not the command this installation wrote", p.command)
	}
	return &prior{p.installation}, nil
}

// retire stops every workspace Host and the semantic coordinator through the installed build, which leaves its
// shutdown request in place. The request then holds off new work until the switch removes it.
func (u *Upgrade) retire(ctx context.Context, p paths, old *prior) error {
	if old == nil {
		return nil
	}
	command := []string{filepath.Join(old.installation, managementPath), "--internal-install", "managed-retire"}
	environment := append(withHome(u.Environment, p.home), "KAST_INSTALL_ROOT="+p.root)
	status, err := u.Child(ctx, command, environment, retireTimeout)
	if err != nil || status != 0 {
		detail := fmt.Sprintf("status %d", status)
		if err != nil {
			detail = err.Error()
		}
		return &Rejection{Failure: StopRejected, Detail: "the installed build did not stop its workspace Hosts: " + detail,
			Next: next[StopRejected]}
	}
	raw, err := readRegular(p.fence, maximumRecord)
	var request shutdownRequest
	if err != nil || json.Unmarshal(raw, &request) != nil || request.InstallationRoot != p.installation {
		return reject(StopRejected, "the installed build stopped without holding off new requests")
	}
	return nil
}

// swap makes the staged payload the installation, then writes the command and the receipt that name it. Only the
// moment between its two renames lacks an installation, and recoverLeftovers undoes it.
func swap(p paths, stage string, reference productReference) (left string, err error) {
	retired := ""
	if present(p.installation) {
		retired = filepath.Join(p.share, ".kast-retiring-"+sha256Hex([]byte(p.root))[:32]+"-"+newUUID())
		if err := os.Rename(p.installation, retired); err != nil {
			return "", storageFailure(err)
		}
	}
	if err := os.Rename(stage, p.installation); err != nil {
		if retired != "" {
			err = errors.Join(err, os.Rename(retired, p.installation))
		}
		return "", storageFailure(err)
	}
	if err := errors.Join(syncDirectory(p.root), syncDirectory(p.share)); err != nil {
		return "", storageFailure(err)
	}
	management := filepath.Join(p.installation, managementPath)
	executable, err := fileDigest(management)
	if err != nil {
		return "", storageFailure(err)
	}
	if err := os.MkdirAll(filepath.Dir(p.command), 0o755); err != nil {
		return "", storageFailure(err)
	}
	source, err := os.Open(management)
	if err != nil {
		return "", storageFailure(err)
	}
	defer source.Close()
	if err := replaceFile(p.command, ".kast-managed-"+newUUID()+"-command", source, 0o755); err != nil {
		return "", err
	}
	receipt := managementReceipt{p.root, p.command, executable, reference, 3}
	if err := replaceFile(p.receipt, ".management-"+newUUID(), strings.NewReader(string(encodedLine(receipt))),
		0o600); err != nil {
		return "", err
	}
	if err := os.Remove(p.fence); err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", storageFailure(err)
	}
	if err := syncDirectory(p.root); err != nil {
		return "", storageFailure(err)
	}
	if retired != "" && removeTree(retired) != nil {
		return retired, nil
	}
	return "", nil
}

// replaceFile writes a temporary sibling and renames it over the destination.
func replaceFile(destination, temporary string, data io.Reader, mode os.FileMode) error {
	staged := filepath.Join(filepath.Dir(destination), temporary)
	if err := writeSealed(staged, data, mode); err != nil {
		os.Remove(staged)
		return err
	}
	if err := os.Rename(staged, destination); err != nil {
		os.Remove(staged)
		return storageFailure(err)
	}
	return storageFailure(syncDirectory(filepath.Dir(destination)))
}

func newUUID() string {
	var value [16]byte
	_, _ = rand.Read(value[:])
	value[6] = value[6]&0x0f | 0x40
	value[8] = value[8]&0x3f | 0x80
	text := hex.EncodeToString(value[:])
	return text[:8] + "-" + text[8:12] + "-" + text[12:16] + "-" + text[16:20] + "-" + text[20:]
}

// runtimeStageName is a tempfile name as Python writes it, so `kast reset` removes an interrupted one.
func runtimeStageName() string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789_"
	var value [8]byte
	_, _ = rand.Read(value[:])
	for index := range value {
		value[index] = letters[int(value[index])%len(letters)]
	}
	return ".kast-runtime-" + string(value[:])
}

func sha256Hex(raw []byte) string {
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}
