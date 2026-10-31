#!/bin/bash
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
read -r response <&3
[[ "$response" == 'HTTP/1.1 200 '* ]]
