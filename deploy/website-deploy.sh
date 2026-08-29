#!/usr/bin/env bash
set -euo pipefail

printf 'Local website deployment is retired. Cloudflare Pages deploys website/ from master through .github/workflows/pages-deploy.yml.\n' >&2
exit 2
