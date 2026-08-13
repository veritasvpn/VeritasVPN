//go:build ignore

package handler

import (
	"context"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/model"
	"github.com/veritasvpn/services/wg-manager/internal/service"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type CreatePeerRequest struct {
	AccountId       string `json:"account_id"`
	PublicKey       string `json:"public_key"`
	PreferredRegion string `json:"preferred_region"`
}

type CreatePeerResponse struct {
	PeerId          string   `json:"peer_id"`
	ServerId        string   `json:"server_id"`
	ServerHostname  string   `json:"server_hostname"`
	ServerPublicKey string   `json:"server_public_key"`
	ServerEndpoint  string   `json:"server_endpoint"`
	AssignedIp      string   `json:"assigned_ip"`
	DnsServer       string   `json:"dns_server"`
	AllowedIps      []string `json:"allowed_ips"`
}

type DeletePeerRequest struct {
	PeerId    string `json:"peer_id"`
	AccountId string `json:"account_id"`
}

type DeletePeerResponse struct {
	Success bool `json:"success"`
}

type GetPeerRequest struct {
	PeerId    string `json:"peer_id"`
	AccountId string `json:"account_id"`
}

type GetPeerResponse struct {
	PeerId          string `json:"peer_id"`
	ServerHostname  string `json:"server_hostname"`
	ServerEndpoint  string `json:"server_endpoint"`
	ServerPublicKey string `json:"server_public_key"`
	AssignedIp      string `json:"assigned_ip"`
	Status          string `json:"status"`
	CreatedAt       int64  `json:"created_at"`
}

type ListPeersRequest struct {
	AccountId string `json:"account_id"`
}

type ListPeersResponse struct {
	Peers []*PeerInfo `json:"peers"`
}

type PeerInfo struct {
	PeerId         string `json:"peer_id"`
	ServerHostname string `json:"server_hostname"`
	ServerEndpoint string `json:"server_endpoint"`
	AssignedIp     string `json:"assigned_ip"`
	Status         string `json:"status"`
	CreatedAt      int64  `json:"created_at"`
}

type ListServersRequest struct {
	Region string `json:"region"`
}

type ListServersResponse struct {
	Servers []*ServerInfo `json:"servers"`
}

type ServerInfo struct {
	Id         string  `json:"id"`
	Hostname   string  `json:"hostname"`
	Region     string  `json:"region"`
	City       string  `json:"city"`
	Country    string  `json:"country"`
	LoadFactor float64 `json:"load_factor"`
	Status     string  `json:"status"`
}

type GetServerConfigRequest struct {
	ServerId string `json:"server_id"`
}

type GetServerConfigResponse struct {
	ServerId   string `json:"server_id"`
	Hostname   string `json:"hostname"`
	PublicKey  string `json:"public_key"`
	Endpoint   string `json:"endpoint"`
	WgPort     int32  `json:"wg_port"`
	DnsServer  string `json:"dns_server"`
}

type WireGuardServiceServer interface {
	CreatePeer(context.Context, *CreatePeerRequest) (*CreatePeerResponse, error)
	DeletePeer(context.Context, *DeletePeerRequest) (*DeletePeerResponse, error)
	GetPeer(context.Context, *GetPeerRequest) (*GetPeerResponse, error)
	ListPeers(context.Context, *ListPeersRequest) (*ListPeersResponse, error)
	ListServers(context.Context, *ListServersRequest) (*ListServersResponse, error)
	GetServerConfig(context.Context, *GetServerConfigRequest) (*GetServerConfigResponse, error)
}

type WireGuardHandler struct {
	svc *service.Service
	log *logging.Logger
}

func NewWireGuardHandler(svc *service.Service, log *logging.Logger) *WireGuardHandler {
	return &WireGuardHandler{svc: svc, log: log}
}

func (h *WireGuardHandler) CreatePeer(ctx context.Context, req *CreatePeerRequest) (*CreatePeerResponse, error) {
	if req.AccountId == "" {
		return nil, status.Error(codes.InvalidArgument, "account_id is required")
	}
	if req.PublicKey == "" {
		return nil, status.Error(codes.InvalidArgument, "public_key is required")
	}

	cfg, err := h.svc.CreatePeer(ctx, req.AccountId, req.PublicKey, req.PreferredRegion)
	if err != nil {
		h.log.Error("create peer failed", "account_id", req.AccountId, "error", err)
		return nil, status.Errorf(codes.Internal, "create peer: %v", err)
	}

	return &CreatePeerResponse{
		PeerId:          cfg.PeerID,
		ServerId:        cfg.ServerID,
		ServerHostname:  cfg.ServerHostname,
		ServerPublicKey: cfg.ServerPublicKey,
		ServerEndpoint:  cfg.ServerEndpoint,
		AssignedIp:      cfg.AssignedIP,
		DnsServer:       cfg.DNSServer,
		AllowedIps:      cfg.AllowedIPs,
	}, nil
}

func (h *WireGuardHandler) DeletePeer(ctx context.Context, req *DeletePeerRequest) (*DeletePeerResponse, error) {
	if req.PeerId == "" {
		return nil, status.Error(codes.InvalidArgument, "peer_id is required")
	}
	if req.AccountId == "" {
		return nil, status.Error(codes.InvalidArgument, "account_id is required")
	}

	if err := h.svc.DeletePeer(ctx, req.PeerId, req.AccountId); err != nil {
		h.log.Error("delete peer failed", "peer_id", req.PeerId, "error", err)
		return nil, status.Errorf(codes.Internal, "delete peer: %v", err)
	}

	return &DeletePeerResponse{Success: true}, nil
}

func (h *WireGuardHandler) GetPeer(ctx context.Context, req *GetPeerRequest) (*GetPeerResponse, error) {
	if req.PeerId == "" {
		return nil, status.Error(codes.InvalidArgument, "peer_id is required")
	}
	if req.AccountId == "" {
		return nil, status.Error(codes.InvalidArgument, "account_id is required")
	}

	peer, srv, err := h.svc.GetPeer(ctx, req.PeerId, req.AccountId)
	if err != nil {
		h.log.Error("get peer failed", "peer_id", req.PeerId, "error", err)
		return nil, status.Errorf(codes.NotFound, "get peer: %v", err)
	}

	resp := &GetPeerResponse{
		PeerId:     peer.ID,
		AssignedIp: peer.AssignedIP,
		Status:     peer.Status,
		CreatedAt:  peer.CreatedAt.Unix(),
	}

	if srv != nil {
		resp.ServerHostname = srv.Hostname
		resp.ServerEndpoint = formatEndpoint(srv.PublicIP, srv.WGPort)
		resp.ServerPublicKey = srv.PublicKey
	}

	return resp, nil
}

func (h *WireGuardHandler) ListPeers(ctx context.Context, req *ListPeersRequest) (*ListPeersResponse, error) {
	if req.AccountId == "" {
		return nil, status.Error(codes.InvalidArgument, "account_id is required")
	}

	peers, err := h.svc.ListPeers(ctx, req.AccountId)
	if err != nil {
		h.log.Error("list peers failed", "account_id", req.AccountId, "error", err)
		return nil, status.Errorf(codes.Internal, "list peers: %v", err)
	}

	resp := &ListPeersResponse{Peers: make([]*PeerInfo, 0, len(peers))}
	for _, p := range peers {
		resp.Peers = append(resp.Peers, &PeerInfo{
			PeerId:         p.ID,
			ServerEndpoint: "", // would need a server lookup for each; skip for now
			AssignedIp:     p.AssignedIP,
			Status:         p.Status,
			CreatedAt:      p.CreatedAt.Unix(),
		})
	}

	return resp, nil
}

func (h *WireGuardHandler) ListServers(ctx context.Context, req *ListServersRequest) (*ListServersResponse, error) {
	servers, err := h.svc.ListServers(ctx)
	if err != nil {
		h.log.Error("list servers failed", "error", err)
		return nil, status.Errorf(codes.Internal, "list servers: %v", err)
	}

	var filtered []model.Server
	if req.Region != "" {
		for _, s := range servers {
			if s.Region == req.Region {
				filtered = append(filtered, s)
			}
		}
	} else {
		filtered = servers
	}

	resp := &ListServersResponse{Servers: make([]*ServerInfo, 0, len(filtered))}
	for _, s := range filtered {
		resp.Servers = append(resp.Servers, &ServerInfo{
			Id:         s.ID,
			Hostname:   s.Hostname,
			Region:     s.Region,
			City:       s.City,
			Country:    s.Country,
			LoadFactor: s.LoadFactor,
			Status:     s.Status,
		})
	}

	return resp, nil
}

func (h *WireGuardHandler) GetServerConfig(ctx context.Context, req *GetServerConfigRequest) (*GetServerConfigResponse, error) {
	if req.ServerId == "" {
		return nil, status.Error(codes.InvalidArgument, "server_id is required")
	}

	// ListServers gets all online servers; this is a simple approach
	// In production, add a repo method to get server by ID
	servers, err := h.svc.ListServers(ctx)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "list servers: %v", err)
	}

	for _, s := range servers {
		if s.ID == req.ServerId {
			return &GetServerConfigResponse{
				ServerId:  s.ID,
				Hostname:  s.Hostname,
				PublicKey: s.PublicKey,
				Endpoint:  formatEndpoint(s.PublicIP, s.WGPort),
				WgPort:    s.WGPort,
				DnsServer: s.DNSServer,
			}, nil
		}
	}

	return nil, status.Error(codes.NotFound, "server not found")
}

