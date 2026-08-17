package dns

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

const (
	dialTimeout  = 5 * time.Second
	readTimeout  = 5 * time.Second
	maxDNSPacket = 4096
)

type Forwarder struct {
	listenAddr   string
	upstreams    []string
	nextUpstream uint32
	conn         *net.UDPConn
	wg           sync.WaitGroup
	log          *logging.Logger
}

func New(listenAddr, upstreamAddr string, log *logging.Logger) *Forwarder {
	if upstreamAddr == "" {
		upstreamAddr = "https://cloudflare-dns.com/dns-query,https://dns.google/dns-query"
	}
	var upstreams []string
	for _, raw := range strings.FieldsFunc(upstreamAddr, func(r rune) bool { return r == ',' || r == ';' || r == ' ' }) {
		if raw == "" {
			continue
		}
		if !strings.Contains(raw, "://") {
			raw += ":853"
		}
		upstreams = append(upstreams, raw)
	}
	if len(upstreams) == 0 {
		upstreams = []string{"https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query"}
	}
	return &Forwarder{listenAddr: listenAddr, upstreams: upstreams, log: log}
}

func (f *Forwarder) Start(ctx context.Context) error {
	addr, err := net.ResolveUDPAddr("udp", f.listenAddr)
	if err != nil {
		return fmt.Errorf("resolve listen address %s: %w", f.listenAddr, err)
	}

	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("listen udp %s: %w", f.listenAddr, err)
	}
	f.conn = conn

	f.log.Info("DNS forwarder started",
		zap.String("listen", f.listenAddr),
		zap.Strings("upstreams", f.upstreams),
	)

	f.wg.Add(1)
	go func() {
		defer f.wg.Done()
		f.serve(ctx)
	}()

	return nil
}

func (f *Forwarder) serve(ctx context.Context) {
	buf := make([]byte, maxDNSPacket)

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		_ = f.conn.SetReadDeadline(time.Now().Add(readTimeout))
		n, clientAddr, err := f.conn.ReadFromUDP(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			select {
			case <-ctx.Done():
				return
			default:
			}
			f.log.Error("DNS read error", zap.Error(err))
			continue
		}

		query := make([]byte, n)
		copy(query, buf[:n])

		clientAddrCopy := &net.UDPAddr{
			IP:   make([]byte, len(clientAddr.IP)),
			Port: clientAddr.Port,
			Zone: clientAddr.Zone,
		}
		copy(clientAddrCopy.IP, clientAddr.IP)

		go f.handleQuery(query, clientAddrCopy)
	}
}

func (f *Forwarder) handleQuery(query []byte, clientAddr *net.UDPAddr) {
	if len(query) > 65535 {
		f.log.Warn("DNS query too large", zap.Int("bytes", len(query)))
		return
	}
	start := int(atomic.AddUint32(&f.nextUpstream, 1)-1) % len(f.upstreams)
	var lastErr error
	for i := 0; i < len(f.upstreams); i++ {
		upstream := f.upstreams[(start+i)%len(f.upstreams)]
		var response []byte
		var err error
		if strings.HasPrefix(upstream, "https://") {
			response, err = f.queryDoH(query, upstream)
		} else {
			response, err = f.queryDoT(query, upstream)
		}
		if err != nil {
			lastErr = err
			continue
		}
		if _, err := f.conn.WriteToUDP(response, clientAddr); err != nil {
			f.log.Error("write client", zap.Error(err))
		}
		return
	}
	f.log.Warn("all encrypted DNS upstreams failed", zap.Error(lastErr))
}

func (f *Forwarder) queryDoT(query []byte, upstream string) ([]byte, error) {
	serverName, _, err := net.SplitHostPort(upstream)
	if err != nil {
		serverName = upstream
	}
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: dialTimeout}, "tcp", upstream, &tls.Config{
		MinVersion: tls.VersionTLS12,
		ServerName: serverName,
		NextProtos: []string{"dot"},
	})
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", upstream, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	var length [2]byte
	binary.BigEndian.PutUint16(length[:], uint16(len(query)))
	if _, err := conn.Write(length[:]); err != nil {
		return nil, fmt.Errorf("write DNS-over-TLS length: %w", err)
	}
	if _, err := conn.Write(query); err != nil {
		return nil, fmt.Errorf("write DNS-over-TLS query: %w", err)
	}
	if _, err := io.ReadFull(conn, length[:]); err != nil {
		return nil, fmt.Errorf("read DNS-over-TLS length: %w", err)
	}
	responseLength := int(binary.BigEndian.Uint16(length[:]))
	if responseLength == 0 || responseLength > maxDNSPacket {
		return nil, fmt.Errorf("invalid DNS-over-TLS response size %d", responseLength)
	}
	response := make([]byte, responseLength)
	if _, err := io.ReadFull(conn, response); err != nil {
		return nil, fmt.Errorf("read DNS-over-TLS response: %w", err)
	}
	return response, nil
}

func (f *Forwarder) queryDoH(query []byte, upstream string) ([]byte, error) {
	req, err := http.NewRequest(http.MethodPost, upstream, bytes.NewReader(query))
	if err != nil {
		return nil, fmt.Errorf("create DNS-over-HTTPS request: %w", err)
	}
	req.Header.Set("Accept", "application/dns-message")
	req.Header.Set("Content-Type", "application/dns-message")
	resp, err := (&http.Client{Timeout: dialTimeout}).Do(req)
	if err != nil {
		return nil, fmt.Errorf("dial DNS-over-HTTPS upstream %s: %w", upstream, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DNS-over-HTTPS upstream %s returned %d", upstream, resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxDNSPacket+1))
	if err != nil {
		return nil, fmt.Errorf("read DNS-over-HTTPS response: %w", err)
	}
	if len(body) == 0 || len(body) > maxDNSPacket {
		return nil, fmt.Errorf("invalid DNS-over-HTTPS response size %d", len(body))
	}
	return body, nil
}

func (f *Forwarder) Shutdown() error {
	if f.conn != nil {
		if err := f.conn.Close(); err != nil {
			return err
		}
	}
	f.wg.Wait()
	f.log.Info("DNS forwarder stopped")
	return nil
}
