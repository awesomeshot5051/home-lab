# search.server.england
### System Overview
Self-hosted SearXNG instance and primary search gateway. Tracks OpenVPN configurations and search engine orchestration.

---

## 📂 Repository Contents
* **/systemd/**: Custom unit files and service orchestration.
* **/scripts_mirror/**: Automated sync of /usr/local/bin (Custom tools & logic).
* **/ssh/**: SSHD hardening and configurations (Sensitive keys excluded).
* **lynis-suggestions.md**: Automated nightly security audit reports.

---

## 🤖 Automated Sync Logic
This branch is automatically updated every night at **23:55** via a crontab trigger. 
1. **Audit:** System-wide security scan via Lynis.
2. **Mirror:** /usr/local/bin is synced to /etc/scripts_mirror/.
3. **Commit:** Changes are versioned via etckeeper.
4. **Push:** Pushed directly to GitHub via SSH.
