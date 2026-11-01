# Vortex Launcher
1. Add your own `vortex-logo.png` (square, 256x256+) and `vortex-client-*.jar` (e.g. `vortex-client-1.0.jar`) to this folder.
2. Create a GitHub repo and upload everything in this folder (keep `.github/workflows/build.yml`).
3. Open the repo's **Actions** tab, run **Build Vortex Launcher** (or just push).
4. Download the `VortexLauncher-windows` artifact, unzip it, run `VortexLauncher\VortexLauncher.exe` (Java is bundled).
5. For a permanent download link, push a tag such as `v1.0` and grab the files from the Releases page.

Local run (JDK 21): `java VortexLauncher.java`
