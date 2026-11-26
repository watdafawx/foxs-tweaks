package dev.watdafawx.foxstweaks.hologram.asset;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * BEGIN(mode,total,name), CHUNK(sequence,bytes), ABORT, GET(offset,id), DATA(offset,id,bytes), RESULT(ok,message),
 * PROGRESS(percent,stage), LIST(offset), LISTING(offset,count,json). BEGIN's mode is {@link #MODE_IMAGE},
 * {@link #MODE_VIDEO}, {@link #MODE_BUNDLE} or {@link #MODE_MODEL}.
 */
public record AssetPayload(int kind, int number, int total, String text, byte[] bytes) implements CustomPacketPayload {
    public static final int BEGIN=0, CHUNK=1, ABORT=2, GET=3, DATA=4, RESULT=5, CHUNK_BYTES=30000;
    public static final int PROGRESS = 6, LIST = 7, LISTING = 8;
    /** Client asks to delete asset {@code text}; {@code number} 1 = even when holograms use it. */
    public static final int DELETE = 9;
    /** A frame bundle (FTAS) the browser built from a video itself; may start larger than the asset limit. */
    public static final int MODE_IMAGE = 0, MODE_VIDEO = 1, MODE_BUNDLE = 2, MODE_MODEL = 3;
    public static final Type<AssetPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("foxstweaks","asset"));
    public static final StreamCodec<FriendlyByteBuf,AssetPayload> CODEC=StreamCodec.of((b,p)-> {
        b.writeVarInt(p.kind);b.writeVarInt(p.number);b.writeVarInt(p.total);b.writeUtf(p.text,256);b.writeByteArray(p.bytes);
    },b->new AssetPayload(b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(256),b.readByteArray(CHUNK_BYTES)));
    @Override public Type<? extends CustomPacketPayload> type() {return TYPE;}
    public static AssetPayload request(int kind,int number,int total,String text) {return new AssetPayload(kind,number,total,text,new byte[0]);}
}
