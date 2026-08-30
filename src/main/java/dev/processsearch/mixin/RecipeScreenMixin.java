package dev.processsearch.mixin;

import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.screen.RecipeScreen;
import dev.processsearch.recipe.RecipeFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says out loud when the recipe list has been narrowed.
 *
 * <p>Pages silently disappearing because of text left in the search box is exactly the kind of thing
 * that reads as a broken mod. One line of grey text removes the mystery.
 */
@Mixin(RecipeScreen.class)
public class RecipeScreenMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void processsearch$drawFilterNotice(GuiGraphics graphics, int mouseX, int mouseY,
                                                float partialTicks, CallbackInfo ci) {
        if (!RecipeFilter.isActive()) {
            return;
        }
        RecipeScreen self = (RecipeScreen) (Object) this;
        Bounds bounds;
        try {
            bounds = self.getBounds();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (bounds == null || bounds.empty()) {
            return;
        }
        String text = "filtered: " + RecipeFilter.lastKept() + " of " + RecipeFilter.lastTotal();
        Minecraft mc = Minecraft.getInstance();
        graphics.drawString(mc.font, text, bounds.x() + 5, bounds.y() + bounds.height() - 11,
                0xFF909090, false);
    }
}
