#!/bin/bash
# Manual test: sends a UDP broadcast to all subnets.
# Run this and check your Android phone for a notification.
#
# Usage:
#   bash test/test_broadcast.sh
#   bash test/test_broadcast.sh "my-custom-session-id"
#
# Monitor on phone with: adb logcat -s ClaudeNotify

SESSION_ID="${1:-test-session-$(date +%s)}"

echo "Broadcasting session ID: $SESSION_ID"
echo "Port: 19876"
echo "Targets: 192.168.0.255, 192.168.1.255, 192.168.2.255"
echo ""

python3 -c "
import socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
for addr in ['192.168.0.255', '192.168.1.255', '192.168.2.255']:
    try:
        sock.sendto(b'$SESSION_ID', (addr, 19876))
        print(f'  Sent to {addr}:19876')
    except Exception as e:
        print(f'  Failed {addr}:19876 - {e}')
sock.close()
"

echo ""
echo "Done. Check your phone for a notification."
echo "Logcat: adb logcat -s ClaudeNotify"
