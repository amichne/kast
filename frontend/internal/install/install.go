// Package install selects one published Kast build, shows it, and downloads its verified files. It changes no
// installation: it writes only its own download directory, and a dry run writes nothing.
package install

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

const usage = "Usage: kast install (--dry-run | --download-only) [--channel preview|stable | --version X.Y.Z | --commit SHA] " +
	"[--json]"

// Install is one invocation. Every effect outside the home's download directory goes through an injected adapter.
type Install struct {
	Home     string
	Platform string
	// Transport performs each HTTPS request.
	Transport http.RoundTripper
	// Stall ends a connection that delivers no byte for this long.
	Stall time.Duration
	Pause func(time.Duration)
	Now   func() time.Time
	// Terminal permits a redrawn progress line on Log.
	Terminal bool
	Out, Log io.Writer
}

// Native performs one invocation against the published releases.
func Native(ctx context.Context, home, platform string, arguments []string, out, log io.Writer) error {
	install := &Install{
		Home: home, Platform: platform,
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           (&net.Dialer{Timeout: 30 * time.Second}).DialContext,
			TLSHandshakeTimeout:   30 * time.Second,
			ResponseHeaderTimeout: 30 * time.Second,
			ForceAttemptHTTP2:     true,
			DisableCompression:    true,
		},
		Stall: time.Minute, Pause: time.Sleep, Now: time.Now, Terminal: terminal(log), Out: out, Log: log,
	}
	return install.Run(ctx, arguments)
}

func terminal(log io.Writer) bool {
	file, ok := log.(*os.File)
	if !ok || os.Getenv("TERM") == "dumb" {
		return false
	}
	info, err := file.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

const (
	dryRun       = "--dry-run"
	downloadOnly = "--download-only"
)

// Mode reports whether an argument selects what an invocation does.
func Mode(argument string) bool { return argument == dryRun || argument == downloadOnly }

type options struct {
	selected selector
	download bool
	json     bool
}

// Run performs one invocation. With --json, standard output holds exactly one document, also for a failure.
func (i *Install) Run(ctx context.Context, arguments []string) error {
	parsed, err := parse(arguments)
	if err == nil {
		err = i.perform(ctx, parsed)
	}
	var rejection *Rejection
	if parsed.json && errors.As(err, &rejection) {
		i.encode(rejectionDocument{"REJECTED", rejection.Failure, rejection.Detail, rejection.Next})
	}
	return err
}

func parse(arguments []string) (options, error) {
	parsed := options{selected: channelHead{preview}}
	modes, selectors := 0, 0
	for index := 0; index < len(arguments); index++ {
		switch argument := arguments[index]; argument {
		case dryRun:
			modes++
		case downloadOnly:
			modes++
			parsed.download = true
		case "--json":
			parsed.json = true
		case "--channel", "--version", "--commit":
			selectors++
			if index++; index == len(arguments) {
				return parsed, reject(UsageRejected, "%s needs a value\n%s", argument, usage)
			}
			selected, ok := selection(argument, arguments[index])
			if !ok {
				return parsed, reject(UsageRejected, "%s does not accept %q\n%s", argument, arguments[index], usage)
			}
			parsed.selected = selected
		default:
			return parsed, reject(UsageRejected, "unexpected argument %q\n%s", argument, usage)
		}
	}
	if modes != 1 || selectors > 1 {
		return parsed, reject(UsageRejected, "select one mode and at most one build\n%s", usage)
	}
	return parsed, nil
}

func selection(option, value string) (selector, bool) {
	switch option {
	case "--channel":
		return channelHead{channel(value)}, value == string(preview) || value == string(stable)
	case "--version":
		return oneVersion{value}, versionForm.MatchString(value)
	default:
		return oneCommit{value}, commitForm.MatchString(value)
	}
}

func (i *Install) perform(ctx context.Context, parsed options) error {
	name, ok := platformNames[i.Platform]
	if !ok {
		return reject(PlatformUnsupported, "Kast is not published for %s", i.Platform)
	}
	found, err := i.resolve(ctx, parsed.selected)
	if err != nil {
		return err
	}
	if !parsed.json {
		fmt.Fprintf(i.Out, "Kast %s (%s) for %s\n", found.version, found.channel, name)
	}
	if parsed.download {
		return i.acquire(ctx, found, parsed.json)
	}
	if parsed.json {
		return i.encode(report("PLANNED", found, map[string]bool{}))
	}
	fmt.Fprintf(i.Out, "  Download     %s in %d files\n  Nothing was downloaded or installed.\n", megabytes(found.bytes()),
		len(found.assets))
	return nil
}

// acquire downloads what the store lacks. Each file's digest is verified before it gets its final name.
func (i *Install) acquire(ctx context.Context, found *release, quiet bool) error {
	store, err := openStore(i.Home)
	if err != nil {
		return err
	}
	defer store.close()
	reused, present := map[string]bool{}, int64(0)
	for _, asset := range found.assets {
		if complete(store.path(asset), asset) {
			reused[asset.Name], present = true, present+asset.Bytes
		}
	}
	meter := &progress{log: i.Log, terminal: i.Terminal, silent: quiet, now: i.Now}
	if present < found.bytes() {
		meter.start(found.bytes(), present)
		for _, asset := range found.assets {
			if err == nil && !reused[asset.Name] {
				err = i.download(ctx, store, found.tag, asset, meter)
			}
		}
		meter.finish()
		if err != nil {
			return err
		}
	}
	if err := store.prune(found.assets); err != nil {
		return err
	}
	if quiet {
		return i.encode(report("DOWNLOADED", found, reused))
	}
	fmt.Fprintf(i.Out, "  Verified     %d files, %s", len(found.assets), megabytes(found.bytes()))
	if present > 0 {
		fmt.Fprintf(i.Out, " (%s already here)", megabytes(present))
	}
	fmt.Fprintf(i.Out, "\n  Saved in     %s\n  Nothing was installed.\n", store.directory)
	return nil
}

type fileDocument struct {
	Asset  `json:",inline"`
	Reused bool `json:"reused"`
}

type reportDocument struct {
	Type     string         `json:"type"`
	Release  string         `json:"release"`
	Version  string         `json:"version"`
	Channel  channel        `json:"channel"`
	Revision string         `json:"revision"`
	Target   string         `json:"target"`
	Files    []fileDocument `json:"files"`
}

type rejectionDocument struct {
	Type    string  `json:"type"`
	Failure Failure `json:"failure"`
	Detail  string  `json:"detail"`
	Next    string  `json:"next"`
}

func report(kind string, found *release, reused map[string]bool) reportDocument {
	document := reportDocument{Type: kind, Release: found.tag, Version: found.version, Channel: found.channel,
		Revision: found.revision, Target: found.target}
	for _, asset := range found.assets {
		document.Files = append(document.Files, fileDocument{asset, reused[asset.Name]})
	}
	return document
}

func (i *Install) encode(document any) error {
	raw, err := json.Marshal(document)
	if err == nil {
		_, err = i.Out.Write(append(raw, '\n'))
	}
	return err
}
