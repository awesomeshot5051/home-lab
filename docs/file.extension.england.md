# Server Documentation: file.extension.england

## System Overview
* **Hostname:** file.extension.england
* **Primary IP:** 192.168.0.110
* **Virtual IP (vIP):** 192.168.0.111
* **Primary Role:** High-Availability Network Services (Secondary DNS/Pi-hole) and Backup Storage Target.

---

## Network & Security Services
This server provides redundancy for the core network while introducing a security layer for ad-blocking and tracking prevention.

| Service | Systemd Unit | Description |
| :--- | :--- | :--- |
| **Secondary DNS** | `named.service` | BIND9 instance acting as the secondary authority for the local domain. |
| **Pi-hole FTL** | `pihole-FTL.service` | DNS-level ad-blocking and network-wide privacy protection. |
| **iSCSI Initiator** | `iscsid.service` | Manages connections to remote iSCSI targets. |

---

## Storage & File Management
Acts as the secondary storage node, housing backups for the primary file server data.

* **Samba (SMB/CIFS):** `smbd.service` and `nmbd.service` facilitate local network access to backup volumes.
* **File Browser:** `filebrowser.service` provides a web-based interface for managing backup archives (Port 8080).
* **Hardware Optimization:** Uses `ethtool` via crontab at reboot to disable Energy Efficient Ethernet (EEE) on the `eno1` interface, ensuring consistent network performance and preventing link drops.

---

## Automation and Power Management
The server follows the global power-saving policy while maintaining specific overrides for reliability.

### Systemd Power Logic
* **nosleep.service:** A specialized service designed to prevent the system from entering sleep states during critical operations.

### Scheduled Tasks (Crontab)
* **Nightly Suspend:** (00:00) `nightly_suspend.sh`.
* **Hardware Tuning:** (@reboot) Disables EEE on the primary NIC to ensure stability.
* **Morning Maintenance:** (08:05) `morning_maintaince.sh`. Runs connectivity checks and package audits.
* **Network Sync:** (10:08) `applyNetplan`. Ensures network configuration consistency post-wake.

---

## Network Configuration and Listening Ports


* **Port 53 (TCP/UDP):** DNS / Pi-hole Filtering.
* **Port 80 / 443 (TCP):** Pi-hole Admin Console and Web Routing.
* **Port 8080 (TCP):** File Browser interface.
* **Port 139 / 445 (TCP):** Samba / NetBIOS backup shares.
* **Port 22 (TCP):** SSH Administrative Access.
* **Port 953 (TCP):** BIND Remote Control Channel (RNDC).

---

## Configuration Paths (Sensitive Data)
The following directories contain critical configurations. Files within these paths are excluded from version control via `.gitignore`.

* **Pi-hole Configurations:** `/etc/pihole/`
* **BIND9 (Secondary) Configs:** `/etc/bind/`
* **Samba Configuration:** `/etc/samba/smb.conf`
* **Custom Maintenance Scripts:** `/usr/local/bin/`
