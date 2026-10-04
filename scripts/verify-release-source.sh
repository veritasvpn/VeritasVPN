#!/usr/bin/env bash
set -euo pipefail
case "${GITHUB_REF:?}" in
  refs/heads/master) ;;
  refs/tags/v*)
    [[ "$GITHUB_REF" =~ ^refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 1 ;;
  *) echo 'Only master or a protected semantic version tag may be signed.' >&2; exit 1 ;;
esac
git fetch origin master
release_commit=$(git rev-parse HEAD)
git merge-base --is-ancestor "$release_commit" origin/master
# CI must have succeeded on master at this exact commit, not a PR merge or
# another workflow with a similar display name. No signing secrets in this job.
gh api "repos/${GITHUB_REPOSITORY:?}/actions/workflows/ci.yml/runs?head_sha=$release_commit&event=push&branch=master&status=success&per_page=100" \
  --jq '.workflow_runs | any(.conclusion == "success")' | grep -qx true
