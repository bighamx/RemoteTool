package com.chuckiehelper.mobile.nativeui

// Distinct component names prevent the manifest merger from conflating these
// full-only providers with the common diagnostics provider. URI authorities stay stable.
class HermesFileProvider : androidx.core.content.FileProvider()
class AppUpdateFileProvider : androidx.core.content.FileProvider()
