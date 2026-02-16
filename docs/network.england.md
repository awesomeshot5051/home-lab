# Server Documentation: network.england

## System Overview
* **Hostname:** network.england
* **IP Address:** 192.168.0.197
* **Primary Role:** Core Network Services (DNS, DHCP, and Virtual Switching)

---

## Core Infrastructure Services
This node serves as the authority for the internal network's identity and addressing.

| Service Group | Systemd Unit | Description |
| :--- | :--- | :--- |
| **DNS (Primary)** | `named.service` | BIND9 Domain Name Server handling internal name resolution. |
| **DHCPv4** | `kea-dhcp4-server.service` | Kea IPv4 DHCP daemon for dynamic address assignment. |
| **DHCPv6** | `kea-dhcp6-server.service` | Kea IPv6 DHCP daemon. |
| **DHCP Management** | `kea-ctrl-agent.service` | Control agent for managing Kea DHCP services. |
| **DDNS** | `kea-dhcp-ddns-server.service` | Handles dynamic DNS updates between Kea and BIND. |

---

## Network Virtualization and Discovery
* **Open vSwitch:** `ovs-vswitchd.service` and `ovsdb-server.service` manage software-defined networking and bridging within the host.
* **Samba:** `smbd.service` and `nmbd.service` provide NetBIOS name resolution and SMB/CIFS support.
* **Avahi:** `avahi-daemon.service` provides mDNS/DNS-SD for ZeroConf networking.

---

## Automation and Maintenance
* **Nightly Suspend:** (00:00) `nightly_suspend.sh`. 
* **Infrastructure Wake:** (22:50) `wake_db_server`. Triggers a Wake-on-LAN event for the database server.
* **Consolidated Maintenance:** (08:05) `morning_maintenance.sh`. Handles ping checks and package audits.

---

## Configuration Paths (Sensitive Data)
The following directories contain the live configurations for this server. While these paths are tracked for structural documentation, the files themselves are excluded from version control to protect network privacy.

* **BIND9 (DNS) Configs:** `/etc/bind/`
    * Contains forward and reverse lookup zone files for `england.local`.
* **Kea (DHCP) Configs:** `/etc/kea/`
    * Contains JSON configuration files for DHCPv4/v6 scopes and reservations.
* **Open vSwitch:** `/etc/openvswitch/`
* **Samba Config:** `/etc/samba/smb.conf`
