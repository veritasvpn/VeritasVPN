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
	defaultTTL   = 30 * time.Second
	maxCacheTTL  = 5 * time.Minute
	ratePerSec   = 20
	rateBurst    = 50
)

type cacheEntry struct {
	response []byte
	expires  time.Time
}

type clientBucket struct {
	tokens float64
	last   time.Time
}

type Forwarder struct {
	listenAddr   string
	upstreams    []string
	nextUpstream uint32
	udpConn      *net.UDPConn
	tcpLn        net.Listener
	wg           sync.WaitGroup
	log          *logging.Logger

	cacheMu sync.Mutex
	cache   map[string]cacheEntry

	rateMu    sync.Mutex
	buckets   map[string]*clientBucket
	blocklist *Blocklist
	observer  Observer

	blockedMu       sync.Mutex
	blockedByClient map[string]uint64 // tunnel client IP → blocked query count (no domains)

	policyMu     sync.RWMutex
	presetByIP   map[string]string // tunnel client IP → shield preset
	defaultPreset string
	allowlist    map[string]struct{}
}

type Config struct {
	ListenAddr         string
	UpstreamAddr       string
	BlocklistURLs      string // legacy fallback
	ShieldCategories   []string
	ShieldURLs         map[string][]string
	BlocklistRefresh   time.Duration
	BlocklistStateFile string
	DefaultPreset      string
	Allowlist          map[string]struct{}
}

func New(cfg Config, observer Observer, log *logging.Logger) *Forwarder {
	if cfg.UpstreamAddr == "" {
		cfg.UpstreamAddr = "https://cloudflare-dns.com/dns-query,https://dns.google/dns-query"
	}
	if observer == nil {
		observer = noopObserver{}
	}
	var upstreams []string
	for _, raw := range strings.FieldsFunc(cfg.UpstreamAddr, func(r rune) bool { return r == ',' || r == ';' || r == ' ' }) {
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

	var blocklist *Blocklist
	if len(cfg.ShieldCategories) > 0 && len(cfg.ShieldURLs) > 0 {
		blocklist = NewShieldBlocklist(cfg.ShieldCategories, cfg.ShieldURLs, cfg.BlocklistStateFile, cfg.BlocklistRefresh, observer, log)
	} else {
		blocklist = NewBlocklist(cfg.BlocklistURLs, cfg.BlocklistStateFile, cfg.BlocklistRefresh, observer, log)
	}

	return &Forwarder{
		listenAddr:      cfg.ListenAddr,
		upstreams:       upstreams,
		log:             log,
		cache:           make(map[string]cacheEntry),
		buckets:         make(map[string]*clientBucket),
		blocklist:       blocklist,
		observer:        observer,
		blockedByClient: make(map[string]uint64),
		presetByIP:      make(map[string]string),
		defaultPreset:   NormalizePreset(cfg.DefaultPreset),
		allowlist:       cfg.Allowlist,
	}
}

// BlockedCounts returns a copy of per-client blocked query counts (tunnel IP → count).
// Counts only — no domain names are retained.
func (f *Forwarder) BlockedCounts() map[string]uint64 {
	f.blockedMu.Lock()
	defer f.blockedMu.Unlock()
	out := make(map[string]uint64, len(f.blockedByClient))
	for ip, n := range f.blockedByClient {
		out[ip] = n
	}
	return out
}

// ClearBlockedForCIDRs drops in-memory blocked counts for tunnel addresses
// (bare IPs or CIDRs such as 10.0.0.5/32) so heartbeats stop advertising
// stale counters after a peer is removed.
func (f *Forwarder) ClearBlockedForCIDRs(cidrs []string) {
	if f == nil || len(cidrs) == 0 {
		return
	}
	f.blockedMu.Lock()
	for _, cidr := range cidrs {
		ip := stripHost(cidr)
		if ip == "" {
			continue
		}
		delete(f.blockedByClient, ip)
	}
	f.blockedMu.Unlock()
	f.ClearPeerPresets(cidrs)
}

// SetPeerPreset associates a Veritas Shield preset with tunnel address(es).
func (f *Forwarder) SetPeerPreset(cidrs []string, preset string) {
	if f == nil {
		return
	}
	preset = NormalizePreset(preset)
	f.policyMu.Lock()
	defer f.policyMu.Unlock()
	if f.presetByIP == nil {
		f.presetByIP = make(map[string]string)
	}
	for _, cidr := range cidrs {
		ip := stripHost(cidr)
		if ip == "" {
			continue
		}
		f.presetByIP[ip] = preset
	}
}

// ClearPeerPresets removes preset mappings for the given tunnel addresses.
func (f *Forwarder) ClearPeerPresets(cidrs []string) {
	if f == nil || len(cidrs) == 0 {
		return
	}
	f.policyMu.Lock()
	defer f.policyMu.Unlock()
	for _, cidr := range cidrs {
		ip := stripHost(cidr)
		if ip == "" {
			continue
		}
		delete(f.presetByIP, ip)
	}
}

func (f *Forwarder) presetForClient(clientIP string) string {
	ip := stripHost(clientIP)
	f.policyMu.RLock()
	defer f.policyMu.RUnlock()
	if p, ok := f.presetByIP[ip]; ok && p != "" {
		return p
	}
	if f.defaultPreset != "" {
		return f.defaultPreset
	}
	return DefaultPreset
}

func stripHost(addr string) string {
	ip := strings.TrimSpace(addr)
	if i := strings.IndexByte(ip, '/'); i >= 0 {
		ip = ip[:i]
	}
	if host, _, err := net.SplitHostPort(ip); err == nil {
		ip = host
	}
	return strings.Trim(ip, "[]")
}

func (f *Forwarder) Start(ctx context.Context) error {
	udpAddr, err := net.ResolveUDPAddr("udp", f.listenAddr)
	if err != nil {
		return fmt.Errorf("resolve listen address %s: %w", f.listenAddr, err)
	}
	udpConn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		return fmt.Errorf("listen udp %s: %w", f.listenAddr, err)
	}
	f.udpConn = udpConn

	tcpLn, err := net.Listen("tcp", f.listenAddr)
	if err != nil {
		_ = udpConn.Close()
		return fmt.Errorf("listen tcp %s: %w", f.listenAddr, err)
	}
	f.tcpLn = tcpLn

	f.log.Info("DNS forwarder started",
		zap.String("listen", f.listenAddr),
		zap.Strings("upstreams", f.upstreams),
	)

	f.wg.Add(3)
	go func() {
		defer f.wg.Done()
		f.serveUDP(ctx)
	}()
	go func() {
		defer f.wg.Done()
		f.serveTCP(ctx)
	}()
	go func() {
		defer f.wg.Done()
		f.janitor(ctx)
	}()
	f.blocklist.Start(ctx)
	return nil
}

