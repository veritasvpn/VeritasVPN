.PHONY: help build build-all test lint clean dev-up dev-down proto k8s-up k8s-down k8s-status k8s-images k8s-btcpay prod-up prod-down backup restore inventory health verify-deployment

help:
	@echo "VeritasVPN Makefile"
	@echo "==================="
	@echo "Development:"
	@echo "  make dev-up       - Start dev environment with host ports"
	@echo "  make dev-down     - Stop dev environment"
	@echo "  make dev-logs     - Tail container logs"
	@echo ""
	@echo "Production:"
	@echo "  make prod-up      - Start prod env (no host ports, hardened)"
	@echo "  make prod-down    - Stop prod env"
	@echo "  make backup       - Run full backup (WireGuard + PostgreSQL)"
	@echo "  make restore FILE - Restore PostgreSQL from backup file"
	@echo "  make inventory    - Capture system state snapshot"
	@echo "  make health       - Run health check against running stack"
	@echo ""
	@echo "Build:"
	@echo "  make build-all    - Build all Go services"
	@echo "  make build-auth   - Build auth-svc"
	@echo "  make build-wg     - Build wg-manager"
	@echo "  make build-billing- Build billing-svc"
	@echo "  make build-agent  - Build veritas-agent"
	@echo "  make build-cli    - Build CLI client"
	@echo "  make test         - Run all tests"
	@echo "  make lint         - Run linter"
	@echo "  make clean        - Clean build artifacts"
	@echo "  make proto        - Generate proto Go stubs"
	@echo ""
	@echo "Kubernetes:"
	@echo "  make k8s-up       - Apply K8s dev overlay + BTCPay"
	@echo "  make k8s-down     - Delete veritas and btcpay namespaces"
	@echo "  make k8s-status   - Show K8s cluster status"
	@echo "  make k8s-images   - Build and push all service images"
	@echo "  make k8s-btcpay   - Apply BTCPay Server stack"

BUILD_DIR ?= $(CURDIR)/build

dev-up:
	docker compose up -d
	@echo "Services starting at:"
	@echo "  auth-svc:   :8081"
	@echo "  wg-manager: :8082"
	@echo "  billing-svc::8083"
	@echo "  postgres:   :5432"
	@echo "  redis:      :6379"
	@echo "  nats:       :4222 (monitoring :8222)"

dev-down:
	docker compose down

dev-logs:
	docker compose logs -f

build-all: build-auth build-wg build-billing build-agent build-cli

build-auth:
	@mkdir -p $(BUILD_DIR)
	CGO_ENABLED=0 go build -o $(BUILD_DIR)/auth-svc ./services/auth-svc/cmd/server/
	@echo "Built auth-svc -> $(BUILD_DIR)/auth-svc"

build-wg:
	@mkdir -p $(BUILD_DIR)
	CGO_ENABLED=0 go build -o $(BUILD_DIR)/wg-manager ./services/wg-manager/cmd/server/
	@echo "Built wg-manager -> $(BUILD_DIR)/wg-manager"

build-billing:
	@mkdir -p $(BUILD_DIR)
	CGO_ENABLED=0 go build -o $(BUILD_DIR)/billing-svc ./services/billing-svc/cmd/server/
	@echo "Built billing-svc -> $(BUILD_DIR)/billing-svc"

build-agent:
	@mkdir -p $(BUILD_DIR)
	CGO_ENABLED=0 go build -o $(BUILD_DIR)/veritas-agent ./services/veritas-agent/cmd/agent/
	@echo "Built veritas-agent -> $(BUILD_DIR)/veritas-agent"

build-cli:
	@mkdir -p $(BUILD_DIR)
	CGO_ENABLED=0 go build -o $(BUILD_DIR)/veritas ./clients/cli/cmd/
	@echo "Built CLI client -> $(BUILD_DIR)/veritas"

test:
	@set -e; for module in api clients/cli lib/config lib/crypto lib/jwt lib/logging services/auth-svc services/bitcoin-readiness services/billing-svc services/browser-proxy services/veritas-agent services/telegram-notifier services/wg-manager; do echo "-- module"; (cd "$$module" && go test ./...); done

lint:
	golangci-lint run ./...

clean:
	rm -rf $(BUILD_DIR)

proto:
	buf generate api/
	@echo "Proto stubs generated"

go-mod-tidy:
	cd lib/config && go mod tidy
	cd lib/logging && go mod tidy
	cd lib/crypto && go mod tidy
	cd lib/jwt && go mod tidy
	cd services/auth-svc && go mod tidy
	cd services/wg-manager && go mod tidy
	cd services/billing-svc && go mod tidy
	cd services/veritas-agent && go mod tidy

# ── Kubernetes ─────────────────────────────────────────────

K8S_DIR := $(CURDIR)/deploy/k8s
REGISTRY ?= localhost:31500
TAG ?=

k8s-up: k8s-btcpay
	kubectl apply -k $(K8S_DIR)/overlays/dev/

k8s-down:
	kubectl delete namespace veritas --wait --ignore-not-found
	kubectl delete namespace btcpay --wait --ignore-not-found

k8s-status:
	@echo "=== Veritas ==="
	@kubectl get deploy,sts,ds,svc,ingress -n veritas 2>/dev/null || true
	@echo ""
	@echo "=== BTCPay ==="
	@kubectl get deploy,sts,svc -n btcpay 2>/dev/null || true
	@echo ""
	@kubectl get pods -A | grep -E "veritas|btcpay"

k8s-images:
	@test -n "$(TAG)" || (echo "TAG is required and must be immutable" >&2; exit 1)
	REGISTRY=$(REGISTRY) TAG=$(TAG) bash $(K8S_DIR)/scripts/push-images.sh

k8s-btcpay:
	kubectl apply -k $(K8S_DIR)/btcpay/

# ── Production ─────────────────────────────────────────────

prod-up:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
	@echo "Production stack started (internal ports only)"

prod-down:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml down

backup:
	bash deploy/backup/backup.sh

restore:
	@if [ -z "$(filter-out $@,$(MAKECMDGOALS))" ]; then \
		echo "usage: make restore FILE=./backups/postgres/daily/veritas-xxx.sql.gz"; \
	else \
		bash deploy/backup/restore-postgres.sh $(filter-out $@,$(MAKECMDGOALS)); \
	fi

inventory:
	sudo bash deploy/backup/inventory.sh

health:
	bash deploy/monitoring/health-check.sh

verify-deployment:
	bash deploy/verify/deployment-drift.sh
