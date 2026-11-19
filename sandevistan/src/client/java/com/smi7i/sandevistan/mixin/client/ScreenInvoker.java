package com.smi7i.sandevistan.mixin.client;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к защищённому Screen.addRenderableWidget: через него добавляем вкладку «Импланты» в инвентарь. */
@Mixin(Screen.class)
public interface ScreenInvoker {
    @Invoker("addRenderableWidget")
    GuiEventListener sandevistan$addRenderableWidget(GuiEventListener widget);
}
