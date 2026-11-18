package com.smi7i.sandevistan.mixin.client;

import com.smi7i.sandevistan.client.SlowMotionClient;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
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
    /** Кат-сцена посадки: в чёрном экране мир почти не слышно («гулкая тишина»), свои звуки мода — слышно. */
    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void sandevistan$cutsceneSilence(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        // Свои звуки мода — по ползунку «Громкость звуков мода» в настройках.
        if ("sandevistan".equals(sound.getIdentifier().getNamespace())) {
            cir.setReturnValue(cir.getReturnValue() * com.smi7i.sandevistan.client.SandevistanConfig.get().soundVolume);
            return;
        }
        float s = Math.max(com.smi7i.sandevistan.client.mech.MechCutscene.silence(),
                com.smi7i.sandevistan.client.mech.MechShowdownClient.silence());
        if (s <= 0) return;
        cir.setReturnValue(cir.getReturnValue() * (1.0F - 0.93F * s));
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
