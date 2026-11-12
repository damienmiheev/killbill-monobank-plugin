#!/usr/bin/env bash
# Applies the repository's GitHub settings: merge settings, security features, Actions policy, environments and the
# rulesets in .github/rulesets/. Idempotent; safe to re-run. Secrets are never set here (see RELEASING.md).
#
# Usage: scripts/github-settings.sh [owner/repo]     (needs `gh` logged in as the repository admin)
set -euo pipefail

repo="${1:-damienmiheev/killbill-monobank-plugin}"
owner="${repo%%/*}"
dir="$(cd "$(dirname "$0")/.." && pwd)"
api() { gh api -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"; }

echo "== Repository settings"
api -X PATCH "repos/$repo" --silent --input - <<'JSON'
{
  "allow_squash_merge": true,
  "allow_merge_commit": false,
  "allow_rebase_merge": false,
  "allow_update_branch": true,
  "delete_branch_on_merge": true,
  "squash_merge_commit_title": "PR_TITLE",
  "squash_merge_commit_message": "PR_BODY",
  "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" }
  }
}
JSON

echo "== Security features"
api -X PUT "repos/$repo/vulnerability-alerts" --silent
api -X PUT "repos/$repo/automated-security-fixes" --silent
api -X PUT "repos/$repo/private-vulnerability-reporting" --silent
api -X PUT "repos/$repo/immutable-releases" --silent

echo "== Actions policy"
api -X PUT "repos/$repo/actions/permissions" --silent --input - <<'JSON'
{ "enabled": true, "allowed_actions": "selected", "sha_pinning_required": true }
JSON
api -X PUT "repos/$repo/actions/permissions/selected-actions" --silent --input - <<'JSON'
{
  "github_owned_allowed": true,
  "verified_allowed": false,
  "patterns_allowed": ["ossf/scorecard-action@*", "advanced-security/maven-dependency-submission-action@*"]
}
JSON
api -X PUT "repos/$repo/actions/permissions/workflow" --silent --input - <<'JSON'
{ "default_workflow_permissions": "read", "can_approve_pull_request_reviews": false }
JSON
api -X PUT "repos/$repo/actions/permissions/fork-pr-contributor-approval" --silent --input - <<'JSON'
{ "approval_policy": "all_external_contributors" }
JSON

echo "== Environments"
owner_id="$(api "users/$owner" --jq .id)"
api -X PUT "repos/$repo/environments/maven-central" --silent --input - <<JSON
{
  "reviewers": [{ "type": "User", "id": $owner_id }],
  "prevent_self_review": false,
  "can_admins_bypass": false,
  "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
}
JSON
# Policies as "name type" lines, read in full before matching (no early pipe exit under pipefail).
policies() { api "repos/$repo/environments/$1/deployment-branch-policies" --jq '.branch_policies[] | "\(.name) \(.type)"'; }
existing="$(policies maven-central)"
if ! grep -qxF 'v* tag' <<<"$existing"; then
  api -X POST "repos/$repo/environments/maven-central/deployment-branch-policies" --silent -f name='v*' -f type=tag
fi
# The nightly live suite runs unattended, so this environment has no reviewer; only main may use it.
api -X PUT "repos/$repo/environments/monobank-test" --silent --input - <<'JSON'
{ "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true } }
JSON
existing="$(policies monobank-test)"
if ! grep -qxF 'main branch' <<<"$existing"; then
  api -X POST "repos/$repo/environments/monobank-test/deployment-branch-policies" --silent -f name=main -f type=branch
fi

echo "== Classic branch protection (replaced by rulesets)"
if api "repos/$repo/branches/main/protection" --silent 2>/dev/null; then
  api -X DELETE "repos/$repo/branches/main/protection" --silent
  echo "removed"
else
  echo "none"
fi

echo "== Rulesets"
for file in "$dir"/.github/rulesets/*.json; do
  name="$(jq -r .name "$file")"
  id="$(api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id")"
  if [ -n "$id" ]; then
    api -X PUT "repos/$repo/rulesets/$id" --silent --input "$file"
    echo "updated $name"
  else
    api -X POST "repos/$repo/rulesets" --silent --input "$file"
    echo "created $name"
  fi
done

echo "Done. Secrets (RELEASING.md, One-time setup) are set by hand."
