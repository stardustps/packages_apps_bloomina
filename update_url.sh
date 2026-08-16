#!/bin/bash
sed -i 's|private const val OTA_BASE = "https://raw.githubusercontent.com/Luminous418/skynight-app/refs/heads/main/updater"|private const val OTA_BASE = "https://over-the-air.tuong.qzz.io/bloomina"|g' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt
sed -i 's|val DEFAULT_JSON_URL: String get() = "$OTA_BASE/${DeviceInfo.deviceCodename}.json"|val DEFAULT_JSON_URL: String get() = "$OTA_BASE/${DeviceInfo.romName}/${DeviceInfo.deviceCodename}.json"|g' app/src/main/java/com/Zerodactyl/bloomina/ui/CheckUpdateFragment.kt
