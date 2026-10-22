#!/usr/bin/env bash
# SessionStart hook: bring local main up to date with GitHub before work starts.
# Only fast-forwards; never merges, rebases or discards anything. Whatever
# happens is reported to the user (systemMessage) and to Claude (additionalContext).

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

report() {
  local msg="${1//\"/\'}"
  printf '{"systemMessage":"%s","hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"%s"}}\n' "$msg" "$msg"
  exit 0
}

if ! git fetch --quiet origin 2>/dev/null; then
  report "git sync: could not reach GitHub, working on the local copy as-is."
fi

branch=$(git rev-parse --abbrev-ref HEAD)
behind=$(git rev-list --count HEAD..origin/main)
ahead=$(git rev-list --count origin/main..HEAD)

if [ "$behind" -eq 0 ]; then
  [ "$ahead" -gt 0 ] && report "git sync: up to date with origin/main, $ahead local commit(s) not pushed yet."
  exit 0
fi

if [ "$branch" != "main" ]; then
  report "git sync: origin/main has $behind new commit(s) but the checkout is on '$branch', not pulled."
fi

if [ "$ahead" -gt 0 ]; then
  report "git sync: main has diverged from origin/main ($ahead local, $behind remote), not pulled. Needs a manual merge or rebase."
fi

if git merge --ff-only --quiet origin/main >/dev/null 2>&1; then
  report "git sync: pulled $behind new commit(s) from GitHub, now at $(git log -1 --format='%h %s' | tr -d '\"\\')."
else
  report "git sync: origin/main has $behind new commit(s) but the fast-forward failed, likely uncommitted local changes in the same files. Not pulled."
fi
