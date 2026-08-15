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
	upstreamAddr string
	conn         *net.UDPConn
	wg           sync.WaitGroup
	log          *logging.Logger
}

func New(listenAddr, upstreamAddr string, log *logging.Logger) *Forwarder {
	if upstreamAddr == "" {
		upstreamAddr = "https://cloudflare-dns.com/dns-query"
	}
	if !strings.Contains(upstreamAddr, ":") {
		upstreamAddr += ":853"
	}
	return &Forwarder{
		listenAddr:   listenAddr,
		upstreamAddr: upstreamAddr,
		log:          log,
	}
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
		zap.String("upstream", f.upstreamAddr),
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
	if strings.HasPrefix(f.upstreamAddr, "https://") {
		f.handleDoH(query, clientAddr)
		return
	}
	serverName, _, err := net.SplitHostPort(f.upstreamAddr)
	if err != nil {
		serverName = f.upstreamAddr
	}
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: dialTimeout}, "tcp", f.upstreamAddr, &tls.Config{
		MinVersion: tls.VersionTLS12,
		ServerName: serverName,
		NextProtos: []string{"dot"},
	})
	if err != nil {
		f.log.Error("dial DNS-over-TLS upstream", zap.Error(err))
		return
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(dialTimeout))

	if len(query) > 65535 {
		f.log.Warn("DNS query too large", zap.Int("bytes", len(query)))
		return
	}
	var length [2]byte
	binary.BigEndian.PutUint16(length[:], uint16(len(query)))
	if _, err := conn.Write(length[:]); err != nil {
		f.log.Error("write DNS-over-TLS length", zap.Error(err))
		return
	}
	if _, err := conn.Write(query); err != nil {
		f.log.Error("write upstream", zap.Error(err))
		return
	}

	if _, err := io.ReadFull(conn, length[:]); err != nil {
		f.log.Error("read DNS-over-TLS length", zap.Error(err))
		return
	}
	responseLength := int(binary.BigEndian.Uint16(length[:]))
	if responseLength == 0 || responseLength > maxDNSPacket {
		f.log.Warn("invalid DNS-over-TLS response length", zap.Int("bytes", responseLength))
		return
	}
	resp := make([]byte, responseLength)
	if _, err := io.ReadFull(conn, resp); err != nil {
		f.log.Error("read DNS-over-TLS response", zap.Error(err))
		return
	}

	if _, err := f.conn.WriteToUDP(resp, clientAddr); err != nil {
		f.log.Error("write client", zap.Error(err))
	}
}

func (f *Forwarder) handleDoH(query []byte, clientAddr *net.UDPAddr) {
	req, err := http.NewRequest(http.MethodPost, f.upstreamAddr, bytes.NewReader(query))
	if err != nil {
		f.log.Error("create DNS-over-HTTPS request", zap.Error(err))
		return
	}
	req.Header.Set("Accept", "application/dns-message")
	req.Header.Set("Content-Type", "application/dns-message")
	client := &http.Client{Timeout: dialTimeout}
	resp, err := client.Do(req)
	if err != nil {
		f.log.Error("dial DNS-over-HTTPS upstream", zap.Error(err))
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		f.log.Warn("DNS-over-HTTPS upstream returned an error", zap.Int("status", resp.StatusCode))
		return
	}
	respBody, err := io.ReadAll(io.LimitReader(resp.Body, maxDNSPacket+1))
	if err != nil {
		f.log.Error("read DNS-over-HTTPS response", zap.Error(err))
		return
	}
	if len(respBody) == 0 || len(respBody) > maxDNSPacket {
		f.log.Warn("invalid DNS-over-HTTPS response size", zap.Int("bytes", len(respBody)))
		return
	}
	if _, err := f.conn.WriteToUDP(respBody, clientAddr); err != nil {
		f.log.Error("write client", zap.Error(err))
	}
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