func formatEndpoint(publicIP string, wgPort int32) string {
	return publicIP + ":" + itoa(int(wgPort))
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	neg := false
	if i < 0 {
		neg = true
		i = -i
	}
	var buf [12]byte
	pos := len(buf)
	for i > 0 {
		pos--
		buf[pos] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}

// ———— gRPC Service registration ————

func RegisterWireGuardServiceServer(s grpc.ServiceRegistrar, srv WireGuardServiceServer) {
	s.RegisterService(&WireGuardService_ServiceDesc, srv)
}

var WireGuardService_ServiceDesc = grpc.ServiceDesc{
	ServiceName: "wg.v1.WireGuardService",
	HandlerType: (*WireGuardServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "CreatePeer",
			Handler:    _WireGuardService_CreatePeer_Handler,
		},
		{
			MethodName: "DeletePeer",
			Handler:    _WireGuardService_DeletePeer_Handler,
		},
		{
			MethodName: "GetPeer",
			Handler:    _WireGuardService_GetPeer_Handler,
		},
		{
			MethodName: "ListPeers",
			Handler:    _WireGuardService_ListPeers_Handler,
		},
		{
			MethodName: "ListServers",
			Handler:    _WireGuardService_ListServers_Handler,
		},
		{
			MethodName: "GetServerConfig",
			Handler:    _WireGuardService_GetServerConfig_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "wg.proto",
}

func _WireGuardService_CreatePeer_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(CreatePeerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(WireGuardServiceServer).CreatePeer(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "wg.v1.WireGuardService/CreatePeer",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(WireGuardServiceServer).CreatePeer(ctx, req.(*CreatePeerRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _WireGuardService_DeletePeer_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(DeletePeerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(WireGuardServiceServer).DeletePeer(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "wg.v1.WireGuardService/DeletePeer",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(WireGuardServiceServer).DeletePeer(ctx, req.(*DeletePeerRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _WireGuardService_GetPeer_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(GetPeerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(WireGuardServiceServer).GetPeer(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "wg.v1.WireGuardService/GetPeer",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(WireGuardServiceServer).GetPeer(ctx, req.(*GetPeerRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _WireGuardService_ListPeers_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(ListPeersRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(WireGuardServiceServer).ListPeers(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "wg.v1.WireGuardService/ListPeers",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(WireGuardServiceServer).ListPeers(ctx, req.(*ListPeersRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _WireGuardService_ListServers_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(ListServersRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(WireGuardServiceServer).ListServers(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "wg.v1.WireGuardService/ListServers",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(WireGuardServiceServer).ListServers(ctx, req.(*ListServersRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _WireGuardService_GetServerConfig_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(GetServerConfigRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(WireGuardServiceServer).GetServerConfig(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "wg.v1.WireGuardService/GetServerConfig",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(WireGuardServiceServer).GetServerConfig(ctx, req.(*GetServerConfigRequest))
	}
	return interceptor(ctx, in, info, handler)
}
