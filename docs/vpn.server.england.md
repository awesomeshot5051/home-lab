# 🛡️ Server Documentation: vpn.server.england

## 📊 System Overview
* **Hostname:** vpn.server.england
* **IP Address:** 192.168.0.140
* **Operating System:** Ubuntu 24.04.3 LTS (Noble Numbat)
* **Primary Role:** Secure VPN Gateway & Internal Infrastructure Controller (WoL)

---

## 🛠️ Core Services
| Service | Systemd Unit | Description |
| :--- | :--- | :--- |
| **OpenVPN** | `openvpn-server@server.service` | Global encrypted access to the 192.168.0.x network. |
| **SearXNG** | `searxng.service` | Privacy-centric metasearch engine (Containerized). |
| **WolAuthServer** | `wolauthserver.service` | Proprietary Java-based authentication for network wake-ups. |
| **Containerd** | `containerd.service` | Runtime for Docker-based services. |
| **SSH** | `ssh.service` | Remote administrative access (Port 22). |

---

## 🔐 Proprietary: WolAuthServer
The **WolAuthServer** serves as a secure gatekeeper for waking internal hardware.

* **Network:** Listens on TCP Port **12222**.
* **Logic:** Uses an RSA-2048 challenge-response mechanism to verify client identity.
* **Security:** Features automated hourly key rotation with a 2-hour grace period for previous keys to prevent session drops.
* **Target:** Primary trigger for waking the DB Server at `192.168.0.143`.
* **Data Path:** `/home/awesomeshot5051/wolauth/`

---

## ⚙️ Automation & Maintenance
Scheduled tasks are managed via `crontab` and custom scripts in `/usr/local/bin/`.

### Power Management
* **Nightly Suspend:** (00:01) Triggers `nightly_suspend.sh`. Puts server into RAM sleep (`rtcwake`) for 8 hours.
* **Persistence Lock:** `disablesuspend` creates `/tmp/suspend_disabled` to bypass the sleep cycle.
* **Manual Control:** `suspendfor` allows for temporary, timed suspension of the server.

### System Health
* **Maintenance:** (05:08) `morning_maintaince.sh` runs general cleanup tasks.
* **Search Engine:** (05:08) `restartSearxng` ensures the Docker container is refreshed daily.
* **Network Stability:** (05:08) `applyNetplan` reapplies network configurations post-wake.
* **Updates:** `daily_apt_check.sh` monitors package status and updates `/tmp/apt_upgradable_count`.

### WoL Scripts
* **`wake_db_server`**: Targets the DB Host at `192.168.0.143`.
* **`wake_pc`**: Secondary broadcast for authorized desktop wake-up.

---

## 📡 Networking & Ports
* **Port 12222:** WolAuthServer (Internal Registration/Auth).
* **Port 8080:** SearXNG Web Interface.
* **Port 22:** Standard SSH.
* **Port 53:** Systemd-resolved DNS.
