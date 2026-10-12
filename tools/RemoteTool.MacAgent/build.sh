#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
agent_bundle="${1:-$PWD/build/RemoteToolMacAgent.app}"
mkdir -p "$agent_bundle/Contents/MacOS"
cp Info.plist "$agent_bundle/Contents/Info.plist"
xcrun swiftc -parse-as-library -swift-version 5 -target "$(uname -m)-apple-macosx12.3" main.swift -o "$agent_bundle/Contents/MacOS/RemoteToolMacAgent" -framework AppKit -framework ScreenCaptureKit -framework CoreImage -framework ApplicationServices
codesign --force --sign "${MAC_AGENT_SIGN_IDENTITY:--}" "$agent_bundle"
echo "Built: $agent_bundle"
