# Server Documentation: file.server.england

## System Overview
* **Hostname:** file.server.england
* **IP Address:** 192.168.0.139
* **Primary Role:** Centralized Storage (NAS), Block Storage (iSCSI), and Web-based File Management.

---

## Storage Architecture
The server manages a hybrid storage array totaling approximately 6.5 TB of raw capacity.

* **High-Performance Tier:** 2.5 TB SSD storage, managed via ZFS for data integrity and snapshot capabilities.
* **Mass Storage Tier:** 4 TB External HDD storage.
* **Pool Management:** Utilizes ZFS (`zfs-zed.service`) for the primary "storage" pool.
* **Protocols:**
    * **Samba (SMB/CIFS):** Managed by `smbd` and `nmbd` for cross-platform file sharing.
    * **iSCSI:** Managed by `tgt.service` on Port 3260 for block-level storage presentation.
    * **Web/HTTPS:** Caddy acts as a reverse proxy/web server, with File Browser providing a GUI (Ports 80, 8080, 8081).

---

## Automation and Backup Logic
This server employs a specialized backup suite in `/usr/local/bin` to manage data redundancy while navigating the system's aggressive power-saving (suspend) schedule.

### Systemd Timers & Services
The backup lifecycle is orchestrated by systemd rather than standard cron, allowing for better logging and dependency management.

| Timer | Service | Schedule | Function |
| :--- | :--- | :--- | :--- |
| **fileserver-backup.timer** | `fileserver-backup.service` | 23:00 Daily | Triggers the primary file server backup logic. |
| **dpkg-db-backup.timer** | `dpkg-db-backup.service` | 00:00 Daily | Backs up the dpkg database for system recovery. |

### Backup Script Orchestration
* **backup_runner:** The primary engine. It calculates the day of the year to maintain a 3-day interval. It creates a ZFS snapshot, disables system suspension, and pipes a compressed stream (`zstd -19`) to a mounted Samba share at `/mnt/backup_data`.
* **backup_watchdog:** Monitors long-running `zfs send` processes. If a backup exceeds 30 minutes, it automatically extends the `disablesuspend` lock to prevent data corruption.
* **backup_wrapper:** Uses `systemd-inhibit` to provide a kernel-level lock against sleep states while `backup_fileserver` (the legacy tar-based backup) is active.
* **compareDir:** A custom utility for verifying file integrity, metadata, and SHA-256 hashes between source and destination directories.

### Maintenance Schedule (Cron)
* **Nightly Suspend:** (00:01) `nightly_suspend.sh`.
* **Morning Maintenance:** (05:08) `morning_maintenance.sh`. Includes network connectivity checks and automated package audits via `daily_apt_check.sh`.

---

## Network Configuration and Listening Ports
* **Port 445 / 139 (TCP):** Samba / NetBIOS File Sharing.
* **Port 3260 (TCP):** iSCSI Target Daemon (`tgt`).
* **Port 80 (TCP):** HTTP (Caddy).
* **Port 8080 / 8081 (TCP):** File Browser Web Interface.
* **Port 22 (TCP):** SSH Administrative Access.

---

## Critical Configuration Paths
The following paths are tracked for structural reference. Actual data is excluded via `.gitignore`.

* **ZFS Pool Configuration:** Managed via `zpool` and `zfs` utilities.
* **Samba Configuration:** `/etc/samba/smb.conf`
* **systemd Units:** `/etc/systemd/system/fileserver-backup.*`
* **Caddyfile:** Configuration for web routing and reverse proxying.
