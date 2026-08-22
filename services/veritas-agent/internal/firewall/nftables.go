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

type Manager struct {
	tableName string
}

func New() *Manager {
	return &Manager{tableName: "veritas"}
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

	egress := os.Getenv("EGRESS_IFACE")
	if egress == "" {
		out, err := exec.Command("sh", "-c", "ip route show default | awk '{print $5; exit}'").Output()
		if err != nil {
			return fmt.Errorf("detect egress interface: %w", err)
		}
		egress = strings.TrimSpace(string(out))
	}
	if !validInterfaceName(egress) {
		return fmt.Errorf("invalid egress interface %q", egress)
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

	return m.runScript(buildRuleset(m.tableName, wgIface, egress, podCIDR, serviceCIDR, wgSubnet, dnsIP, wgPort))
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

func buildRuleset(table, wgIface, egress, podCIDR, serviceCIDR, wgSubnet, dnsIP string, wgPort int) string {
	q := strconv.Quote

	var b strings.Builder
	lines := []string{
		"destroy table inet " + table,
		"add table inet " + table,

		// NAT only for VPN clients leaving via the real uplink.
		"add chain inet " + table + " nat { type nat hook postrouting priority srcnat; policy accept; }",
		"add rule inet " + table + " nat iifname " + q(wgIface) + " oifname " + q(egress) + " masquerade",

		// Fail-closed forward.
		"add chain inet " + table + " forward { type filter hook forward priority filter; policy drop; }",

		// Preserve Kubernetes CNI/service forwarding (never from the VPN iface).
		"add rule inet " + table + " forward iifname != " + q(wgIface) + " ip saddr " + podCIDR + " accept",
		"add rule inet " + table + " forward iifname != " + q(wgIface) + " ip daddr " + podCIDR + " accept",
		"add rule inet " + table + " forward iifname != " + q(wgIface) + " ip saddr " + serviceCIDR + " accept",
		"add rule inet " + table + " forward iifname != " + q(wgIface) + " ip daddr " + serviceCIDR + " accept",
		"add rule inet " + table + " forward iifname \"cni0\" accept",
		"add rule inet " + table + " forward oifname \"cni0\" accept",
		"add rule inet " + table + " forward iifname \"flannel.1\" accept",
		"add rule inet " + table + " forward oifname \"flannel.1\" accept",

		// VPN path: established return traffic first.
		"add rule inet " + table + " forward ct state established,related accept",

		// No client-to-client, no hairpin onto the tunnel.
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(wgIface) + " counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr " + wgSubnet + " counter drop",

		// Block VPN clients from LAN, cloud metadata, and other private ranges.
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr 10.0.0.0/8 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr 172.16.0.0/12 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr 192.168.0.0/16 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr 169.254.0.0/16 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr 127.0.0.0/8 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " ip daddr 100.64.0.0/10 counter drop",

		// DNS protection is mandatory for WireGuard clients. Permit only the
		// in-tunnel gateway (handled by input below), and prevent plain DNS or
		// DNS-over-TLS from bypassing its malware/phishing policy. DNS-over-HTTPS
		// cannot be blocked generically without breaking ordinary HTTPS traffic.
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(egress) + " udp dport 53 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(egress) + " tcp dport 53 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(egress) + " udp dport 853 counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(egress) + " tcp dport 853 counter drop",

		// MSS clamp + allow only VPN -> public egress.
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(egress) + " tcp flags syn tcp option maxseg size set 1380",
		"add rule inet " + table + " forward oifname " + q(wgIface) + " tcp flags syn tcp option maxseg size set 1380",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " oifname " + q(egress) + " accept",
		"add rule inet " + table + " forward iifname " + q(egress) + " oifname " + q(wgIface) + " ct state established,related accept",

		// Input: allow WG handshakes and VPN DNS; leave general host policy to veritas_filter.
		"add chain inet " + table + " input { type filter hook input priority filter; policy accept; }",
		"add rule inet " + table + " input ct state established,related accept",
		"add rule inet " + table + " input iifname " + q(wgIface) + " ip daddr " + dnsIP + " udp dport 53 accept",
		"add rule inet " + table + " input iifname " + q(wgIface) + " ip daddr " + dnsIP + " tcp dport 53 accept",
		"add rule inet " + table + " input iifname " + q(wgIface) + " ip protocol icmp accept",
		"add rule inet " + table + " input udp dport " + strconv.Itoa(wgPort) + " accept",
		// Drop any other unsolicited packets arriving from VPN clients.
		"add rule inet " + table + " input iifname " + q(wgIface) + " counter drop",
	}
	for _, line := range lines {
		b.WriteString(line)
		b.WriteByte('\n')
	}
	return b.String()
}

// Compatibility wrappers for callers that used the older split setup API.
func (m *Manager) SetupNAT(iface string) error { return m.Reconcile(iface, 51820, 50) }

func (m *Manager) SetupKillSwitch(iface string, port int) error { return m.Reconcile(iface, port, 50) }

func (m *Manager) SetupMSSClamp(iface string) error { return m.Reconcile(iface, 51820, 50) }

func (m *Manager) SetupBandwidth(iface string, mbps int) error {
	return m.Reconcile(iface, 51820, mbps)
}

func (m *Manager) EnableKillSwitch() error {
	return m.Reconcile("wg0", 51820, 50)
}

func (m *Manager) DisableKillSwitch() error {
	return fmt.Errorf("server-side kill switch cannot be disabled by the agent")
}

// Cleanup intentionally keeps the fail-closed table in place. Removing NAT or
// forward isolation during a restart would open a brief exposure window.
func (m *Manager) Cleanup() error {
	return nil
}
