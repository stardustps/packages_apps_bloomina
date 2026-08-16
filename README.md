# bloomina updater

a native ota updater for unofficial lineageos-based roms. 

built with standard **material 3 expressive** design, it perfectly mimics the clean, stock android settings aesthetic. unlike standard root-based updaters, bloomina is designed to be built directly inline with your rom's source code as a privileged system app.

## features

- **material 3 design:** fully integrated m3 dynamic colors and google sans typography.
- **native a/b support:** uses `android.os.updateengine` to seamlessly install `payload.bin` updates in the background on seamless a/b devices.
- **native a-only support:** uses `android.os.recoverysystem` to securely verify, stage, and flash traditional recovery zips.
- **no root required:** operates entirely using native aosp system permissions. 
- **dependency-free:** networking and json parsing are handled strictly through native `httpurlconnection` and `org.json`, eliminating the need for bulky prebuilt libraries (like okhttp or gson) in your rom tree.

---

## how to add to your rom source tree

because bloomina is configured with an `android.bp`, adding it to your aosp/lineageos tree is simple.

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
product_packages += \
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
the app is built as a privileged system app. the required `privapp-permissions-bloomina.xml` is automatically included and deployed by the `android.bp` build rules, granting the app the `reboot` and `recovery` permissions necessary to trigger native ota flashes.

### 4. selinux policies
to ensure the app has the proper selinux contexts to write to recovery and access the update engine natively (replacing the old magisk helper), add the included sepolicy directory to your device's `board_sepolicy_dirs` in your `boardconfig.mk`:

```makefile
board_sepolicy_dirs += packages/apps/bloomina/sepolicy
```

## system properties configuration
to make bloomina work seamlessly with your rom, you must configure a few build properties in your device tree.

add the following lines to your `device.mk` (or `lineage_codename.mk`):

```makefile
# bloomina updater properties
product_property_overrides += \
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
