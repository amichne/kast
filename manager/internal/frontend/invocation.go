package frontend

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"github.com/coder/websocket"
)

const invocationRoute = "ws://localhost/kast-public-invocation"

// invocationRequest is the typed envelope. Arguments, environment and stdin stay opaque to this frontend.
type invocationRequest struct {
	Version     int               `json:"version"`
	Directory   string            `json:"directory"`
	Arguments   []string          `json:"arguments"`
	Environment map[string]string `json:"environment"`
}

// reply is the union of server messages, discriminated by Type.
type reply struct {
	Type         string       `json:"type"`
	MaximumBytes *int         `json:"maximumBytes,omitempty"`
	Coordinator  *coordinator `json:"coordinator,omitempty"`
	ExitCode     *int         `json:"exitCode,omitempty"`
	Output       *string      `json:"output,omitempty"`
	Diagnostics  []string     `json:"diagnostics,omitempty"`
	Failure      string       `json:"failure,omitempty"`
}

type coordinator struct {
	ServiceGeneration string `json:"serviceGeneration"`
	ProcessID         int64  `json:"processId"`
}

// Completed is the coordinator's presented result. The frontend delivers Output and exits with ExitCode.
type Completed struct {
	Coordinator coordinator
	ExitCode    int
	Output      string
	Diagnostics []string
}

// TransportFailure is the closed set of frontend-observed transport outcomes.
type TransportFailure int

const (
	// Unreachable means no request was sent, so the invocation had no effect.
	Unreachable TransportFailure = iota
	// Rejected means the coordinator refused the envelope before running the public shell.
	Rejected
	// Unobserved means the request may have run; its outcome, including any mutation, is unknown.
	Unobserved
	// InputUnreadable means stdin failed while the coordinator awaited it; closing the connection cancels the request.
	InputUnreadable
)

// TransportError carries the failure and, for Rejected, the coordinator's reason.
type TransportError struct {
	Failure TransportFailure
	Reason  string
}

func (e *TransportError) Error() string {
	switch e.Failure {
	case Rejected:
		if e.Reason == "INPUT_EXPIRED" {
			return "request input did not arrive in time (INPUT_EXPIRED). Use --request FILE, or write and close stdin."
		}
		return fmt.Sprintf("the coordinator rejected the invocation (%s).", e.Reason)
	case Unobserved:
		return "the coordinator connection ended before a result; the request outcome is unknown."
	case InputUnreadable:
		return "request input read failed. Use --request FILE for a readable JSON file or --request - for JSON on stdin."
	default:
		return "the coordinator is unreachable."
	}
}

// admitSocket proves the warm-path trust basis: the socket lives in a directory owned by this user and closed to others.
func admitSocket(path string) error {
	directory, err := os.Lstat(filepath.Dir(path))
	if err != nil || !directory.IsDir() || directory.Mode().Perm()&0o077 != 0 {
		return &TransportError{Failure: Unreachable}
	}
	owner, ok := directory.Sys().(*syscall.Stat_t)
	if !ok || int(owner.Uid) != os.Getuid() {
		return &TransportError{Failure: Unreachable}
	}
	socket, err := os.Lstat(path)
	if err != nil || socket.Mode().Type() != os.ModeSocket {
		return &TransportError{Failure: Unreachable}
	}
	return nil
}

// transportPath is the path to dial. A socket too long to bind is reached through the coordinator's alias, which
// must link to exactly the socket's directory; trust is still proven on the physical directory.
func transportPath(physical string, alias socketAlias) (string, error) {
	if len(physical) <= alias.MaximumPathBytes {
		return physical, nil
	}
	directory := filepath.Dir(physical)
	digest := sha256.Sum256([]byte(directory))
	link := filepath.Join(alias.Directory, alias.Prefix+hex.EncodeToString(digest[:])[:alias.DigestCharacters])
	if target, err := os.Readlink(link); err != nil || target != directory {
		return "", &TransportError{Failure: Unreachable}
	}
	return filepath.Join(link, filepath.Base(physical)), nil
}

