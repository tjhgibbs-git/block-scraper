#!/usr/bin/env bash
# Installs what a headless Minecraft client needs on Debian/Ubuntu: Xvfb, Mesa (llvmpipe GL, lavapipe Vulkan)
# and the X11/GL libraries LWJGL/SDL load at runtime. Run as root or with sudo available.
set -euo pipefail

SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi

$SUDO apt-get update -q
$SUDO apt-get install -y -q --no-install-recommends \
	xvfb xauth \
	mesa-utils libgl1 libgl1-mesa-dri libegl1 libgles2 libglx-mesa0 mesa-vulkan-drivers libvulkan1 \
	libx11-6 libxext6 libxrandr2 libxi6 libxcursor1 libxinerama1 libxxf86vm1 libxss1 libxtst6 libxkbcommon0 libxkbcommon-x11-0 \
	libasound2t64 libpulse0 2>/dev/null || \
$SUDO apt-get install -y -q --no-install-recommends \
	xvfb xauth \
	mesa-utils libgl1 libgl1-mesa-dri libegl1 libgles2 libglx-mesa0 mesa-vulkan-drivers libvulkan1 \
	libx11-6 libxext6 libxrandr2 libxi6 libxcursor1 libxinerama1 libxxf86vm1 libxss1 libxtst6 libxkbcommon0 libxkbcommon-x11-0 \
	libasound2 libpulse0

echo "OpenGL under Xvfb:"
xvfb-run -a -s "-screen 0 1920x1080x24" glxinfo -B | grep -E "OpenGL (renderer|core profile version)" || true
