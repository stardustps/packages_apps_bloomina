# Bloomina Updater

A native OTA Updater for unofficial LineageOS-based ROMs. 

Built with standard **Material 3 Expressive** design, it perfectly mimics the clean, stock Android settings aesthetic. Unlike standard root-based updaters, Bloomina is designed to be built directly inline with your ROM's source code as a Privileged System App.

## Features

- **Material 3 Design:** Fully integrated M3 dynamic colors and Google Sans typography.
- **Native A/B Support:** Uses `android.os.UpdateEngine` to seamlessly install `payload.bin` updates in the background on seamless A/B devices.
- **Native A-Only Support:** Uses `android.os.RecoverySystem` to securely verify, stage, and flash traditional recovery zips.
- **No Root Required:** Operates entirely using native AOSP system permissions. 
- **Dependency-Free:** Networking and JSON parsing are handled strictly through native `HttpURLConnection` and `org.json`, eliminating the need for bulky prebuilt libraries (like OkHttp or Gson) in your ROM tree.

---

## How to add to your ROM source tree

Because Bloomina is configured with an `Android.bp`, adding it to your AOSP/LineageOS tree is simple.

### 1. Clone the repository
Clone this repository into the standard `packages/apps` directory in your ROM source tree:
```bash
cd /path/to/your/rom/source
git clone https://github.com/stardustps/packages_apps_bloomina.git packages/apps/bloomina
```

*(Alternatively, add it to your local manifest `.repo/local_manifests/roomservice.xml` so `repo sync` pulls it automatically).*

### 2. Include the package in your build
Add the package to your device's makefile (e.g., `device/brand/codename/device.mk` or `lineage_codename.mk`):

```makefile
# Include Bloomina Updater
PRODUCT_PACKAGES += \
    BloominaUpdater
```

### 3. Build your ROM
Once the package is declared, standard builds will pick it up automatically:
```bash
source build/envsetup.sh
breakfast codename
mka bacon
```

## Permissions
The app is built as a Privileged System App. The required `privapp-permissions-bloomina.xml` is automatically included and deployed by the `Android.bp` build rules, granting the app the `REBOOT` and `RECOVERY` permissions necessary to trigger native OTA flashes.
