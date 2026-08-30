package dev.processsearch.input;

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import dev.processsearch.ProcessSearch;
import org.lwjgl.glfw.GLFW;

/**
 * One configurable key combination, written as {@code shift+comma}.
 *
 * <p>The process tree is opened with {@code <} and {@code >}, which on a US layout are shift+comma
 * and shift+period. They are not those keys everywhere, which is why this is config-driven -- the
 * same reasoning that makes the search prefixes configurable.
 *
 * <p>Key names are Minecraft's own, minus the {@code key.keyboard.} prefix, so anything
 * {@link InputConstants} knows works: {@code comma}, {@code period}, {@code slash}, {@code f6},
 * {@code left.bracket}.
 */
public record HotKey(int keyCode, boolean shift, boolean control, boolean alt) {
    private static final String PREFIX = "key.keyboard.";

    /** @param modifiers the GLFW modifier bitfield handed to {@code keyPressed} */
    public boolean matches(int pressedKeyCode, int modifiers) {
        if (pressedKeyCode != keyCode) {
            return false;
        }
        return shift == isSet(modifiers, GLFW.GLFW_MOD_SHIFT)
                && control == isSet(modifiers, GLFW.GLFW_MOD_CONTROL)
                && alt == isSet(modifiers, GLFW.GLFW_MOD_ALT);
    }

    private static boolean isSet(int modifiers, int flag) {
        return (modifiers & flag) != 0;
    }

    /** @return the parsed key, or {@code fallback} if the spec names a key Minecraft does not know */
    public static HotKey parse(String spec, HotKey fallback, String what) {
        if (spec == null || spec.isBlank()) {
            return fallback;
        }
        boolean shift = false;
        boolean control = false;
        boolean alt = false;
        String name = null;

        for (String part : spec.toLowerCase(Locale.ROOT).split("\\+")) {
            String trimmed = part.trim();
            switch (trimmed) {
                case "" -> {
                    // A stray separator; ignore it rather than failing the whole bind.
                }
                case "shift" -> shift = true;
                case "ctrl", "control" -> control = true;
                case "alt" -> alt = true;
                default -> name = trimmed;
            }
        }
        if (name == null) {
            ProcessSearch.LOGGER.error("Hotkey '{}' ({}) names no key; using the default", spec, what);
            return fallback;
        }

        try {
            InputConstants.Key key = InputConstants.getKey(PREFIX + name);
            if (key == InputConstants.UNKNOWN) {
                throw new IllegalArgumentException("unknown key");
            }
            return new HotKey(key.getValue(), shift, control, alt);
        } catch (RuntimeException e) {
            ProcessSearch.LOGGER.error("Hotkey '{}' ({}) is not a key Minecraft knows; using the default",
                    spec, what);
            return fallback;
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (control) {
            sb.append("ctrl+");
        }
        if (alt) {
            sb.append("alt+");
        }
        if (shift) {
            sb.append("shift+");
        }
        String name = InputConstants.Type.KEYSYM.getOrCreate(keyCode).getName();
        sb.append(name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name);
        return sb.toString();
    }
}
