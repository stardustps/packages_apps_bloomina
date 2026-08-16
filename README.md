# bloomina updater

a native ota updater for unofficial LineageOS-based roms. 

built with standard **Material 3 Expressive** design, it perfectly mimics the clean, stock android settings aesthetic. unlike standard root-based updaters, bloomina is designed to be built directly inline with your rom's source code as a privileged system app.

## features

- **Material 3 design:** fully integrated m3 dynamic colors and google sans typography.
- **native A/B support:** uses `android.os.updateengine` to seamlessly install `payload.bin` updates in the background on seamless a/b devices.
- **native A-only support:** uses `android.os.recoverysystem` to securely verify, stage, and flash traditional recovery zips.
- **no root required:** operates entirely using native AOSP system permissions. 
- **dependency-free:** networking and json parsing are handled strictly through native `HttpURLConnection` and `org.json`, eliminating the need for bulky prebuilt libraries (like okhttp or gson) in your rom tree.

---

## how to add to your rom source tree

because bloomina is configured with an `Android.bp`, adding it to your AOSP/LineageOS tree is simple.

### 1. clone the repository
clone this repository into the standard `packages/apps` directory in your rom source tree:
```bash
cd /path/to/your/rom/source
git clone https://github.com/stardustps/packages_apps_bloomina.git packages/apps/bloomina
```

*(alternatively, add it to your local manifest `.repo/local_manifests/roomservice.xml` so `repo sync` pulls it automatically).*

### 2. include the package in your build
add the package to your device's makefile (e.g., `device/brand/codename/device.mk` or `lineage_codename.mk`):

```makefile
# include bloomina updater
PRODUCT_PACKAGES += \
    bloomina
```

### 3. build your rom
once the package is declared, standard builds will pick it up automatically:
```bash
source build/envsetup.sh
breakfast codename
mka bacon
```

## permissions
the app is built as a privileged system app. the required `privapp-permissions-bloomina.xml` is automatically included and deployed by the `Android.bp` build rules, granting the app the `reboot` and `recovery` permissions necessary to trigger native ota flashes.

### 4. selinux policies
to ensure the app has the proper selinux contexts to write to recovery and access the update engine natively (replacing the old magisk helper), add the included sepolicy directory to your device's `BOARD_SEPOLICY_DIRS` in your `BoardConfig.mk`:

```makefile
BOARD_SEPOLICY_DIRS += packages/apps/bloomina/sepolicy
```

## system properties configuration
to make bloomina work seamlessly with your rom, you must configure a few build properties in your device tree.

add the following lines to your `device.mk` (or `lineage_codename.mk`):

```makefile
# bloomina updater properties
PRODUCT_PROPERTY_OVERRIDES += \
    ro.bloomina.rom=lineage \
    ro.bloomina.maintainer="your name here" \
    ro.bloomina.rom.ver=$(lineage_version) \
    ro.bloomina.rom.ver.code=$(date +%Y%m%d)
```

### what these properties do:
- `ro.bloomina.rom`: this dictates the rom name injected into the ota url (e.g. `lineage`, `lunaris`, `bliss`). if set to `lineage`, the app will query `https://over-the-air.tuong.qzz.io/bloomina/lineage/device.json`.
- `ro.bloomina.maintainer`: the name of the official maintainer for this device. this populates the "maintainer" tab in the app automatically.
- `ro.bloomina.rom.ver`: the human-readable version of the rom currently installed on the device (e.g., `21.0-20260816-unofficial`).
- `ro.bloomina.rom.ver.code`: a numeric integer used by the app to natively compare if the online update is newer than the installed update (usually a datecode).
---

## removing the stock lineageos updater

bloomina and the stock lineageos updater (the `Updater` app, `org.lineageos.updater`, built from `packages/apps/Updater`) both register `android.settings.SYSTEM_UPDATE_SETTINGS`, so only one can own the system update entry. to use bloomina (or to strip the stock updater entirely), delete `Updater` from the build.

### 1. drop it from PRODUCT_PACKAGES
lineageos adds `Updater` from a common makefile (`vendor/lineage/config/common.mk` / `lineage.mk`), not your device makefile — so you can't simply "not add" it. exclude it from your device tree instead:

```makefile
# remove the stock lineageos updater (supported on modern trees)
PRODUCT_PACKAGES_DELENDED += \
    Updater
```

if your tree is older and does not support `PRODUCT_PACKAGES_DELENDED`, filter it out directly:

```makefile
PRODUCT_PACKAGES := $(filter-out Updater,$(PRODUCT_PACKAGES))
```

*(some lineageos-based roms rename the module — e.g. `ArrowUpdater`, `crDroidUpdater`, `DerpUpdater`. grep your `vendor/*/config/*.mk` and `packages/apps` for the updater module name and substitute it above.)*

### 2. (optional) remove its sepolicy / privapp
the stock updater carries its own selinux rules and `privapp-permissions`. leftover entries are ignored once the package is gone, but for a clean tree you can drop its `BOARD_SEPOLICY_DIRS` entry and privapp xml if your device makefile referenced them explicitly. this is not required.

### 3. rebuild and reflash
```bash
source build/envsetup.sh
breakfast codename
mka bacon
```
after flashing, the stock `Updater` is no longer installed. the **settings → about phone → system update** entry will now be claimed by bloomina (if you added it per the steps above). if you removed the updater without installing bloomina, that menu item simply won't resolve to any app.
