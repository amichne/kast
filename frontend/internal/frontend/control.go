package frontend

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
)

// payloadPath is the Control executable inside an installation. The installer copies the same bytes to the public
// command path, so the binary locates its installation from either place.
const payloadPath = "share/kast/libexec/kast-management"

// entrypointPrefix selects a private Control entry, as the installed launchers and service jobs pass it.
const entrypointPrefix = "--internal-entrypoint="

const maximumReceiptBytes = 1 << 20

// invocation is the admitted shape of the process arguments: launcher JVM options, the selected entry, and the
// arguments that entry receives unchanged.
type invocation struct {
	jvmOptions []string
	entry      string
	arguments  []string
}

// jvmOption reports a leading process option for the JVM, the same set admitNativeControlProcessInvocation admits.
func jvmOption(argument string) bool {
	if strings.HasPrefix(argument, "-D") && strings.Contains(argument, "=") {
		return true
	}
	for _, prefix := range []string{"-XX:", "-Xmn", "-Xms", "-Xmx", "-Xss"} {
		if strings.HasPrefix(argument, prefix) {
			return true
		}
	}
	return false
}

// parseInvocation separates the leading JVM options and the optional private entry selector. Without a selector the
// entry is management, the public command's default.
func parseInvocation(arguments []string) invocation {
	index := 0
	for index < len(arguments) && jvmOption(arguments[index]) {
		index++
	}
	parsed := invocation{jvmOptions: arguments[:index], entry: "management", arguments: arguments[index:]}
	if len(parsed.arguments) > 0 && strings.HasPrefix(parsed.arguments[0], entrypointPrefix) {
		parsed.entry = strings.TrimPrefix(parsed.arguments[0], entrypointPrefix)
		parsed.arguments = parsed.arguments[1:]
	}
	return parsed
}

// servedByCoordinator reports whether the persistent coordinator serves this invocation. Hook namespaces read
// process-local state, so they always run in their own JVM.
func (c *Catalog) servedByCoordinator(parsed invocation) bool {
	if parsed.entry != "management" && parsed.entry != "query" {
		return false
	}
	if len(parsed.arguments) == 0 {
		return false
	}
	namespace := parsed.arguments[0]
	if namespace == "hook" || namespace == "workspace" {
		return false
	}
	return parsed.entry == "query" || slices.Contains(c.publicNamespaces, namespace)
}

// locateInstallation resolves the installation from the executable. The payload sits beneath its installation; the
// public copy belongs to the one installation the management root admits, beneath HOME.
func locateInstallation(executable string, environment map[string]string) (string, error) {
	resolved, err := filepath.EvalSymlinks(executable)
	if err != nil {
		return "", err
	}
	if strings.HasSuffix(resolved, string(filepath.Separator)+filepath.FromSlash(payloadPath)) {
		return filepath.Clean(strings.TrimSuffix(resolved, filepath.FromSlash(payloadPath))), nil
	}
	home := environment["HOME"]
	if !filepath.IsAbs(home) {
		return "", errors.New("HOME is not absolute")
	}
	// Kotlin admits KAST_INSTALL_ROOT only when it names this same root, and presents its own rejection otherwise.
	installation, err := filepath.EvalSymlinks(filepath.Join(home, ".local", "share", "kast", "installation"))
	if err != nil {
		return "", err
	}
	return installation, nil
}

// runtimeHome reads the Host runtime the installation's management receipt records and selects this platform's Java
// home inside it. The JVM entry re-admits its own runtime against the receipt before any effect.
func (c *Catalog) runtimeHome(installation string) (string, error) {
	file, err := os.Open(filepath.Join(filepath.Dir(installation), "management.json"))
	if err != nil {
		return "", err
	}
	defer file.Close()
	raw, err := io.ReadAll(io.LimitReader(file, maximumReceiptBytes+1))
	if err != nil || len(raw) > maximumReceiptBytes {
		return "", errors.New("management receipt unreadable")
	}
	var receipt struct {
		Product struct {
			RuntimeRoot string `json:"runtimeRoot"`
		} `json:"product"`
	}
	if json.Unmarshal(raw, &receipt) != nil || !filepath.IsAbs(receipt.Product.RuntimeRoot) {
		return "", errors.New("management receipt names no runtime")
	}
	relative, ok := c.control.JavaHomes[platform()]
	if !ok || !filepath.IsLocal(relative) {
		return "", errors.New("no Java home for this platform")
	}
	return filepath.Join(receipt.Product.RuntimeRoot, relative), nil
}

// commandProperty names the command the caller ran, which the JVM can no longer observe after exec. It equals
// CONTROL_COMMAND_PROPERTY in distribution/cli.
const commandProperty = "kast.command"

// jvmCommand is the exact process the JVM entry runs: the runtime's java, the command the caller ran, the launcher
// options, the installation's explicit class path, the single Control main class, and the entry with its arguments.
func (c *Catalog) jvmCommand(installation, javaHome, executable string, parsed invocation) []string {
	classPath := make([]string, len(c.control.ClassPath))
	for i, entry := range c.control.ClassPath {
		classPath[i] = filepath.Join(installation, entry)
	}
	command := []string{filepath.Join(javaHome, "bin", "java"), "-D" + commandProperty + "=" + executable}
	command = append(command, parsed.jvmOptions...)
	command = append(command, "-cp", strings.Join(classPath, string(filepath.ListSeparator)), c.control.MainClass,
		entrypointPrefix+parsed.entry)
	return append(command, parsed.arguments...)
}

// platform is the product's public platform name for this binary.
func platform() string {
	os := map[string]string{"darwin": "macos", "linux": "linux"}[runtime.GOOS]
	arch := map[string]string{"arm64": "aarch64", "amd64": "x86_64"}[runtime.GOARCH]
	return os + "-" + arch
}
