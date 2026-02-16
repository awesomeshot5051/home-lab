# Home Lab Infrastructure: england.local

This repository serves as the central documentation and configuration management for my home lab. It covers the deployment of a segmented network supporting secure remote access, redundant DNS/DHCP, multi-tier storage, and specialized database services.

## Network Topology Overview

The infrastructure is built on a hierarchical model focused on high availability and secure perimeter management.

### 1. Perimeter & Gateway
* **cisco.firewall.england**
    * **Role:** Network Gateway / Edge Firewall.
    * **Description:** Manages the external-to-internal boundary, handling NAT, firewall rules, and initial traffic shaping for the entire `england.local` domain.

* **vpn.server.england** ([View Documentation](./docs/vpn.server.england.md))
    * **Role:** Remote Access Gateway.
    * **Description:** Provides secure internal network connectivity via OpenVPN. It also hosts a custom Java-based authentication server (`WolAuthServer`) for on-demand hardware wake-ups.

### 2. Core Network Services
* **network.england**
    * **Role:** Primary DNS & DHCP.
    * **Description:** Centralized authority for local hostname resolution and IP address management (DHCP) for all lab clients and servers.

* **file.extension.england**
    * **Role:** Network Redundancy & Security.
    * **Description:** Operates as the Secondary DNS and a Pi-hole instance for network-wide ad-blocking. Additionally provides off-site backup parity for the primary storage array.

### 3. Data & Application Layers
* **file.server.england**
    * **Role:** Primary Storage Array.
    * **Description:** Hosts 6TB of usable storage for centralized media and project data, accessible via SMB/NFS protocols across the network.

* **database.server.england**
    * **Role:** Encryption Software Backend.
    * **Description:** Dedicated database host specifically optimized for my proprietary file encryption software suite.

---

## Technical Specifications: Primary Workstation

* **traes-pc**
    * **OS:** Pika OS (Linux) running X11.
    * **Hardware:** AMD Ryzen 9 9950X3D | 32GB DDR5-6000 | NVIDIA RTX 5060 Ti (16GB).
    * **Function:** Primary development environment and management console for the `england.local` network.

---

## Maintenance & Automation Logic
The lab utilizes a "Pull" documentation model. Audit scripts running on individual nodes push their current configuration states to this repository. This ensures that documentation remains an accurate reflection of the live environment, capturing:
* Active systemd services and units.
* Cron-based maintenance schedules.
* Network port listening states and service dependencies.
