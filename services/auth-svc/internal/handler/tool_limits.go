package handler

import (
	"encoding/json"
	"net/http"
)

func (h *HTTPHandler) handleToolLimit(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodPost {
		writeHTTPError(w, 405, "method not allowed")
		return
	}
	var req struct {
		Bucket string `json:"bucket"`
		IP     string `json:"ip"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		writeHTTPError(w, 400, "invalid request")
		return
	}
	if !h.service.VerifyToolRequest(req.Bucket, req.IP, r.Header.Get("X-Tool-Timestamp"), r.Header.Get("X-Tool-Signature")) {
		writeHTTPError(w, 401, "unauthorized")
		return
	}
	limited, err := h.service.LimitToolRequest(r.Context(), req.Bucket, req.IP)
	if err != nil {
		writeHTTPError(w, 503, "limiter unavailable")
		return
	}
	if limited {
		w.Header().Set("Retry-After", "60")
		writeHTTPError(w, 429, "too many requests")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
