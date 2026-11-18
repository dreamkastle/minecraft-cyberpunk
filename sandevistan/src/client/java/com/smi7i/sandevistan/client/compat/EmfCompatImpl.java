package com.smi7i.sandevistan.client.compat;

import com.smi7i.sandevistan.client.AfterimageRenderState;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;

/**
 * Послеобразы — снимки позы. EMF анимирует модель по живому игроку (ходьба, дыхание и т.п.),
 * поэтому копии «шли на месте». Через официальный API EMF отменяем его анимацию для снимков:
 * модель остаётся в позе из снимка — послеобраз замирает.
 * Загружается только если EMF установлен (см. EmfCompat).
 */
final class EmfCompatImpl {
    private EmfCompatImpl() {}

    static void register() throws Exception {
        EMFAnimationApi.registerAnimationHook(new EMFAnimationApi.EMFAnimationHook() {
            @Override
            public boolean onAnimationStart(AnimationContext context, boolean alreadyCancelled) {
                EMFEntityRenderState state = context.activeState();
                // false — EMF не анимирует модель в этой отрисовке.
                if (state == null) return true;
                if (state.vanillaState() instanceof AfterimageRenderState) return false;
                // Кадры кат-сцены «Последнего боя»: позу (сидят, смотрят на Землю) задаём сами.
                if (state.vanillaState() instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState stage
                        && Boolean.TRUE.equals(stage.getData(com.smi7i.sandevistan.client.mech.MechWornLayer.STAGE))) return false;
                // Гибель в костюме: позу головы (падает на грудь) задаём сами.
                if (state.vanillaState() instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState dying) {
                    Float dt = dying.getData(com.smi7i.sandevistan.client.mech.MechWornLayer.DEATH_TIME);
                    if (dt != null && dt >= 0) return false;
                }
                // Кат-сцена посадки: пока рука стучит по броне, позу задаём сами (EMF/Fresh Animations её бы перезаписал).
                if (state.vanillaState() instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar
                        && com.smi7i.sandevistan.client.mech.MechCutscene.knockPhase(
                        com.smi7i.sandevistan.client.mech.MechWornLayer.boardTime(avatar))) return false;
                return true;
            }
        });
    }
}
