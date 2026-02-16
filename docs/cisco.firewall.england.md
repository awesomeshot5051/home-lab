# Server Documentation: cisco.firewall.england

## System Overview
* **Hostname:** cisco.firewall.england
* **IP Address:** 192.168.0.1 (Internal Gateway)
* **OS:** FreeBSD 14.3-RELEASE-p7 (OPNsense 25.7 Stable)
* **Architecture:** amd64
* **Primary Role:** Stateful Firewall, NAT Gateway, and Perimeter Security.

---

## Network Interfaces
* **em0 (WAN):** 192.168.1.222 (Upstream Gateway: 192.168.1.254)
* **em1 (LAN):** 192.168.0.1 (Local Network: 192.168.0.0/24)

---

## Core Security Services (BSD rc.d)
The following critical daemons are active, providing the backbone of the OPNsense infrastructure:

| Service | Component | Description |
| :--- | :--- | :--- |
| **pf** | Packet Filter | The core firewall engine managing stateful inspection. |
| **unbound** | DNS Resolver | Hardened recursive DNS server for the gateway. |
| **dnsmasq** | DNS/DHCP Forwarder | Lightweight DNS forwarder providing local resolution support. |
| **suricata** | IDS/IPS | High-performance Network IDS, IPS, and Security Monitoring. |
| **syslog-ng** | Logging | Enhanced system logging daemon for advanced audit trails. |
| **sshd** | OpenSSH | Secure remote administration on Port 22. |
| **lighttpd** | Web GUI | Serves the OPNsense administrative web interface (Ports 80/443). |

---

## Port Forwarding & NAT Configuration
The gateway handles several critical redirections to internal lab servers:

### Ingress Port Mapping (RDR)
| External Port | Protocol | Internal Target | Target Port | Service |
| :--- | :--- | :--- | :--- | :--- |
| **1194** | TCP/UDP | 192.168.0.140 | 1194 | OpenVPN Tunnel |
| **12222** | TCP/UDP | 192.168.0.140 | 12222 | WolAuthServer |
| **46317** | TCP/UDP | 192.168.0.143 | 46317 | DB Server (Encrypted Data) |

### Outbound NAT
* **Standard Masquerade:** All internal traffic from `192.168.0.0/24` is NAT'ed through `em0`.
* **Static-Port NAT:** Specifically configured for `isakmp` (Port 500) to ensure VPN/IPsec compatibility.

---

## Security Policy & Rulesets (pf)
* **Anti-Spoofing:** Strict `block` rules prevent traffic from unauthorized internal IPs on the WAN interface.
* **Automated Lockouts:** * `<sshlockout>` table: Automatically drops traffic to SSH/HTTPS ports from repeat offenders.
    * `<virusprot>` table: Drops all traffic from known malicious sources.
    * `<bogons>` table: Blocks unassigned and private IP space on the WAN.
* **ICMP Policy:** Explicitly allows essential IPv6-ICMP types (Neighbor Discovery, Router Advertisement) for network health.

---

## Maintenance & Monitoring
* **Log Management:** `syslog-ng` and `newsyslog` manage rotation and archiving.
* **Traffic Graphing:** `rrdcached` maintains Round Robin Databases for historical performance analysis.
* **Health Checks:** A custom `ping_hosts.sh` script runs every 4 minutes via cron to verify the availability of critical lab nodes.

---

## Critical Diagnostic Commands
* **Verify Listening Sockets:** `sockstat -4 -l`
* **Inspect Filter Rules:** `pfctl -sr`
* **Inspect NAT Rules:** `pfctl -sn`
* **Check Service Status:** `service -e`
