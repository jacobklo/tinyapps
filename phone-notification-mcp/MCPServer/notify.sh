#!/bin/bash
# Claude Code Stop hook — broadcasts session ID via UDP
# Uses python3 for SO_BROADCAST socket support

python3 -c "
import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)
for a in['192.168.0.255','192.168.1.255','192.168.2.255']:s.sendto(b'done',(a,19876))
" 2>/dev/null

exit 0