// dial opens one invocation connection within the handshake bound. Any failure here, expiry included, precedes the
// request, so the coordinator is unreachable and the invocation had no effect.
func dial(ctx context.Context, physical string, alias socketAlias, handshake time.Duration) (*websocket.Conn, error) {
	if err := admitSocket(physical); err != nil {
		return nil, err
	}
	socket, err := transportPath(physical, alias)
	if err != nil {
		return nil, err
	}
	// The handshake context bounds only the opening handshake; the returned connection outlives it.
	ctx, cancel := context.WithTimeout(ctx, handshake)
	defer cancel()
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var dialer net.Dialer
			return dialer.DialContext(ctx, "unix", socket)
		},
	}
	conn, _, err := websocket.Dial(ctx, invocationRoute, &websocket.DialOptions{
		HTTPClient: &http.Client{Transport: transport},
	})
	if err != nil {
		return nil, &TransportError{Failure: Unreachable}
	}
	// The coordinator bounds its documents; this frontend imposes no smaller limit than the coordinator's transport.
	conn.SetReadLimit(math.MaxInt32)
	return conn, nil
}

// exchange sends one request and answers at most one input request. stdin is read only when the coordinator asks.
func exchange(ctx context.Context, conn *websocket.Conn, request invocationRequest, stdin io.Reader) (*Completed, error) {
	encoded, err := json.Marshal(request)
	if err != nil {
		return nil, &TransportError{Failure: Unreachable}
	}
	if err := conn.Write(ctx, websocket.MessageText, encoded); err != nil {
		return nil, &TransportError{Failure: Unobserved}
	}
	answered := false
	for {
		kind, message, err := conn.Read(ctx)
		if err != nil || kind != websocket.MessageText {
			return nil, &TransportError{Failure: Unobserved}
		}
		var received reply
		if json.Unmarshal(message, &received) != nil {
			return nil, &TransportError{Failure: Unobserved}
		}
		switch received.Type {
		case "input_required":
			if answered || received.MaximumBytes == nil || *received.MaximumBytes < 0 {
				return nil, &TransportError{Failure: Unobserved}
			}
			answered = true
			input, err := readInput(ctx, stdin, int64(*received.MaximumBytes))
			if err != nil {
				return nil, err
			}
			if err := conn.Write(ctx, websocket.MessageBinary, input); err != nil {
				return nil, &TransportError{Failure: Unobserved}
			}
		case "completed":
			if received.Coordinator == nil || received.ExitCode == nil || received.Output == nil {
				return nil, &TransportError{Failure: Unobserved}
			}
			return &Completed{*received.Coordinator, *received.ExitCode, *received.Output, received.Diagnostics}, nil
		case "rejected":
			return nil, &TransportError{Failure: Rejected, Reason: received.Failure}
		default:
			return nil, &TransportError{Failure: Unobserved}
		}
	}
}

// readInput reads at most limit bytes of stdin. A terminal or a stalled pipe can block indefinitely, so cancellation
// returns without the read; closing the connection then cancels the coordinator's request.
func readInput(ctx context.Context, stdin io.Reader, limit int64) ([]byte, error) {
	type result struct {
		input []byte
		err   error
	}
	read := make(chan result, 1)
	go func() {
		input, err := io.ReadAll(io.LimitReader(stdin, limit))
		read <- result{input, err}
	}()
	select {
	case <-ctx.Done():
		return nil, &TransportError{Failure: Unobserved}
	case received := <-read:
		if received.err != nil {
			return nil, &TransportError{Failure: InputUnreadable}
		}
		return received.input, nil
	}
}

func isUnreachable(err error) bool {
	var transport *TransportError
	return errors.As(err, &transport) && transport.Failure == Unreachable
}
