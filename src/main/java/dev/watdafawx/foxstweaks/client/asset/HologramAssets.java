package dev.watdafawx.foxstweaks.client.asset;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.watdafawx.foxstweaks.hologram.asset.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Render-thread API. Compressed frames only in RAM; one RGBA native image + GPU texture per drawn asset.
 * Decode on bounded worker, upload on client thread. 30s idle eviction; 32 MiB compressed RAM, 200 MiB disk LRU.
 * At 256²: 256 KiB native + 256 KiB VRAM per asset, plus one transient 256 KiB decode, not 37.5 MiB/150 frames.
 */
@EventBusSubscriber(modid="foxstweaks",value=Dist.CLIENT)
public final class HologramAssets {
    private static final long DISK_LIMIT=200L*1024*1024, RAM_LIMIT=32L*1024*1024;
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"Hologram assets");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    static {WORKER.allowCoreThreadTimeOut(true);}
    private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();
    private static final Map<String,Long> RETRY=new HashMap<>();
    private static final ArrayDeque<String> REQUESTS=new ArrayDeque<>();
    private static String downloading="";
    private static ByteArrayOutputStream incoming;
    private static int expected;
    private static long touched;
    private static byte[] uploading;
    private static int uploadOffset, uploadSequence;
    private static long generation, uploadStarted;
    public static String lastResult="";
    public static boolean lastSuccess;
    private static final class Entry {
        AssetFrames frames; DynamicTexture texture; ResourceLocation location; int frame=-1;boolean busy;long last=System.currentTimeMillis();long bytes, diskTouched;
    }
    public static Path cachePath() {return Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks/asset-cache");}
    private static boolean available() {var c=Minecraft.getInstance().getConnection();return c!=null && c.hasChannel(AssetPayload.TYPE);}
    public static ResourceLocation texture(String hash,double timeSeconds) {
        if(!AssetFrames.validId(hash))return null;
        Entry e=ENTRIES.get(hash);
        if(e==null) {
            if(ENTRIES.size()>=64 || RETRY.getOrDefault(hash,0L)>System.currentTimeMillis())return null;
            e=new Entry();ENTRIES.put(hash,e);load(hash,e);
        }
        e.last=System.currentTimeMillis();
        if(e.frames!=null && e.last-e.diskTouched>10000) {
            e.diskTouched=e.last;
            submit(()-> {try {Files.setLastModifiedTime(cachePath().resolve(hash),java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));}catch(IOException ignored) {}},()->{});
        }
        if(e.frames==null)return null;
        int frame=e.frames.frame(timeSeconds);
        if(frame!=e.frame && !e.busy) {
            Entry entry=e;entry.busy=true;long gen=generation;
            submit(()-> {
                NativeImage image=null;
                try {
                    image=NativeImage.read(new ByteArrayInputStream(entry.frames.frames().get(frame)));
                    NativeImage decoded=image;
                    Minecraft.getInstance().execute(()-> {
                        if(gen!=generation || ENTRIES.get(hash)!=entry) {decoded.close();return;}
                        try {
                            if(entry.texture==null) {entry.texture=new DynamicTexture(new NativeImage(decoded.getWidth(),decoded.getHeight(),false));entry.location=Minecraft.getInstance().getTextureManager().register("hologram_asset",entry.texture);}
                            entry.texture.getPixels().copyFrom(decoded);entry.texture.upload();entry.frame=frame;
                        } finally {decoded.close();entry.busy=false;}
                    });
                } catch(IOException|RuntimeException ex) {if(image!=null)image.close();Minecraft.getInstance().execute(()->entry.busy=false);}
            },()->entry.busy=false);
        }
        return e.location;
    }
    private static void submit(Runnable work,Runnable rejected) {try {WORKER.execute(work);}catch(RejectedExecutionException e){rejected.run();}}
    private static void load(String hash,Entry entry) {
        long gen=generation;
        submit(()-> {
            try {
                Path path=cachePath().resolve(hash);
                if(!Files.exists(path))throw new IOException("Not cached");
                if(Files.size(path)>AssetLimits.MAX_BYTES) {Files.delete(path);throw new IOException("Cache oversized");}
                byte[] bytes=Files.readAllBytes(path);
                if(!AssetFrames.hash(bytes).equals(hash)) {Files.delete(path);throw new IOException("Cache hash mismatch");}
                AssetFrames frames=AssetFrames.bundle(bytes);Files.setLastModifiedTime(path,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                Minecraft.getInstance().execute(()-> {if(gen==generation && ENTRIES.get(hash)==entry){entry.frames=frames;entry.bytes=bytes.length;}});
            } catch(IOException ex) {Minecraft.getInstance().execute(()-> {if(gen==generation && ENTRIES.get(hash)==entry && !REQUESTS.contains(hash))REQUESTS.add(hash);});}
        },()->ENTRIES.remove(hash));
    }
    public static void accept(AssetPayload p) {
        if(p.kind()==AssetPayload.RESULT && p.total()==AssetPayload.GET) {failDownload();return;}
        if(p.kind()==AssetPayload.RESULT) {lastSuccess=p.number()==1;lastResult=p.text();uploading=null;notifyPlayer((lastSuccess?"Asset uploaded: ":"Asset refused: ")+p.text());return;}
        if(p.kind()!=AssetPayload.DATA || !p.text().equals(downloading))return;
        try {
            if(p.total()<8 || p.total()>AssetLimits.MAX_BYTES || p.number()!=(incoming==null?0:incoming.size()) || p.bytes().length==0)throw new IOException("Invalid asset download sequence");
            if(incoming==null) {incoming=new ByteArrayOutputStream();expected=p.total();}
            if(p.total()!=expected || incoming.size()+p.bytes().length>expected)throw new IOException("Invalid asset download size");
            incoming.write(p.bytes());touched=System.currentTimeMillis();
            if(incoming.size()==expected) {
                byte[] bytes=incoming.toByteArray();String hash=downloading;
                if(!AssetFrames.hash(bytes).equals(hash))throw new IOException("Downloaded asset hash mismatch");
                Entry entry=ENTRIES.get(hash);long gen=generation;downloading="";incoming=null;
                submit(()-> {
                    try {
                        AssetFrames frames=AssetFrames.bundle(bytes);
                        Files.createDirectories(cachePath());AssetStore.atomic(cachePath().resolve(hash),bytes);trimDisk();
                        Minecraft.getInstance().execute(()-> {if(gen==generation && ENTRIES.get(hash)==entry && entry!=null){entry.frames=frames;entry.bytes=bytes.length;}});
                    } catch(IOException e) {Minecraft.getInstance().execute(()-> {ENTRIES.remove(hash);RETRY.put(hash,System.currentTimeMillis()+30000);});}
                },()->ENTRIES.remove(hash));
            } else PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.GET,incoming.size(),0,downloading));
        } catch(IOException e) {failDownload();}
    }
    private static void failDownload() {ENTRIES.remove(downloading);RETRY.put(downloading,System.currentTimeMillis()+30000);downloading="";incoming=null;}
    private static void trimDisk() throws IOException {
        try(var files=Files.list(cachePath())) {
            var paths=files.filter(p->AssetFrames.validId(p.getFileName().toString())).sorted(Comparator.comparingLong(p->p.toFile().lastModified())).toList();
            long bytes=0;for(Path p:paths)bytes+=Files.size(p);
            for(Path p:paths) {if(bytes<=DISK_LIMIT)break;long size=Files.size(p);Files.deleteIfExists(p);bytes-=size;}
        }
    }
    public static void upload(String relative) throws IOException {
        if(!available())throw new IOException("Server has no asset channel");
        if(uploading!=null)throw new IOException("Upload already in progress");
        Path root=Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks/upload");Files.createDirectories(root);root=root.toRealPath();
        Path supplied=Path.of(relative);
        if(supplied.isAbsolute())throw new IOException("Upload path must be relative");
        Path path=root.resolve(supplied).normalize();
        if(!path.startsWith(root) || !path.toRealPath().startsWith(root) || !Files.isRegularFile(path))throw new IOException("Upload must stay under foxstweaks/upload");
        if(Files.size(path)>AssetLimits.MAX_BYTES)throw new IOException("Upload exceeds 16 MiB ceiling");
        uploading=Files.readAllBytes(path);uploadOffset=0;uploadSequence=0;uploadStarted=System.currentTimeMillis();lastResult="";
        PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.BEGIN,0,uploading.length,path.getFileName().toString()));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) {
        if(!available())return;
        if(uploading!=null && uploadOffset<uploading.length) {
            int end=Math.min(uploading.length,uploadOffset+AssetPayload.CHUNK_BYTES);
            PacketDistributor.sendToServer(new AssetPayload(AssetPayload.CHUNK,uploadSequence++,0,"",Arrays.copyOfRange(uploading,uploadOffset,end)));uploadOffset=end;
        }
        long now=System.currentTimeMillis();
        if(uploading!=null && now-uploadStarted>30000) {uploading=null;lastSuccess=false;lastResult="Upload timed out";PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.ABORT,0,0,""));}
        if(!downloading.isEmpty() && now-touched>30000)failDownload();
        if(downloading.isEmpty() && !REQUESTS.isEmpty()) {downloading=REQUESTS.removeFirst();touched=now;PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.GET,0,0,downloading));}
        long total=ENTRIES.values().stream().mapToLong(v->v.bytes).sum();
        var iterator=ENTRIES.entrySet().iterator();
        while(iterator.hasNext()) {var item=iterator.next();var entry=item.getValue();if(now-entry.last>30000 || total>RAM_LIMIT) {total-=entry.bytes;if(entry.location!=null)Minecraft.getInstance().getTextureManager().release(entry.location);iterator.remove();}}
        RETRY.values().removeIf(t->t<now);
    }
    public static void clearMemory() {generation++;for(var e:ENTRIES.values())if(e.location!=null)Minecraft.getInstance().getTextureManager().release(e.location);ENTRIES.clear();REQUESTS.clear();RETRY.clear();downloading="";incoming=null;uploading=null;}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {clearMemory();}
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("foxstweaksc").then(Commands.literal("asset").then(Commands.literal("upload").then(Commands.argument("path",StringArgumentType.greedyString()).executes(c->{try {upload(StringArgumentType.getString(c,"path"));return 1;}catch(IOException|RuntimeException ex){notifyPlayer(ex.getMessage());return 0;}})))));
    }
    private static void notifyPlayer(String text) {var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(text),false);}
}
