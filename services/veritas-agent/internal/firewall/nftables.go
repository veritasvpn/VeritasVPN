package firewall

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const cmdTimeout = 10 * time.Second

const defaultBandwidthMbps = 150 // matches PEER_BANDWIDTH_LIMIT_MBPS default

type Manager struct {
	tableName string
}

func New() *Manager {
	return &Manager{
		tableName: "veritas",
	}
}

func (m *Manager) run(args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), cmdTimeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, "nft", args...)
	output, err := cmd.CombinedOutput()
	if err != nil {
		if ctx.Err() != nil {
			return fmt.Errorf("nft %s: timed out after %v", strings.Join(args, " "), cmdTimeout)
		}
		return fmt.Errorf("nft %s: %s: %w", strings.Join(args, " "), strings.TrimSpace(string(output)), err)
	}
	return nil
}

func (m *Manager) runScript(script string) error {
	ctx, cancel := context.WithTimeout(context.Background(), cmdTimeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, "nft", "-f", "-")
	cmd.Stdin = strings.NewReader(script)
	output, err := cmd.CombinedOutput()
	if err != nil {
		if ctx.Err() != nil {
			return fmt.Errorf("nft ruleset: timed out after %v", cmdTimeout)
		}
		return fmt.Errorf("nft ruleset: %s: %w", strings.TrimSpace(string(output)), err)
	}
	return nil
}

// RemoveLegacyPortForwardTable removes the retired DNAT table left by older
// agents. It is safe to call on every startup and ensures old mappings cannot
// survive the product feature being removed.
func (m *Manager) RemoveLegacyPortForwardTable() error {
	if err := m.run("list", "table", "inet", "veritas_pf"); err != nil {
		return nil
	}
	return m.run("delete", "table", "inet", "veritas_pf")
}

func detectEgressInterface() (string, error) {
	egress := os.Getenv("EGRESS_IFACE")
	if egress == "" {
		out, err := exec.Command("sh", "-c", "ip route show default | awk '{print $5; exit}'").Output()
		if err != nil {
			return "", fmt.Errorf("detect egress interface: %w", err)
		}
		egress = strings.TrimSpace(string(out))
	}
	if !validInterfaceName(egress) {
		return "", fmt.Errorf("invalid egress interface %q", egress)
	}
	return egress, nil
}

// Reconcile atomically rebuilds the agent-owned nftables table.
// Forward is fail-closed: VPN clients may only reach the public internet via
// the egress interface. Client-to-client, LAN, link-local, and Kubernetes
// ranges are dropped. Bandwidth shaping is owned by the host tc service.
// Cleanup intentionally does not remove this table so a restart never opens a
// window without NAT/isolation.
func (m *Manager) Reconcile(wgIface string, wgPort, mbps int) error {
	if !validInterfaceName(wgIface) {
		return fmt.Errorf("invalid WireGuard interface %q", wgIface)
	}
	if wgPort <= 0 || wgPort > 65535 {
		return fmt.Errorf("invalid WireGuard port %d", wgPort)
	}
	_ = mbps // host tc owns per-peer caps; kept for API compatibility

	egress, err := detectEgressInterface()
	if err != nil {
		return err
	}
	podCIDR, serviceCIDR, err := detectKubernetesCIDRs()
	if err != nil {
		return err
	}
	wgSubnet := os.Getenv("WG_SUBNET")
	if wgSubnet == "" {
		wgSubnet = "10.0.0.0/24"
	}
	if _, _, err := net.ParseCIDR(wgSubnet); err != nil {
		return fmt.Errorf("invalid WireGuard subnet %q: %w", wgSubnet, err)
	}
	dnsIP := os.Getenv("DNS_GATEWAY_IP")
	if dnsIP == "" {
		dnsIP = strings.Replace(wgSubnet, ".0/24", ".1", 1)
		if dnsIP == wgSubnet {
			dnsIP = "10.0.0.1"
		}
	}
	if ip := net.ParseIP(dnsIP); ip == nil || ip.To4() == nil {
		return fmt.Errorf("invalid DNS gateway IP %q", dnsIP)
	}

	dohV4, dohV6 := doHBlockLists()
	if err := m.runScript(buildRuleset(m.tableName, wgIface, egress, podCIDR, serviceCIDR, wgSubnet, dnsIP, wgPort, dohV4, dohV6)); err != nil {
		return err
	}
	return nil
}

