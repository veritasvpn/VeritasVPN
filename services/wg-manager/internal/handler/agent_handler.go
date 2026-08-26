//go:build ignore

package handler

import (
	"context"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/service"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type RegisterServerRequest struct {
	Hostname  string `json:"hostname"`
	PublicKey string `json:"public_key"`
	PublicIp  string `json:"public_ip"`
	WgPort    int32  `json:"wg_port"`
	Region    string `json:"region"`
	City      string `json:"city"`
	Country   string `json:"country"`
	AuthToken string `json:"auth_token"`
}

type RegisterServerResponse struct {
	ServerId  string `json:"server_id"`
	WgSubnet  string `json:"wg_subnet"`
	DnsServer string `json:"dns_server"`
	Capacity  int32  `json:"capacity"`
}

type HeartbeatRequest struct {
	ServerId   string  `json:"server_id"`
	PeerCount  int32   `json:"peer_count"`
	LoadFactor float64 `json:"load_factor"`
	RxBytes    int64   `json:"rx_bytes"`
	TxBytes    int64   `json:"tx_bytes"`
}

type HeartbeatResponse struct {
	Ok bool `json:"ok"`
}

type AgentServiceServer interface {
	RegisterServer(context.Context, *RegisterServerRequest) (*RegisterServerResponse, error)
	Heartbeat(context.Context, *HeartbeatRequest) (*HeartbeatResponse, error)
}

type AgentHandler struct {
	svc *service.Service
	log *logging.Logger
}

func NewAgentHandler(svc *service.Service, log *logging.Logger) *AgentHandler {
	return &AgentHandler{svc: svc, log: log}
}

func (h *AgentHandler) RegisterServer(ctx context.Context, req *RegisterServerRequest) (*RegisterServerResponse, error) {
	if req.Hostname == "" {
		return nil, status.Error(codes.InvalidArgument, "hostname is required")
	}
	if req.PublicKey == "" {
		return nil, status.Error(codes.InvalidArgument, "public_key is required")
	}
	if req.PublicIp == "" {
		return nil, status.Error(codes.InvalidArgument, "public_ip is required")
	}
	if req.AuthToken == "" {
		return nil, status.Error(codes.InvalidArgument, "auth_token is required")
	}

	srv, err := h.svc.RegisterServer(ctx,
		req.Hostname,
		req.PublicKey,
		req.PublicIp,
		req.WgPort,
		req.Region,
		req.City,
		req.Country,
		req.AuthToken,
	)
	if err != nil {
		h.log.Error("register server failed", "hostname", req.Hostname, "error", err)
		return nil, status.Errorf(codes.PermissionDenied, "register server: %v", err)
	}

	return &RegisterServerResponse{
		ServerId:  srv.ID,
		WgSubnet:  srv.WGSubnet,
		DnsServer: srv.DNSServer,
		Capacity:  srv.Capacity,
	}, nil
}

func (h *AgentHandler) Heartbeat(ctx context.Context, req *HeartbeatRequest) (*HeartbeatResponse, error) {
	if req.ServerId == "" {
		return nil, status.Error(codes.InvalidArgument, "server_id is required")
	}

	if err := h.svc.HandleHeartbeat(ctx, req.ServerId, req.PeerCount, req.LoadFactor, req.RxBytes, req.TxBytes, nil); err != nil {
		h.log.Error("heartbeat failed", "server_id", req.ServerId, "error", err)
		return nil, status.Errorf(codes.Internal, "heartbeat: %v", err)
	}

	return &HeartbeatResponse{Ok: true}, nil
}

func RegisterAgentServiceServer(s grpc.ServiceRegistrar, srv AgentServiceServer) {
	s.RegisterService(&AgentService_ServiceDesc, srv)
}

var AgentService_ServiceDesc = grpc.ServiceDesc{
	ServiceName: "agent.v1.AgentService",
	HandlerType: (*AgentServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "RegisterServer",
			Handler:    _AgentService_RegisterServer_Handler,
		},
		{
			MethodName: "Heartbeat",
			Handler:    _AgentService_Heartbeat_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "agent.proto",
}

func _AgentService_RegisterServer_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(RegisterServerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(AgentServiceServer).RegisterServer(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "agent.v1.AgentService/RegisterServer",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(AgentServiceServer).RegisterServer(ctx, req.(*RegisterServerRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _AgentService_Heartbeat_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(HeartbeatRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(AgentServiceServer).Heartbeat(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "agent.v1.AgentService/Heartbeat",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(AgentServiceServer).Heartbeat(ctx, req.(*HeartbeatRequest))
	}
	return interceptor(ctx, in, info, handler)
}
