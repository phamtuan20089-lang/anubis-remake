package net.anubis.client.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime platform detection so Anubis can degrade gracefully inside
 * PojavLauncher-family launchers (Pojav, Amethyst, Zalith, FCL, ...) on Android.
 */
@Environment(EnvType.CLIENT)
public final class Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger("Anubis");
    private static final boolean ANDROID = detectAndroid();
    private static volatile Boolean awtOk;

    private Platform() {
    }

    private static boolean detectAndroid() {
        if (Boolean.getBoolean("anubis.forceAndroid")) {
            return true;
        }
        if (Boolean.getBoolean("anubis.forceDesktop")) {
            return false;
        }
        try {
            for (String key : System.getenv().keySet()) {
                if (key.startsWith("POJAV") || key.equals("ANDROID_ROOT") || key.equals("ANDROID_DATA")) {
                    return true;
                }
            }
            for (String key : System.getProperties().stringPropertyNames()) {
                if (key.startsWith("pojav") || key.startsWith("glfwstub")) {
                    return true;
                }
            }
            String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
            String vm = System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT);
            return vendor.contains("android") || vm.contains("android");
        } catch (SecurityException e) {
            return false;
        }
    }

    /** True when running under PojavLauncher or another Android launcher. */
    public static boolean isAndroid() {
        return ANDROID;
    }

    /** True when the GL renderer looks like a mobile / translated implementation. */
    public static boolean isMobileRenderer(String glRenderer) {
        if (ANDROID) {
            return true;
        }
        if (glRenderer == null) {
            return false;
        }
        String r = glRenderer.toLowerCase(Locale.ROOT);
        return r.contains("gl4es") || r.contains("mali") || r.contains("adreno") || r.contains("angle") || r.contains("powervr");
    }

    /** One-time probe: can we create a BufferedImage + Graphics2D + Font here? */
    public static boolean awtUsable() {
        Boolean cached = awtOk;
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12));
            g.getFontMetrics().stringWidth("A");
            g.dispose();
            ok = true;
        } catch (Throwable t) {
            LOGGER.warn("[Anubis] java.awt is not usable here ({}); AWT-drawn sky textures are disabled", t.toString());
            ok = false;
        }
        awtOk = ok;
        return ok;
    }

    /**
     * Must run before any imgui-java class initialises. Lets an Android build of the
     * imgui-java JNI library be dropped into {@code <config>/anubis/natives/} (or pointed at
     * with -Dimgui.library.path) without touching the bundled desktop natives.
     */
    public static void prepareImGuiNatives() {
        if (!ANDROID || System.getProperty("imgui.library.path") != null) {
            return;
        }
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("anubis").resolve("natives");
            Files.createDirectories(dir);
            extractBundledNative(dir);
            Path lib = dir.resolve("libimgui-java64.so");
            if (Files.isRegularFile(lib)) {
                System.setProperty("imgui.library.path", dir.toAbsolutePath().toString());
                LOGGER.info("[Anubis] Using ImGui natives from {}", dir);
                try {
                    System.load(lib.toAbsolutePath().toString());
                } catch (Throwable t) {
                    LOGGER.warn("[Anubis] Could not preload {}: {}", lib, t.toString());
                }
            } else {
                LOGGER.warn("[Anubis] No Android ImGui native found in {}; falling back to the vanilla-widget GUI", dir);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Anubis] Could not prepare ImGui natives: {}", t.toString());
        }
    }

    /** Copies the arm64 imgui-java library bundled inside the mod jar (built by CI) next to the config. */
    private static void extractBundledNative(Path dir) {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.contains("aarch64") && !arch.contains("arm64")) {
            return;
        }
        try (java.io.InputStream in = Platform.class.getResourceAsStream("/natives/android-arm64/libimgui-java64.so")) {
            if (in == null) {
                return;
            }
            Path target = dir.resolve("libimgui-java64.so");
            Path tmp = dir.resolve("libimgui-java64.so.tmp");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!Files.isRegularFile(target) || Files.size(target) != Files.size(tmp)) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Anubis] Extracted bundled ImGui native to {}", target);
            } else {
                Files.deleteIfExists(tmp);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Anubis] Could not extract bundled ImGui native: {}", t.toString());
        }
    }
}
