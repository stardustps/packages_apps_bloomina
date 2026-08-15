#!/system/bin/sh
# Runs late (after boot). Fixes ownership/permissions once the data partition is fully up,
# so skynight can read/write its staging area regardless of the multi-user path.
MODDIR=${0%/*}

# libsu spawns our commands as root at flash time; this just keeps the staging dir sane.
if [ -d /data/media/0/skynight ]; then
  chmod 0771 /data/media/0/skynight 2>/dev/null
fi

# Re-assert the live SELinux rules in case a policy reload dropped them (KernelSU/Magisk safe).
if command -v magiskpolicy >/dev/null 2>&1; then
  magiskpolicy --live "allow untrusted_app cache_file file { create open write getattr }" 2>/dev/null
fi
