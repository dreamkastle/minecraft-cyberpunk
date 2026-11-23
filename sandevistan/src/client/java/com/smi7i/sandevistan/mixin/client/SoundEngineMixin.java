package com.smi7i.sandevistan.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.smi7i.sandevistan.client.SlowMotionClient;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Пока мир вокруг в слоумо, звуки мира (мобы, блоки, взрывы, погода) звучат ниже и тягучее, как в кино.
 * Звуки игрока (и звуки импланта — они тоже «игрока»), музыка и интерфейс — как обычно.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    /** Громкость всего, кроме спокойной музыки, во время киберпсихоза. */
    private static final float PSYCHOSIS_WORLD_VOLUME = 0.03F;
    private static final Identifier PSYCHOSIS_CALM_ID =
            Identifier.fromNamespaceAndPath("sandevistan", "psychosis_calm");
    /** Голос в начале приступа: звучит на полной громкости, не приглушается. */
    private static final Identifier PSYCHOSIS_VOICE_ID =
            Identifier.fromNamespaceAndPath("sandevistan", "psychosis_voice");

    /** Голос бабки выключен в настройках мода: звук не играет совсем. */
    private static boolean sandevistan$voiceDisabled(SoundInstance sound) {
        return !com.smi7i.sandevistan.client.SandevistanConfig.get().psychosisVoice
                && sound.getIdentifier().equals(PSYCHOSIS_VOICE_ID);
    }

    /** Игрок «оглох»: идёт приступ киберпсихоза или малый приступ. */
    private static boolean sandevistan$deaf() {
        return com.smi7i.sandevistan.client.ClientSandevistanState.psychosis()
                || com.smi7i.sandevistan.client.ClientSandevistanState.episode();
    }

    /** true, если звук нужно приглушить до 3% (идёт приступ и это не спокойная музыка и не голос в начале приступа). */
    private static boolean sandevistan$mustDuck(SoundInstance sound) {
        if (!sandevistan$deaf()) return false;
        Identifier id = sound.getIdentifier();
        return !id.equals(PSYCHOSIS_CALM_ID) && !id.equals(PSYCHOSIS_VOICE_ID);
    }

    /**
     * ГЛАВНОЕ ИСПРАВЛЕНИЕ. Раньше громкость урезалась только в calculateVolume(SoundInstance), а он вызывается
     * лишь для «тикающих» звуков (гул, музыка) и при смене ползунков громкости. Разовые звуки (шаги, удары,
     * мобы, взрывы, playLocalSound) получают громкость один раз в момент запуска — в play(), в обход этого метода,
     * поэтому играли на полной громкости. Здесь режем громкость прямо при запуске звука.
     */
    @WrapOperation(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getVolume()F"))
    private float sandevistan$duckOnStart(SoundInstance sound, Operation<Float> original) {
        if (sandevistan$voiceDisabled(sound)) return 0.0F; // нулевая громкость — движок звук не запускает
        float volume = original.call(sound);
        return sandevistan$mustDuck(sound) ? volume * PSYCHOSIS_WORLD_VOLUME : volume;
    }

    /**
     * Во время киберпсихоза все звуки игры и мода понижаются до 3% (0.03F),
     * кроме спокойной музыки приступа (psychosis_calm) и голоса в начале приступа (psychosis_voice). Это для «тикающих» звуков и пересчёта громкости.
     */
    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void sandevistan$cutsceneSilence(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        if (sandevistan$voiceDisabled(sound)) {
            cir.setReturnValue(0.0F);
            return;
        }
        if (sandevistan$deaf()) {
            if (sandevistan$mustDuck(sound)) {
                cir.setReturnValue(cir.getReturnValue() * PSYCHOSIS_WORLD_VOLUME);
                return;
            }
            cir.setReturnValue(cir.getReturnValue()
                    * com.smi7i.sandevistan.client.SandevistanConfig.get().soundVolume);
            return;
        }

        // Свои звуки мода — по ползунку «Громкость звуков мода» в настройках.
        if ("sandevistan".equals(sound.getIdentifier().getNamespace())) {
            cir.setReturnValue(cir.getReturnValue() * com.smi7i.sandevistan.client.SandevistanConfig.get().soundVolume);
            return;
        }
        float s = Math.max(com.smi7i.sandevistan.client.mech.MechCutscene.silence(),
                com.smi7i.sandevistan.client.mech.MechShowdownClient.silence());
        float volume = cir.getReturnValue() * com.smi7i.sandevistan.client.PsychosisMedia.soundFactor();
        if (s > 0) volume *= 1.0F - 0.93F * s;
        cir.setReturnValue(volume);
    }

    @Inject(method = "calculatePitch", at = @At("RETURN"), cancellable = true)
    private void sandevistan$slowWorldSounds(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        if (!SlowMotionClient.isSlowMotion()) return;
        SoundSource source = sound.getSource();
        if (source == SoundSource.PLAYERS || source == SoundSource.MASTER || source == SoundSource.MUSIC
                || source == SoundSource.UI || source == SoundSource.VOICE) return;
        // Движок не опускает высоту ниже 0.5 — это и есть «максимально тягуче».
        if (!com.smi7i.sandevistan.client.SandevistanConfig.get().slowWorldSounds) return;
        cir.setReturnValue(Math.max(0.5F, cir.getReturnValue() * SlowMotionClient.WORLD_SOUND_PITCH));
    }
}
