package handler

import (
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

// listServersPublicView is the JSON object shape returned by GET /api/v1/wg/servers
// for one server. Intentionally omits stealth_path_prefix (peer create/get only).
func listServersPublicView(srv *model.Server, serverEndpoint, stealthEndpoint string, stealthAvailable bool) map[string]interface{} {
	return map[string]interface{}{
		"id":                srv.ID,
		"hostname":          srv.Hostname,
		"public_ip":         srv.PublicIP,
		"wg_port":           srv.WGPort,
		"public_key":        srv.PublicKey,
		"status":            srv.Status,
		"region":            srv.Region,
		"city":              srv.City,
		"country":           srv.Country,
		"server_endpoint":   serverEndpoint,
		"stealth_endpoint":  stealthEndpoint,
		"stealth_available": stealthAvailable,
	}
}
