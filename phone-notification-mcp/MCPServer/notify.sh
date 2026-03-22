#!/bin/bash
# Claude Code Stop hook — broadcasts session ID via UDP
# Uses python3 for SO_BROADCAST socket support

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | grep -oP '"session_id"\s*:\s*"\K[^"]*')

if [ -z "$SESSION_ID" ]; then
  exit 0
fi

python3 -c "
import socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
for addr in ['192.168.0.255', '192.168.1.255', '192.168.2.255']:
    sock.sendto(b'$SESSION_ID', (addr, 19876))
sock.close()
" 2>/dev/null

exit 0
