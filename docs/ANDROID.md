# Anubis on PojavLauncher / Android (experimental, untested on device)

## What this fork changes
- `Platform` helper detects Pojav-family launchers (env vars `POJAV*`, `pojav*`/`glfwstub*` properties). Override with `-Danubis.forceAndroid=true` / `-Danubis.forceDesktop=true`.
- ImGui init is wrapped: if the natives can't load, Anubis disables its UI instead of crashing the game. The ClickGUI screen then shows a notice and closes with ESC.
- Borderless-fullscreen mixin is skipped on Android (GLFW window APIs are stubs there).
- The "disable direct state access" mixin now also applies on mobile/translated GL renderers (gl4es, Mali, Adreno, ANGLE, PowerVR).
- AWT-drawn sky textures (SkyEmoji / SkyLyrics / Peep) turn off if `java.awt` is not usable in the launcher's JRE.

## Mobile ClickGUI (works without ImGui)
When ImGui is unavailable, Anubis opens `MobileClickGuiScreen`, built only from vanilla widgets. It drives the same modules and settings, so every module and saved config keeps working.
- Open it from the **Anubis** button (top-left) on the pause menu or title screen, or with the Anubis GUI key if you map one in the launcher controls.
- Tap a category, tap a module name to see its settings, tap the ON/OFF pill to toggle. Drag to scroll, drag a slider to change a number.
- Supported: boolean, mode, number, key, color (tap cycles a preset palette), text (tap to edit), action buttons, module keybind and hold mode.
- Not available yet: block/item/string list editors, the HUD layout editor and profile/theme screens (shown as "(PC only)" or hidden).

## ImGui natives on Android (built automatically)
The whole UI (ClickGUI, HUD, main menu) is drawn with imgui-java, whose bundled natives are desktop-only.
The GitHub Actions workflow builds `libimgui-java64.so` for Android arm64 with the NDK
(`scripts/build-android-natives.sh`) and bundles it in the mod jar under `natives/android-arm64/`.
At startup on Android the mod extracts it to `<game dir>/config/anubis/natives/` and loads it, so the original
ClickGUI and main menu appear. If the library is missing or fails to load, Anubis falls back to the vanilla-widget GUI.
- Check the Actions run: job "ImGui native" must be green; artifact `natives-diagnostics` has the full log.
- Needs a renderer with OpenGL 3.3 core (MobileGlues, ANGLE, Zink ...). If the UI is black/crashes, try another renderer.
- The "Anubis" button on the pause and title screens always opens the GUI on Android (no keyboard needed).

## Using it in Pojav
- Minecraft 1.21.11 + Fabric Loader 0.19.2+, Fabric API 0.141.2+1.21.11, Java 21 runtime in the launcher.
- Pick a renderer with OpenGL 3.3 core features (e.g. MobileGlues, Holy-GL4ES, ANGLE or Zink depending on your launcher). Anubis' custom shaders may not work on every translation layer.
- Touch has no keyboard: bind the ClickGUI key to an on-screen control button in the launcher's control editor.
- Spotify integration needs desktop tools and won't work.

## Build
`./gradlew build` (JDK 21). Output: `build/libs/anubis-*.jar`. GitHub Actions builds it on every push.
Local builds are unsigned, so the official-build tamper check reports "unsigned local build" and does not stop the game.

## License
GPL-3.0-only, inherited from the original Anubis client by 4ldenz. Keep the LICENSE file and original credits when you publish a fork.
