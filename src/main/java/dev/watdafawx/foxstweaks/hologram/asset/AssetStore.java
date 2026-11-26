package dev.watdafawx.foxstweaks.hologram.asset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Server-thread owned. Blobs are immutable; index replacement is atomic; release never deletes. */
public final class AssetStore {
    public record Metadata(String id, UUID owner, String name, String type, int width, int height,
                           int frameCount, List<Integer> delays, long byteSize, long created, int references) {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;
    private final Map<String, Metadata> index = new LinkedHashMap<>();
    public AssetStore(Path root) throws IOException {
        this.root = root; Files.createDirectories(root.resolve("blobs"));
        Path file=root.resolve("index.json");
        if(Files.exists(file)) {
            try(var reader=Files.newBufferedReader(file)) {
                Metadata[] entries=JSON.fromJson(reader,Metadata[].class);
                if(entries == null) throw new IOException("Invalid asset index");
                for(var m:entries) {
                    if(!AssetFrames.validId(m.id()) || m.owner()==null || m.byteSize()<0) throw new IOException("Invalid asset metadata");
                    index.put(m.id(),m);
                }
            } catch(RuntimeException e) { throw new IOException("Cannot read asset index",e); }
        }
    }
    public static Path path(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("foxstweaks/assets"); }
    public List<Metadata> list() { return List.copyOf(index.values()); }
    public Metadata info(String id) { return index.get(id); }
    public long bytes() { return index.values().stream().mapToLong(Metadata::byteSize).sum(); }
    public long count(UUID owner) { return index.values().stream().filter(m->m.owner().equals(owner)).count(); }
    /** A sanitised upload, ready to store. Made off the server thread by {@link #prepare}. */
    public record Prepared(AssetFrames frames, byte[] blob, String id) {}

    /**
     * The expensive half of an upload: decode, rescale and re-encode every frame, then hash. Pure (touches no
     * store state), so it runs on a worker thread; decoding a 10 s GIF on the server thread stalls the tick.
     */
    public static Prepared prepare(byte[] input, AssetLimits limits) throws IOException {
        return prepare(input, limits, limits.fileBytes());
    }

    public static Prepared prepare(byte[] input, AssetLimits limits, int inputCap) throws IOException {
        AssetFrames frames = AssetFrames.sanitize(input, limits, inputCap);
        byte[] blob = frames.encode();
        return new Prepared(frames, blob, AssetFrames.hash(blob));
    }

    public Metadata put(UUID owner, String name, byte[] input, AssetLimits limits) throws IOException {
        return commit(owner, name, prepare(input, limits), limits);
    }

    /** Server thread: quota checks and the index write for an already prepared upload. */
    public Metadata commit(UUID owner, String name, Prepared prepared, AssetLimits limits) throws IOException {
        AssetFrames frames = prepared.frames(); byte[] blob = prepared.blob(); String id = prepared.id();
        if(index.containsKey(id)) return index.get(id);
        if(count(owner)>=limits.perPlayer()) throw new IOException("Per-player asset cap exceeded");
        if(bytes()+blob.length>limits.worldBytes()) throw new IOException("World asset quota exceeded");
        String clean=name.replaceAll("[\\p{Cntrl}]", "_"); if(clean.length()>128) clean=clean.substring(0,128);
        Metadata meta=new Metadata(id,owner,clean,frames.frames().size()==1?"IMAGE":"ANIMATION",frames.width(),frames.height(),
                frames.frames().size(),List.copyOf(frames.delays()),blob.length,System.currentTimeMillis(),0);
        atomic(root.resolve("blobs").resolve(id),blob);
        index.put(id,meta);
        try { save(); } catch(IOException e) { index.remove(id); Files.deleteIfExists(root.resolve("blobs").resolve(id)); throw e; }
        return meta;
    }
    public byte[] read(String id) throws IOException {
        if(!AssetFrames.validId(id) || !index.containsKey(id)) throw new IOException("Unknown asset");
        Path path=root.resolve("blobs").resolve(id);
        if(Files.size(path)>AssetLimits.MAX_BYTES) throw new IOException("Asset exceeds transfer ceiling");
        byte[] bytes=Files.readAllBytes(path);
        if(!AssetFrames.hash(bytes).equals(id)) throw new IOException("Asset hash mismatch");
        return bytes;
    }
    public void retain(String id) throws IOException { references(id,1); }
    public void release(String id) throws IOException { references(id,-1); }
    private void references(String id,int delta) throws IOException {
        Metadata m=index.get(id); if(m==null) throw new IOException("Unknown asset");
        Metadata next=new Metadata(m.id,m.owner,m.name,m.type,m.width,m.height,m.frameCount,m.delays,m.byteSize,m.created,Math.max(0,Math.addExact(m.references,delta)));
        index.put(id,next);try {save();} catch(IOException e) {index.put(id,m);throw e;}
    }
    /**
     * Sets every reference count to what the saved holograms actually use. Counts are kept up to date on each
     * save, but a world that crashed before saving its holograms would otherwise keep a stale count forever.
     */
    public void reconcile(Map<String, Integer> used) throws IOException {
        boolean changed = false;
        for (var entry : List.copyOf(index.entrySet())) {
            Metadata m = entry.getValue();
            int actual = used.getOrDefault(m.id(), 0);
            if (m.references() == actual)
                continue;
            index.put(m.id(), new Metadata(m.id, m.owner, m.name, m.type, m.width, m.height, m.frameCount, m.delays,
                    m.byteSize, m.created, actual));
            changed = true;
        }
        if (changed)
            save();
    }

    public boolean delete(String id) throws IOException {
        Metadata m=index.remove(id);if(m==null)return false;
        try {save();} catch(IOException e) {index.put(id,m);throw e;}
        Files.deleteIfExists(root.resolve("blobs").resolve(id));return true;
    }
    private void save() throws IOException { atomic(root.resolve("index.json"),JSON.toJson(index.values()).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    public static void atomic(Path file,byte[] data) throws IOException {
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        try {Files.write(temp,data);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally {Files.deleteIfExists(temp);}
    }
}
