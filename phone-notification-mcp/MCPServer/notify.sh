#!/bin/bash
# Claude Code Stop hook — sends UDP unicast to LAN devices
# Unicast works across NAT/bridge subnets unlike broadcast

python3 -c "
import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
for i in range(1,21):s.sendto(b'done',('192.168.2.'+str(i),19876))
" 2>/dev/null

exit 0
