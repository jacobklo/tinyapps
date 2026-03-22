#!/bin/bash
# Integration test: simulates a Claude Code Stop hook event.
# Pipes the same JSON format that Claude Code sends to the hook script.
#
# Usage:
#   bash test/test_hook_integration.sh
#   bash test/test_hook_integration.sh "custom-session-id"

SESSION_ID="${1:-hook-test-$(date +%s)}"
SCRIPT="MCPServer/notify.sh"

cd "$(dirname "$0")/.."

# Simulate the JSON payload Claude Code sends to Stop hooks
HOOK_JSON=$(cat <<EOF
{
  "session_id": "$SESSION_ID",
  "transcript_path": "~/.claude/projects/test/fake-transcript.jsonl",
  "cwd": "$(pwd)",
  "permission_mode": "default",
  "hook_event_name": "Stop",
  "stop_hook_active": false,
  "last_assistant_message": "I have completed the task."
}
EOF
)

echo "=== Hook integration test ==="
echo "Simulating Claude Code Stop hook payload:"
echo "$HOOK_JSON" | python3 -m json.tool 2>/dev/null || echo "$HOOK_JSON"
echo ""

echo "Running notify.sh..."
echo "$HOOK_JSON" | bash "$SCRIPT"
EXIT_CODE=$?

echo ""
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "PASS: notify.sh exited with code 0"
  echo "Session ID '$SESSION_ID' was broadcast to all subnets."
  echo "Check your phone for a notification."
else
  echo "FAIL: notify.sh exited with code $EXIT_CODE"
  exit 1
fi
