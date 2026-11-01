#!/bin/sh
set -eu
# Install both families atomically before declaring readiness. An internal Docker
# network alone would still permit lateral connections to application services.
for restore in iptables-restore ip6tables-restore; do
    "$restore" <<'RULES'
*filter
:INPUT DROP [0:0]
:FORWARD DROP [0:0]
:OUTPUT DROP [0:0]
-A INPUT -m conntrack --ctstate ESTABLISHED -j ACCEPT
-A INPUT -p tcp --dport 8443 -m conntrack --ctstate NEW -j ACCEPT
-A OUTPUT -m conntrack --ctstate ESTABLISHED -j ACCEPT
-A OUTPUT -o lo -p tcp --dport 8443 -m conntrack --ctstate NEW -j ACCEPT
COMMIT
RULES
done
# There is deliberately no general loopback exception: Docker's DNS resolver
# at 127.0.0.11 must not provide a query-based outbound channel for the parser.
touch /tmp/firewall-ready
exec sleep infinity
