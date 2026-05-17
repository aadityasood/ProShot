#!/bin/bash
# ==============================================================================
# ProShot — Android Development Environment Setup Script
# System: Fedora 43 (x86_64)
#
# This script installs everything needed to build ProShot:
#   - JDK 21 (AGP 8.x requires JDK 17+; Fedora 43 ships 21 as lowest)
#   - Android SDK command-line tools
#   - Android SDK Platform 35, Build Tools, NDK, CMake, Platform Tools
#   - Environment variables in ~/.bashrc
#
# Usage:
#   chmod +x setup-android-env.sh
#   ./setup-android-env.sh
#
# After running, restart your terminal or run: source ~/.bashrc
# ==============================================================================

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

SDK_ROOT="$HOME/Android/Sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
NDK_VERSION="27.2.12479018"
CMAKE_VERSION="3.22.1"
BUILD_TOOLS_VERSION="35.0.0"
PLATFORM_VERSION="android-35"

print_header() {
    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║${NC}  ${BOLD}$1${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════════════════════╝${NC}"
}

print_step() {
    echo -e "${BLUE}▸${NC} $1"
}

print_ok() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

# ==============================================================================
print_header "ProShot — Android Environment Setup"
echo ""
echo -e "  This will install the Android development toolchain on your system."
echo -e "  ${YELLOW}You will be prompted for sudo password for JDK 21 installation.${NC}"
echo ""
read -p "  Press Enter to continue (Ctrl+C to cancel)... "

# ==============================================================================
print_header "Step 1/5 — Install JDK 21"

if [ -d "/usr/lib/jvm/java-21-openjdk" ]; then
    print_ok "JDK 21 already installed at /usr/lib/jvm/java-21-openjdk"
else
    print_step "Installing java-21-openjdk-devel..."
    sudo dnf install -y java-21-openjdk-devel
    if [ -d "/usr/lib/jvm/java-21-openjdk" ]; then
        print_ok "JDK 21 installed successfully"
    else
        print_error "JDK 21 installation failed!"
        exit 1
    fi
fi

# Verify
JDK21_VERSION=$(/usr/lib/jvm/java-21-openjdk/bin/java -version 2>&1 | head -1)
print_ok "JDK 21: $JDK21_VERSION"

# ==============================================================================
print_header "Step 2/5 — Download Android Command-Line Tools"

if [ -f "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    print_ok "Android command-line tools already installed"
else
    print_step "Creating SDK directory at $SDK_ROOT..."
    mkdir -p "$SDK_ROOT/cmdline-tools"

    TEMP_DIR=$(mktemp -d)
    print_step "Downloading command-line tools..."
    wget -q --show-progress -O "$TEMP_DIR/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"

    print_step "Extracting..."
    unzip -q "$TEMP_DIR/cmdline-tools.zip" -d "$TEMP_DIR"

    # The zip contains a 'cmdline-tools' folder — move it to 'latest'
    if [ -d "$TEMP_DIR/cmdline-tools" ]; then
        mv "$TEMP_DIR/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
        print_ok "Command-line tools installed to $SDK_ROOT/cmdline-tools/latest"
    else
        print_error "Unexpected archive structure. Check the download."
        ls -la "$TEMP_DIR"
        exit 1
    fi

    rm -rf "$TEMP_DIR"
fi

# Verify sdkmanager works
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if "$SDKMANAGER" --version > /dev/null 2>&1; then
    print_ok "sdkmanager version: $($SDKMANAGER --version)"
else
    # Try with explicit JAVA_HOME
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
    if "$SDKMANAGER" --version > /dev/null 2>&1; then
        print_ok "sdkmanager version: $($SDKMANAGER --version) (with JAVA_HOME set)"
    else
        print_error "sdkmanager failed to run. Check Java installation."
        exit 1
    fi
fi

# ==============================================================================
print_header "Step 3/5 — Set Environment Variables"

MARKER="# ===== Android Development Environment ====="

if grep -q "$MARKER" "$HOME/.bashrc" 2>/dev/null; then
    print_warn "Android environment variables already in ~/.bashrc — skipping"
else
    print_step "Adding environment variables to ~/.bashrc..."
    cat >> "$HOME/.bashrc" << EOF

$MARKER
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=\$HOME/Android/Sdk
export ANDROID_SDK_ROOT=\$ANDROID_HOME
export ANDROID_NDK_HOME=\$ANDROID_HOME/ndk/$NDK_VERSION

export PATH=\$JAVA_HOME/bin:\$PATH
export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH
export PATH=\$ANDROID_HOME/platform-tools:\$PATH
export PATH=\$ANDROID_HOME/emulator:\$PATH
# ===== End Android Environment =====
EOF
    print_ok "Environment variables added to ~/.bashrc"
fi

# Apply for this session
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# ==============================================================================
print_header "Step 4/5 — Install SDK Components"

print_step "Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

print_step "Installing platform-tools..."
sdkmanager "platform-tools"

print_step "Installing SDK platform ($PLATFORM_VERSION)..."
sdkmanager "platforms;$PLATFORM_VERSION"

print_step "Installing build-tools ($BUILD_TOOLS_VERSION)..."
sdkmanager "build-tools;$BUILD_TOOLS_VERSION"

print_step "Installing NDK ($NDK_VERSION)..."
sdkmanager "ndk;$NDK_VERSION"

print_step "Installing CMake ($CMAKE_VERSION)..."
sdkmanager "cmake;$CMAKE_VERSION"

print_ok "All SDK components installed"

# ==============================================================================
print_header "Step 5/5 — Verification"

echo ""
PASS=0
FAIL=0

# Java 21
if /usr/lib/jvm/java-21-openjdk/bin/java -version 2>&1 | grep -q "21"; then
    print_ok "JDK 21 .............. PASS"
    ((PASS++))
else
    print_error "JDK 21 .............. FAIL"
    ((FAIL++))
fi

# sdkmanager
if sdkmanager --version > /dev/null 2>&1; then
    print_ok "sdkmanager .......... PASS"
    ((PASS++))
else
    print_error "sdkmanager .......... FAIL"
    ((FAIL++))
fi

# ADB
if "$ANDROID_HOME/platform-tools/adb" version > /dev/null 2>&1; then
    print_ok "adb ................. PASS"
    ((PASS++))
else
    print_error "adb ................. FAIL"
    ((FAIL++))
fi

# Platform
if [ -d "$ANDROID_HOME/platforms/$PLATFORM_VERSION" ]; then
    print_ok "Platform (API 35) ... PASS"
    ((PASS++))
else
    print_error "Platform (API 35) ... FAIL"
    ((FAIL++))
fi

# Build tools
if [ -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION" ]; then
    print_ok "Build Tools ......... PASS"
    ((PASS++))
else
    print_error "Build Tools ......... FAIL"
    ((FAIL++))
fi

# NDK
if [ -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
    print_ok "NDK ($NDK_VERSION) .. PASS"
    ((PASS++))
else
    print_error "NDK ................. FAIL"
    ((FAIL++))
fi

# CMake
if [ -f "$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/cmake" ]; then
    print_ok "CMake ($CMAKE_VERSION) ......... PASS"
    ((PASS++))
else
    print_error "CMake ............... FAIL"
    ((FAIL++))
fi

echo ""
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

if [ $FAIL -eq 0 ]; then
    echo -e "  ${GREEN}${BOLD}All $PASS checks passed! ✓${NC}"
    echo ""
    echo -e "  ${BOLD}Your Android dev environment is ready for ProShot!${NC}"
    echo ""
    echo -e "  ${YELLOW}→ Restart your terminal or run:${NC} source ~/.bashrc"
    echo -e "  ${YELLOW}→ Then tell the AI to scaffold the project!${NC}"
else
    echo -e "  ${RED}${BOLD}$FAIL check(s) failed. Review errors above.${NC}"
    echo -e "  ${GREEN}$PASS check(s) passed.${NC}"
fi

echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