func detectKubernetesCIDRs() (string, string, error) {
	podCIDR := os.Getenv("K8S_POD_CIDR")
	if podCIDR == "" {
		out, err := exec.Command("sh", "-c", "ip route show dev cni0 | awk '{print $1; exit}'").Output()
		if err != nil {
			return "", "", fmt.Errorf("detect Kubernetes pod CIDR: %w", err)
		}
		podCIDR = strings.TrimSpace(string(out))
	}
	if podCIDR == "" {
		podCIDR = "10.42.0.0/24"
	}
	serviceCIDR := os.Getenv("K8S_SERVICE_CIDR")
	if serviceCIDR == "" {
		serviceCIDR = "10.43.0.0/16"
	}
	for label, value := range map[string]string{"pod": podCIDR, "service": serviceCIDR} {
		if _, _, err := net.ParseCIDR(value); err != nil {
			return "", "", fmt.Errorf("invalid Kubernetes %s CIDR %q: %w", label, value, err)
		}
	}
	return podCIDR, serviceCIDR, nil
}

func validInterfaceName(name string) bool {
	if name == "" || len(name) > 15 {
		return false
	}
	for _, r := range name {
		if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != '_' && r != '-' && r != '.' {
			return false
		}
	}
	return true
}

func buildRuleset(table, wgIface, egress, podCIDR, serviceCIDR, wgSubnet, dnsIP string, wgPort int, dohIPv4, dohIPv6 []string) string {
	q := strconv.Quote

	var b strings.Builder
	lines := []string{
		"destroy table inet " + table,
		"add table inet " + table,
	}
	if len(dohIPv4) > 0 {
		lines = append(lines,
			"add set inet "+table+" doh_v4 { type ipv4_addr; }",
			"add element inet "+table+" doh_v4 { "+strings.Join(dohIPv4, ", ")+" }",
		)
	}
	if len(dohIPv6) > 0 {
		lines = append(lines,
			"add set inet "+table+" doh_v6 { type ipv6_addr; }",
			"add element inet "+table+" doh_v6 { "+strings.Join(dohIPv6, ", ")+" }",
		)
	}
	lines = append(lines,
		// Enforce the VPN DNS resolver even when a client attempts to use an
		// external plain-DNS server. Redirecting happens before forwarding, so
		// these queries reach the local resolver and never leave the host.
		"add chain inet "+table+" dns_redirect { type nat hook prerouting priority dstnat; policy accept; }",
		"add rule inet "+table+" dns_redirect iifname "+q(wgIface)+" udp dport 53 redirect to :53",
		"add rule inet "+table+" dns_redirect iifname "+q(wgIface)+" tcp dport 53 redirect to :53",

		// NAT only for VPN clients leaving via the real uplink.
		"add chain inet "+table+" nat { type nat hook postrouting priority srcnat; policy accept; }",
		"add rule inet "+table+" nat iifname "+q(wgIface)+" oifname "+q(egress)+" masquerade",

		// Fail-closed forward.
		"add chain inet "+table+" forward { type filter hook forward priority filter; policy drop; }",

		// Preserve Kubernetes CNI/service forwarding (never from the VPN iface).
		"add rule inet "+table+" forward iifname != "+q(wgIface)+" ip saddr "+podCIDR+" accept",
		"add rule inet "+table+" forward iifname != "+q(wgIface)+" ip daddr "+podCIDR+" accept",
		"add rule inet "+table+" forward iifname != "+q(wgIface)+" ip saddr "+serviceCIDR+" accept",
		"add rule inet "+table+" forward iifname != "+q(wgIface)+" ip daddr "+serviceCIDR+" accept",
		"add rule inet "+table+" forward iifname \"cni0\" accept",
		"add rule inet "+table+" forward oifname \"cni0\" accept",
		"add rule inet "+table+" forward iifname \"flannel.1\" accept",
		"add rule inet "+table+" forward oifname \"flannel.1\" accept",

		// VPN path: established return traffic first.
		"add rule inet "+table+" forward ct state established,related accept",

		// No client-to-client, no hairpin onto the tunnel.
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(wgIface)+" counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr "+wgSubnet+" counter drop",

		// Block VPN clients from LAN, cloud metadata, and other private ranges (IPv4).
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr 10.0.0.0/8 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr 172.16.0.0/12 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr 192.168.0.0/16 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr 169.254.0.0/16 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr 127.0.0.0/8 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip daddr 100.64.0.0/10 counter drop",

		// Same intent for IPv6 if forwarding is enabled on the node.
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip6 daddr fc00::/7 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip6 daddr fe80::/10 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip6 daddr ::1/128 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" ip6 daddr ff00::/8 counter drop",

		// DNS protection: plain DNS and DoT cannot bypass the gateway. Known
		// public DoH resolver anycast IPs are dropped on TCP/UDP 443 (see
		// doh_v4 / doh_v6). Unknown/custom DoH endpoints remain a residual risk.
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" udp dport 53 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" tcp dport 53 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" udp dport 853 counter drop",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" tcp dport 853 counter drop",
	)
	if len(dohIPv4) > 0 {
		lines = append(lines,
			"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" ip daddr @doh_v4 tcp dport 443 counter drop",
			"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" ip daddr @doh_v4 udp dport 443 counter drop",
		)
	}
	if len(dohIPv6) > 0 {
		lines = append(lines,
			"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" ip6 daddr @doh_v6 tcp dport 443 counter drop",
			"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" ip6 daddr @doh_v6 udp dport 443 counter drop",
		)
	}
	lines = append(lines,
		// MSS clamp + allow only VPN -> public egress.
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" tcp flags syn tcp option maxseg size set 1380",
		"add rule inet "+table+" forward oifname "+q(wgIface)+" tcp flags syn tcp option maxseg size set 1380",
		"add rule inet "+table+" forward iifname "+q(wgIface)+" oifname "+q(egress)+" accept",
		"add rule inet "+table+" forward iifname "+q(egress)+" oifname "+q(wgIface)+" ct state established,related accept",

		// Input: allow WG handshakes and VPN DNS; leave general host policy to veritas_filter.
		"add chain inet "+table+" input { type filter hook input priority filter; policy accept; }",
		"add rule inet "+table+" input ct state established,related accept",
		"add rule inet "+table+" input iifname "+q(wgIface)+" ip daddr "+dnsIP+" udp dport 53 accept",
		"add rule inet "+table+" input iifname "+q(wgIface)+" ip daddr "+dnsIP+" tcp dport 53 accept",
		"add rule inet "+table+" input iifname "+q(wgIface)+" ip protocol icmp accept",
		"add rule inet "+table+" input udp dport "+strconv.Itoa(wgPort)+" accept",
		// Drop any other unsolicited packets arriving from VPN clients.
		"add rule inet "+table+" input iifname "+q(wgIface)+" counter drop",
	)
	for _, line := range lines {
		b.WriteString(line)
		b.WriteByte('\n')
	}
	return b.String()
}

// Compatibility wrappers for callers that used the older split setup API.
func (m *Manager) SetupNAT(iface string) error {
	return m.Reconcile(iface, 51820, defaultBandwidthMbps)
}

func (m *Manager) SetupKillSwitch(iface string, port int) error {
	return m.Reconcile(iface, port, defaultBandwidthMbps)
}

func (m *Manager) SetupMSSClamp(iface string) error {
	return m.Reconcile(iface, 51820, defaultBandwidthMbps)
}

func (m *Manager) SetupBandwidth(iface string, mbps int) error {
	return m.Reconcile(iface, 51820, mbps)
}

func (m *Manager) EnableKillSwitch() error {
	return m.Reconcile("wg0", 51820, defaultBandwidthMbps)
}

func (m *Manager) DisableKillSwitch() error {
	return fmt.Errorf("server-side kill switch cannot be disabled by the agent")
}

// Cleanup intentionally keeps the fail-closed table in place. Removing NAT or
// forward isolation during a restart would open a brief exposure window.
func (m *Manager) Cleanup() error {
	return nil
}