func (f *Forwarder) janitor(ctx context.Context) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			now := time.Now()
			f.cacheMu.Lock()
			for k, v := range f.cache {
				if now.After(v.expires) {
					delete(f.cache, k)
				}
			}
			f.cacheMu.Unlock()
			f.rateMu.Lock()
			for k, b := range f.buckets {
				if now.Sub(b.last) > 2*time.Minute {
					delete(f.buckets, k)
				}
			}
			f.rateMu.Unlock()
		}
	}
}

func (f *Forwarder) serveUDP(ctx context.Context) {
	buf := make([]byte, maxDNSPacket)
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		_ = f.udpConn.SetReadDeadline(time.Now().Add(readTimeout))
		n, clientAddr, err := f.udpConn.ReadFromUDP(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			select {
			case <-ctx.Done():
				return
			default:
			}
			f.log.Error("DNS UDP read error", zap.Error(err))
			continue
		}
		query := make([]byte, n)
		copy(query, buf[:n])
		addrCopy := copyUDPAddr(clientAddr)
		go f.handleQuery(query, func(resp []byte) error {
			_, err := f.udpConn.WriteToUDP(resp, addrCopy)
			return err
		}, addrCopy.IP.String())
	}
}

func (f *Forwarder) serveTCP(ctx context.Context) {
	go func() {
		<-ctx.Done()
		_ = f.tcpLn.Close()
	}()
	for {
		conn, err := f.tcpLn.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
			}
			f.log.Error("DNS TCP accept error", zap.Error(err))
			continue
		}
		go f.handleTCP(conn)
	}
}

func (f *Forwarder) handleTCP(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	var length [2]byte
	if _, err := io.ReadFull(conn, length[:]); err != nil {
		return
	}
	n := int(binary.BigEndian.Uint16(length[:]))
	if n == 0 || n > maxDNSPacket {
		return
	}
	query := make([]byte, n)
	if _, err := io.ReadFull(conn, query); err != nil {
		return
	}
	clientIP := ""
	if host, _, err := net.SplitHostPort(conn.RemoteAddr().String()); err == nil {
		clientIP = host
	}
	_ = f.handleQuery(query, func(resp []byte) error {
		var outLen [2]byte
		binary.BigEndian.PutUint16(outLen[:], uint16(len(resp)))
		if _, err := conn.Write(outLen[:]); err != nil {
			return err
		}
		_, err := conn.Write(resp)
		return err
	}, clientIP)
}

