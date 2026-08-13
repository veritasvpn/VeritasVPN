package dns

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"

	"go.uber.org/zap"
	"github.com/veritasvpn/lib/logging"
)

const (
	dialTimeout  = 5 * time.Second
	readTimeout  = 5 * time.Second
	maxDNSPacket = 512
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
		upstreamAddr = "1.1.1.1:53"
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
	upstreamAddr, err := net.ResolveUDPAddr("udp", f.upstreamAddr)
	if err != nil {
		f.log.Error("resolve upstream", zap.Error(err))
		return
	}

	conn, err := net.DialUDP("udp", nil, upstreamAddr)
	if err != nil {
		f.log.Error("dial upstream", zap.Error(err))
		return
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(dialTimeout))

	if _, err := conn.Write(query); err != nil {
		f.log.Error("write upstream", zap.Error(err))
		return
	}

	resp := make([]byte, maxDNSPacket)
	n, err := conn.Read(resp)
	if err != nil {
		f.log.Error("read upstream", zap.Error(err))
		return
	}

	if _, err := f.conn.WriteToUDP(resp[:n], clientAddr); err != nil {
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
