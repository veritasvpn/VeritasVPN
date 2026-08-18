package firewall

import (
	"context"
	"fmt"
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

// Reconcile atomically rebuilds the agent-owned nftables table. The forward
// chain is intentionally fail-closed: only WireGuard-originated traffic and
// return traffic for an established flow are accepted. Rebuilding the whole
// table in one nft transaction makes startup/restarts convergent and prevents
// duplicate NAT, MSS, or bandwidth rules from accumulating.
func (m *Manager) Reconcile(wgIface string, wgPort, mbps int) error {
	if !validInterfaceName(wgIface) {
		return fmt.Errorf("invalid WireGuard interface %q", wgIface)
	}
	if wgPort <= 0 || wgPort > 65535 {
		return fmt.Errorf("invalid WireGuard port %d", wgPort)
	}
	if mbps <= 0 {
		mbps = 50
	}

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

	return m.runScript(buildRuleset(m.tableName, wgIface, egress, wgPort, mbps))
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

func buildRuleset(table, wgIface, egress string, wgPort, mbps int) string {
	bytesRate := fmt.Sprintf("%d kbytes/second", mbps*125)
	q := strconv.Quote

	var b strings.Builder
	lines := []string{
		"destroy table inet " + table,
		"add table inet " + table,
		"add chain inet " + table + " nat { type nat hook postrouting priority srcnat; policy accept; }",
		"add chain inet " + table + " forward { type filter hook forward priority filter; policy drop; }",
		"add chain inet " + table + " input { type filter hook input priority filter; policy accept; }",
		"add rule inet " + table + " nat iifname " + q(wgIface) + " oifname " + q(egress) + " masquerade",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " meter vpn_upload { ip saddr limit rate over " + bytesRate + " burst 1 mbytes } counter drop",
		"add rule inet " + table + " forward oifname " + q(wgIface) + " meter vpn_download { ip daddr limit rate over " + bytesRate + " burst 1 mbytes } counter drop",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " tcp flags syn tcp option maxseg size set 1380",
		"add rule inet " + table + " forward oifname " + q(wgIface) + " tcp flags syn tcp option maxseg size set 1380",
		"add rule inet " + table + " forward ct state established,related accept",
		"add rule inet " + table + " forward iifname " + q(wgIface) + " accept",
		"add rule inet " + table + " input ct state established,related accept",
		"add rule inet " + table + " input iifname " + q(wgIface) + " accept",
		"add rule inet " + table + " input udp dport " + strconv.Itoa(wgPort) + " accept",
	}
	for _, line := range lines {
		b.WriteString(line)
		b.WriteByte('\n')
	}
	return b.String()
}

// Compatibility wrappers for callers that used the older split setup API. All
// wrappers now use the same atomic, fail-closed reconciliation path.
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

func (m *Manager) Cleanup() error {
	return m.run("delete", "table", "inet", m.tableName)
}
