#!/bin/bash
sed -i '/when (val privileged = installer.tryPrivilegedInstall(pkg)) {/,/}/c\
                installer.installPackage(pkg)\
            }\
            val v = _b ?: return@launch\
            when (result) {\
                is InstallResult.StagedRebootingToRecovery ->\
                    setHero(R.drawable.ic_status_available, "Staged", "Rebooting to recovery to apply…")\
                is InstallResult.AppliedBackgroundRebootRequired -> {\
                    setHero(R.drawable.ic_status_available, "Installed", "Update applied successfully. Please reboot.")\
                    v.btnDownload.text = "Reboot"\
                    v.btnDownload.isEnabled = true\
                    v.btnDownload.setOnClickListener { RootManager.exec("reboot") } // Optional reboot shortcut\
                }\
                is InstallResult.Failed -> {\
                    setHero(R.drawable.ic_status_error, "Install failed", result.why)\
                    v.btnDownload.isEnabled = true\
                }' app/src/main/java/com/Zerodactyl/skynight/ui/CheckUpdateFragment.kt
