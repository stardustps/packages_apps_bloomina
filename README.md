# skynight OTA

OTA Updater for the **skynight** custom ROM (Samsung Galaxy A32 4G). 

Built with an authentic OneUI 8 design. Includes an Android app for fetching updates and a Magisk/KernelSU helper module for A-only recovery staging.

## Building

Requires a GitHub PAT with `read:packages` to fetch OneUI design libraries.

```bash
export GPR_USER="your-github-username"
export GPR_TOKEN="your-pat-token"

./gradlew :app:assembleRelease
```

## Maintainer
- **ncatt/Zerodactyl**: Main developer
