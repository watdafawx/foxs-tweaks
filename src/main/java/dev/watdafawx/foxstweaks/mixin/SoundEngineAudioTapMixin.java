package dev.watdafawx.foxstweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.watdafawx.foxstweaks.client.audio.AudioHub;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * Lets music-reactive holograms see the PCM of every streamed sound: the stream a {@link SoundInstance} supplies
 * is wrapped in an analysing pass-through before vanilla attaches it to its channel. Wrapping the call site
 * (rather than {@code SoundBufferLibrary#getStream}) also covers mods whose sound instances override
 * {@code getStream} with their own PCM. Only records and music are actually wrapped; see {@link AudioHub#wrap}.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineAudioTapMixin {

    @WrapOperation(method = "play", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> foxstweaks$tapStream(SoundInstance instance, SoundBufferLibrary buffers,
                                                              Sound sound, boolean looping,
                                                              Operation<CompletableFuture<AudioStream>> original) {
        CompletableFuture<AudioStream> future = original.call(instance, buffers, sound, looping);
        if (future == null)
            return null;
        return future.thenApply(stream -> AudioHub.wrap(instance, stream));
    }
}
