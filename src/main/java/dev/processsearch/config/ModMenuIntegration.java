package dev.processsearch.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Puts a config button on the mod's entry in the main menu's mod list.
 *
 * <p>Registered as the {@code modmenu} entrypoint, so this class is only ever loaded when Mod Menu
 * is installed to ask for it.
 *
 * <p>Cloth is a second, separate question. The factory is returned as a lambda whose <em>body</em>
 * names {@link ProcessSearchConfigScreen}, not a method reference: the body's classes resolve when
 * the button is clicked rather than when the factory is built, so a pack with Mod Menu and no Cloth
 * gets a button that does nothing rather than a crash on the mod list.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            // Mod Menu reads a null screen as "this mod has no config screen".
            return parent -> null;
        }
        return parent -> ProcessSearchConfigScreen.create(parent);
    }
}
