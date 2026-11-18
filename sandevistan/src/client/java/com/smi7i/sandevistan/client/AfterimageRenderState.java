package com.smi7i.sandevistan.client;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Снимок позы игрока для послеобраза. Это обычное состояние отрисовки игрока,
 * поэтому игра рисует его своим кодом; наши mixin'ы узнают его по классу
 * и рисуют полупрозрачным и цветным, без брони и предметов.
 */
public class AfterimageRenderState extends AvatarRenderState {
    /** Цвет и прозрачность на этот кадр (ARGB). */
    public int afterimageColor = -1;
    /** Клиентский тик, когда снимок сделан. */
    public long bornTick;
    /** Порядковый номер снимка: от него зависит оттенок. */
    public int index;
    /** Снят во время киберпсихоза — красный. */
    public boolean psychosis;
}
