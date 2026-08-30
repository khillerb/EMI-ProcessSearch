package dev.processsearch.mixin;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.screen.EmiScreenManager;
import dev.processsearch.ProcessSearchConfig;
import dev.processsearch.index.tree.Direction;
import dev.processsearch.index.tree.ProcessTreeNavigation;
import dev.processsearch.input.KeyHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens the process tree when {@code <} or {@code >} is pressed over a stack.
 *
 * <p>Injected at {@code RETURN} rather than {@code HEAD}. EMI gives its search widget first refusal
 * on every key and returns {@code true} whenever the box consumed the key or merely has focus, so
 * checking the return value is all it takes to never steal a keystroke from the search field. No
 * guessing about focus state, and typing {@code <} into the box still works.
 */
@Mixin(value = EmiScreenManager.class, remap = false)
public class EmiScreenManagerMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("RETURN"), cancellable = true, remap = false)
    private static void processsearch$openProcessTree(int keyCode, int scanCode, int modifiers,
                                                      CallbackInfoReturnable<Boolean> cir) {
        // Reaching this at all is the proof the mixin applied; /processsearch stats reports it.
        KeyHook.markInstalled();

        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            // EMI already handled it, which includes "the search box has focus".
            return;
        }
        if (!ProcessSearchConfig.processTree()) {
            return;
        }

        Direction direction;
        if (ProcessSearchConfig.treeConsumersKey().matches(keyCode, modifiers)) {
            direction = Direction.CONSUMERS;
        } else if (ProcessSearchConfig.treeProducersKey().matches(keyCode, modifiers)) {
            direction = Direction.PRODUCERS;
        } else {
            return;
        }

        EmiStackInteraction hovered;
        try {
            hovered = EmiApi.getHoveredStack(false);
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (hovered == null || hovered.isEmpty()) {
            return;
        }
        if (ProcessTreeNavigation.open(hovered.getStack(), direction)) {
            cir.setReturnValue(true);
        }
    }
}
