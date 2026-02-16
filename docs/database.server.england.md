# Server Documentation: database.server.england

## System Overview
* **Hostname:** database.server.england
* **IP Address:** 192.168.0.143
* **Primary Role:** Backend Data Store for [File Encryption Software](https://englandtechnologies.net) and License Management.
* **Storage Engine:** ZFS (VaultDB Pool) with MariaDB 10.11.

---

## Core Infrastructure Services
This server manages sensitive cryptographic metadata and licensing keys. Due to high-security requirements, several custom controllers manage the application lifecycle.

| Service | Component | Description |
| :--- | :--- | :--- |
| **MariaDB** | `mariadb.service` | Primary SQL database server listening on port 46317. |
| **Fail2Ban** | `fail2ban.service` | Intrusion prevention protecting SSH and database ports. |
| **ZFS Event Daemon** | `zfs-zed.service` | Monitors pool health and automates snapshot events. |
| **License Sync** | `license-db-sync.service` | Automates two-way synchronization between local MariaDB and Aiven Cloud DB. |
| **SSH** | `ssh.service` | Administrative access (Port 22). |

---

## The Heartbeat & Middleman Logic
A specialized coordination system is used to manage the server's availability and listener states. 

* **heartbeat-controller.service**: A Java-based application that manages system state and potential shutdowns.
* **heartbeat.service**: A UDP-based listener providing connectivity signals.
* **heartbeat-middleman.sh**: A critical orchestration script used to switch between the controller and the heartbeat server. It ensures that port 46317 is properly released and re-bound during transitions to avoid bind failures.
* **boot-manager.sh**: Executed during the boot sequence to detect maintenance windows (08:00 and 22:00-23:00). If a window is detected, it preemptively stops the heartbeat-controller to allow for uninterrupted updates and backups.

---

## Data Integrity & Backup Strategy
The database utilizes a tiered ZFS backup approach to ensure zero data loss.

### 1. Local ZFS Snapshots (`db_zfs_backup`)
A nightly cron job at 23:00 executes a differential check. It compares the current state of `vaultdb/mariadb` against the most recent snapshot. A new snapshot is only created if changes are detected, keeping the last three snapshots for recovery.

### 2. Cloud Synchronization (`sync_license_db`)
The system maintains a bidirectional sync with a remote Aiven Cloud MySQL instance. This ensures that license keys generated externally are pulled to the local lab, and local updates are pushed to the cloud.

### 3. Integrity Audits
* **Monthly Scrub:** A zpool scrub is triggered on the 1st of every month at 23:00 for both `vaultdb` and `vaultbackup`.
* **Login Audit:** `zfs_login_check.sh` provides an immediate health and capacity summary of ZFS pools and the `/vaultbackup` mount point upon every SSH login.

---

## Scheduled Operations (Cron)
| Schedule | Command | Function |
| :--- | :--- | :--- |
| **@reboot** | `daily_apt_check.sh` | Initial package audit on startup. |
| **08:20** | `systemctl poweroff` | Automated morning shutdown to conserve resources post-maintenance. |
| **23:00** | `db_zfs_backup` | Differential ZFS snapshot of the MariaDB dataset. |
| **00:00** | `systemctl poweroff` | Primary nightly shutdown. |

---

## Network Configuration and Listening Ports



| Port | Protocol | Service | Note |
| :--- | :--- | :--- | :--- |
| **46317** | **TCP** | MariaDB | Primary Database Listener. |
| **46317*** | **UDP** | Heartbeat | *Subject to Middleman Orchestration. Conflict managed by `heartbeat-middleman.sh`. |
| **22** | **TCP** | SSH | Administrative Access. |
| **53** | **TCP/UDP** | systemd-resolved | Local Name Resolution. |

---

## Critical Configuration Paths
* **MariaDB Data (ZFS):** `/vaultdb/mariadb`
* **Backup Archives:** `/vaultbackup/mariadb_backups`
* **Custom Binaries:** `/usr/local/bin/`
* **Sync Logs:** `/var/log/license_db_sync.log`
* **Backup Logs:** `/var/log/zfs_backup.log`
