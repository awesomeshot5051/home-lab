#!/bin/bash
export __NV_PRIME_RENDER_OFFLOAD=1
export __GLX_VENDOR_LIBRARY_NAME=nvidia
exec ~/Applications/UltiMaker-Cura-5.12.0-linux-X64_ec24cde3c7863ca57ef0cd8c5805f7b4.AppImage "$@"
