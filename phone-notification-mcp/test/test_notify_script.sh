#!/bin/bash
# Unit tests for MCPServer/notify.sh
# Verifies JSON parsing and exit behavior.
#
# Usage: bash test/test_notify_script.sh

SCRIPT="MCPServer/notify.sh"
PASS=0
FAIL=0

cd "$(dirname "$0")/.."

run_test() {
  local name="$1"
  local input="$2"
  local expected_exit="$3"

  actual_exit=0
  echo "$input" | bash "$SCRIPT" || actual_exit=$?

  if [ "$actual_exit" -eq "$expected_exit" ]; then
    echo "  PASS: $name (exit=$actual_exit)"
    ((PASS++))
  else
    echo "  FAIL: $name (expected exit=$expected_exit, got exit=$actual_exit)"
    ((FAIL++))
  fi
}

echo "=== notify.sh unit tests ==="
echo ""

# Test 1: normal JSON
run_test "Normal JSON with session_id" \
  '{"session_id":"abc-123","stop_hook_active":false}' 0

# Test 2: JSON with whitespace around colon
run_test "JSON with spaces around colon" \
  '{"session_id" : "spaced-456"}' 0

# Test 3: missing session_id field
run_test "Missing session_id exits cleanly" \
  '{"other_field":"value"}' 0

# Test 4: empty input
run_test "Empty input exits cleanly" \
  '' 0

# Test 5: malformed input
run_test "Malformed input exits cleanly" \
  'not json at all' 0

# Test 6: session_id is empty string
run_test "Empty session_id string exits cleanly" \
  '{"session_id":""}' 0

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
