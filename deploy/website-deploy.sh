#!/usr/bin/env bash
# Auto-deploy: pull latest from GitHub and update the website.
# The Docker nginx mounts the website directory directly,
# so changes take effect immediately after pull.

set -euo pipefail

REPO_DIR="${REPO_ROOT:-/opt/veritasvpn}"
BRANCH="master"
LOG_FILE="/var/log/veritas-deploy.log"

log() {
    echo "[$(date "+%Y-%m-%d %H:%M:%S")] $*" | tee -a "$LOG_FILE"
}

cd "$REPO_DIR"

GIT_SSH_COMMAND="ssh -i ~/.ssh/id_ed25519_veritas_deploy -o StrictHostKeyChecking=yes" \
git fetch origin "$BRANCH" --quiet 2>&1 || {
    log "ERROR: git fetch failed"
    exit 1
}

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse "origin/$BRANCH")

if [ "$LOCAL" = "$REMOTE" ]; then
    # No changes — quiet exit (only log if verbose)
    exit 0
fi

log "New commits detected. Deploying..."
log "  Local:  ${LOCAL:0:8}"
log "  Remote: ${REMOTE:0:8}"

git reset --hard "origin/$BRANCH" 2>&1 | tee -a "$LOG_FILE"

log "Restarting nginx..."
docker restart veritasvpn-nginx-1 2>&1 | tee -a "$LOG_FILE"

log "Deploy complete."
