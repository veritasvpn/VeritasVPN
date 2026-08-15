package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/nats-io/nats.go"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func required(name string) string {
	v := strings.TrimSpace(os.Getenv(name))
	if v == "" {
		log.Fatalf("%s is required", name)
	}
	return v
}
func sendTelegram(client *http.Client, token, chatID, message string) error {
	form := url.Values{"chat_id": []string{chatID}, "text": []string{message}, "disable_web_page_preview": []string{"true"}}
	req, err := http.NewRequest(http.MethodPost, "https://api.telegram.org/bot"+token+"/sendMessage", bytes.NewBufferString(form.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("telegram returned HTTP %s", resp.Status)
	}
	return nil
}
func main() {
	token := required("TELEGRAM_BOT_TOKEN")
	chatID := required("TELEGRAM_ACTIVITY_CHAT_ID")
	natsURL := os.Getenv("NATS_URL")
	if natsURL == "" {
		natsURL = "nats://nats.veritas.svc.cluster.local:4222"
	}
	client := &http.Client{Timeout: 10 * time.Second}
	nc, err := nats.Connect(natsURL, nats.RetryOnFailedConnect(true), nats.MaxReconnects(-1), nats.ReconnectWait(2*time.Second), nats.Timeout(5*time.Second))
	if err != nil {
		log.Fatalf("connect to NATS: %v", err)
	}
	defer nc.Drain()
	handler := func(subject string) nats.MsgHandler {
		return func(msg *nats.Msg) {
			var e map[string]interface{}
			if err := json.Unmarshal(msg.Data, &e); err != nil {
				log.Printf("invalid %s event: %v", subject, err)
				return
			}
			var text string
			switch subject {
			case "account.registered":
				kind, _ := e["account_type"].(string)
				if kind == "" {
					kind = "unknown"
				}
				text = fmt.Sprintf("New %s account registered", kind)
			case "subscription.renewed":
				method, _ := e["payment_method"].(string)
				if method == "" {
					method = "Bitcoin"
				}
				text = fmt.Sprintf("Premium payment received (%s); subscription activated", method)
			default:
				return
			}
			if err := sendTelegram(client, token, chatID, text); err != nil {
				log.Printf("send %s activity failed: %v", subject, err)
			}
		}
	}
	if _, err := nc.Subscribe("account.registered", handler("account.registered")); err != nil {
		log.Fatal(err)
	}
	if _, err := nc.Subscribe("subscription.renewed", handler("subscription.renewed")); err != nil {
		log.Fatal(err)
	}
	if err := nc.Flush(); err != nil {
		log.Fatal(err)
	}
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		if nc.IsConnected() {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
			return
		}
		http.Error(w, "nats disconnected", http.StatusServiceUnavailable)
	})
	go http.ListenAndServe(":8080", nil)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
}
