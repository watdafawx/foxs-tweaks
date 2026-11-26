package dev.watdafawx.foxstweaks.mixin;

import com.mojang.blaze3d.audio.Channel;
import dev.watdafawx.foxstweaks.client.audio.AnalysingAudioStream;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * Tells an analysed stream which frame is being heard. Vanilla queues up to four seconds ahead, so the PCM the
 * stream last handed out is not what plays now; OpenAL's queued-buffer count and sample offset are. Runs on the
 * sound thread right after vanilla's own stream update (and after the first fill), where the AL context is current.
 */
@Mixin(Channel.class)
public abstract class ChannelAudioTapMixin {

    @Shadow
    @Final
    private int source;

    @Shadow
    @Nullable
    private AudioStream stream;

    @Inject(method = { "updateStream", "attachBufferStream" }, at = @At("TAIL"), require = 0)
    private void foxstweaks$playhead(CallbackInfo ci) {
        if (this.stream instanceof AnalysingAudioStream analysed) {
            int queued = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_QUEUED);
            int offset = AL10.alGetSourcei(this.source, AL11.AL_SAMPLE_OFFSET);
            analysed.tap().onPlayhead(queued, offset);
        }
    }
}