func copyUDPAddr(a *net.UDPAddr) *net.UDPAddr {
	out := &net.UDPAddr{Port: a.Port, Zone: a.Zone, IP: make(net.IP, len(a.IP))}
	copy(out.IP, a.IP)
	return out
}

func (f *Forwarder) allow(clientIP string) bool {
	if clientIP == "" {
		return true
	}
	now := time.Now()
	f.rateMu.Lock()
	defer f.rateMu.Unlock()
	b, ok := f.buckets[clientIP]
	if !ok {
		f.buckets[clientIP] = &clientBucket{tokens: rateBurst - 1, last: now}
		return true
	}
	elapsed := now.Sub(b.last).Seconds()
	b.tokens += elapsed * ratePerSec
	if b.tokens > rateBurst {
		b.tokens = rateBurst
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func (f *Forwarder) handleQuery(query []byte, write func([]byte) error, clientIP string) error {
	if len(query) < 12 || len(query) > 65535 {
		return nil
	}
	if !f.allow(clientIP) {
		f.log.Warn("DNS rate limit")
		return nil
	}
	if name, questionEnd, ok := queryName(query); ok {
		if AllowlistMatch(f.allowlist, name) {
			// Escape hatch — never block allowlisted names (Aggressive FP relief).
		} else if cat, blocked := f.blocklist.BlockedCategory(name); blocked && CategoryEnabled(f.presetForClient(clientIP), cat) {
			f.observer.DNSQuery(true)
			f.observer.DNSBlockedCategory(cat)
			if clientIP != "" {
				f.blockedMu.Lock()
				f.blockedByClient[stripHost(clientIP)]++
				f.blockedMu.Unlock()
			}
			if err := write(blockedResponse(query, questionEnd)); err != nil {
				f.log.Error("write blocked DNS response", zap.Error(err))
				return err
			}
			return nil
		}
	}
	f.observer.DNSQuery(false)

	cacheKey := string(query[2:]) // exclude TXID
	if resp, ok := f.cacheGet(cacheKey, query); ok {
		if err := write(resp); err != nil {
			f.log.Error("write client", zap.Error(err))
			return err
		}
		return nil
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
		response = filterRebinding(response)
		f.cachePut(cacheKey, response)
		if err := write(response); err != nil {
			f.log.Error("write client", zap.Error(err))
			return err
		}
		return nil
	}
	f.log.Warn("all encrypted DNS upstreams failed", zap.Error(lastErr))
	f.observer.DNSUpstreamFailure()
	return lastErr
}

func queryName(msg []byte) (string, int, bool) {
	if len(msg) < 17 || binary.BigEndian.Uint16(msg[4:6]) != 1 {
		return "", 0, false
	}
	off := 12
	labels := make([]string, 0, 4)
	for {
		if off >= len(msg) {
			return "", 0, false
		}
		length := int(msg[off])
		if length == 0 {
			off++
			break
		}
		if length > 63 || off+1+length > len(msg) {
			return "", 0, false
		}
		labels = append(labels, string(msg[off+1:off+1+length]))
		off += length + 1
	}
	if off+4 > len(msg) || len(labels) == 0 {
		return "", 0, false
	}
	return strings.ToLower(strings.Join(labels, ".")), off + 4, true
}

func blockedResponse(query []byte, questionEnd int) []byte {
	response := append([]byte(nil), query[:questionEnd]...)
	flags := binary.BigEndian.Uint16(query[2:4])
	flags |= 0x8080                    // response + recursion available
	flags = (flags &^ 0x000F) | 0x0003 // NXDOMAIN
	binary.BigEndian.PutUint16(response[2:4], flags)
	binary.BigEndian.PutUint16(response[6:8], 0)
	binary.BigEndian.PutUint16(response[8:10], 0)
	binary.BigEndian.PutUint16(response[10:12], 0)
	return response
}

func (f *Forwarder) cacheGet(key string, query []byte) ([]byte, bool) {
	f.cacheMu.Lock()
	defer f.cacheMu.Unlock()
	ent, ok := f.cache[key]
	if !ok || time.Now().After(ent.expires) {
		if ok {
			delete(f.cache, key)
		}
		return nil, false
	}
	out := make([]byte, len(ent.response))
	copy(out, ent.response)
	if len(out) >= 2 && len(query) >= 2 {
		out[0], out[1] = query[0], query[1]
	}
	return out, true
}

func (f *Forwarder) cachePut(key string, response []byte) {
	if len(response) < 12 {
		return
	}
	ttl := defaultTTL
	if parsed := minAnswerTTL(response); parsed > 0 && parsed < maxCacheTTL {
		ttl = parsed
	}
	stored := make([]byte, len(response))
	copy(stored, response)
	f.cacheMu.Lock()
	f.cache[key] = cacheEntry{response: stored, expires: time.Now().Add(ttl)}
	f.cacheMu.Unlock()
}

func minAnswerTTL(msg []byte) time.Duration {
	if len(msg) < 12 {
		return 0
	}
	qd := binary.BigEndian.Uint16(msg[4:6])
	an := binary.BigEndian.Uint16(msg[6:8])
	off := 12
	for i := 0; i < int(qd); i++ {
		n, ok := skipName(msg, off)
		if !ok {
			return 0
		}
		off = n + 4
		if off > len(msg) {
			return 0
		}
	}
	var minTTL uint32
	for i := 0; i < int(an); i++ {
		n, ok := skipName(msg, off)
		if !ok || n+10 > len(msg) {
			return 0
		}
		ttl := binary.BigEndian.Uint32(msg[n+4 : n+8])
		rdlen := int(binary.BigEndian.Uint16(msg[n+8 : n+10]))
		off = n + 10 + rdlen
		if off > len(msg) {
			return 0
		}
		if i == 0 || ttl < minTTL {
			minTTL = ttl
		}
	}
	if minTTL == 0 {
		return 0
	}
	return time.Duration(minTTL) * time.Second
}

func skipName(msg []byte, off int) (int, bool) {
	for off < len(msg) {
		l := int(msg[off])
		if l == 0 {
			return off + 1, true
		}
		if l&0xC0 == 0xC0 {
			if off+2 > len(msg) {
				return 0, false
			}
			return off + 2, true
		}
		off += 1 + l
	}
	return 0, false
}

// filterRebinding removes A/AAAA answers that point at private or link-local
// addresses so VPN clients cannot be steered at LAN/metadata targets via DNS.
func filterRebinding(msg []byte) []byte {
	if len(msg) < 12 {
		return msg
	}
	qd := int(binary.BigEndian.Uint16(msg[4:6]))
	an := int(binary.BigEndian.Uint16(msg[6:8]))
	ns := int(binary.BigEndian.Uint16(msg[8:10]))
	ar := int(binary.BigEndian.Uint16(msg[10:12]))
	off := 12
	for i := 0; i < qd; i++ {
		n, ok := skipName(msg, off)
		if !ok {
			return msg
		}
		off = n + 4
		if off > len(msg) {
			return msg
		}
	}
	out := make([]byte, off)
	copy(out, msg[:off])
	keptAN := 0
	sections := []int{an, ns, ar}
	keptCounts := make([]int, 3)
	for s, count := range sections {
		for i := 0; i < count; i++ {
			nameStart := off
			n, ok := skipName(msg, off)
			if !ok || n+10 > len(msg) {
				return msg
			}
			typ := binary.BigEndian.Uint16(msg[n : n+2])
			rdlen := int(binary.BigEndian.Uint16(msg[n+8 : n+10]))
			rdataStart := n + 10
			rdataEnd := rdataStart + rdlen
			if rdataEnd > len(msg) {
				return msg
			}
			off = rdataEnd
			drop := false
			if s == 0 && (typ == 1 || typ == 28) {
				if isDisallowedAnswer(msg[rdataStart:rdataEnd]) {
					drop = true
				}
			}
			if drop {
				continue
			}
			out = append(out, msg[nameStart:rdataEnd]...)
			keptCounts[s]++
			if s == 0 {
				keptAN++
			}
		}
	}
	_ = keptAN
	binary.BigEndian.PutUint16(out[6:8], uint16(keptCounts[0]))
	binary.BigEndian.PutUint16(out[8:10], uint16(keptCounts[1]))
	binary.BigEndian.PutUint16(out[10:12], uint16(keptCounts[2]))
	return out
}

func isDisallowedAnswer(rdata []byte) bool {
	switch len(rdata) {
	case 4:
		ip := net.IP(rdata)
		return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() || isCGNAT(ip)
	case 16:
		ip := net.IP(rdata)
		return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified()
	default:
		return false
	}
}

func isCGNAT(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	return ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127
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
	var first error
	if f.udpConn != nil {
		if err := f.udpConn.Close(); err != nil && first == nil {
			first = err
		}
	}
	if f.tcpLn != nil {
		if err := f.tcpLn.Close(); err != nil && first == nil {
			first = err
		}
	}
	f.wg.Wait()
	f.log.Info("DNS forwarder stopped")
	return first
}
