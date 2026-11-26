package dev.watdafawx.foxstweaks.hologram.asset;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** FTAS v1: magic, frame count, then (delay milliseconds, PNG length, PNG bytes). Hash covers entire bundle. */
public record AssetFrames(List<byte[]> frames, List<Integer> delays, int width, int height) {
    public static final int MAGIC = 0x46544131;
    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(frames.size());
            for (int i = 0; i < frames.size(); i++) {
                out.writeInt(delays.get(i)); out.writeInt(frames.get(i).length); out.write(frames.get(i));
            }
        }
        return bytes.toByteArray();
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{64}"); }
    public static AssetFrames bundle(byte[] bytes) throws IOException {
        if (bytes.length > AssetLimits.MAX_BYTES) throw new IOException("Asset byte limit exceeded");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) throw new IOException("Invalid frame bundle magic");
        int count = in.readInt();
        if (count < 1 || count > AssetLimits.MAX_FRAMES) throw new IOException("Frame count limit exceeded");
        var frames = new ArrayList<byte[]>(); var delays = new ArrayList<Integer>();
        int w = 0, h = 0;
        for (int i = 0; i < count; i++) {
            int delay = in.readInt(), len = in.readInt();
            if (delay < 1 || delay > 30000 || len < 1 || len > in.available()) throw new IOException("Invalid frame bounds");
            byte[] frame = in.readNBytes(len);
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(frame))) {
                ImageReader reader = reader(frame, stream);
                try {
                    if (reader.getFormatName().equalsIgnoreCase("gif")) throw new IOException("Sequence frames must be PNG or JPEG");
                    int fw = reader.getWidth(0), fh = reader.getHeight(0);
                    dimensions(fw, fh);
                    if (i == 0) { w = fw; h = fh; }
                    if (w != fw || h != fh) throw new IOException("Frame dimensions differ");
                } finally { reader.dispose(); }
            }
            frames.add(frame); delays.add(delay);
        }
        if (in.available() != 0 || (long) w * h * count > AssetLimits.PIXEL_BUDGET) throw new IOException("Frame pixel budget exceeded");
        return new AssetFrames(List.copyOf(frames), List.copyOf(delays), w, h);
    }
    private static ImageReader reader(byte[] bytes, MemoryCacheImageInputStream stream) throws IOException {
        boolean png = bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
        boolean gif = bytes.length >= 6 && new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).matches("GIF8[79]a");
        boolean jpg = bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255;
        if (!png && !gif && !jpg) throw new IOException("Unsupported image magic (PNG, JPEG or GIF required)");
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new IOException("Image decoder unavailable");
        var reader = readers.next(); reader.setInput(stream); return reader;
    }
    private static void dimensions(int w, int h) throws IOException {
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long)w*h > 4194304) throw new IOException("Decoded dimensions exceed bomb guard");
    }
    public static AssetFrames sanitize(byte[] input, AssetLimits limits) throws IOException {
        if (input.length < 8 || input.length > limits.fileBytes()) throw new IOException("File byte limit exceeded or truncated file");
        if (new DataInputStream(new ByteArrayInputStream(input)).readInt() == MAGIC) {
            AssetFrames src = bundle(input);
            var result = new ArrayList<byte[]>(); var delays = new ArrayList<Integer>(); int elapsed = 0, stored = 8;
            for (int i = 0; i < src.frames.size(); i++) {
                AssetFrames frame = sanitize(src.frames.get(i), limits);
                int delay = Math.max(src.delays.get(i), (int)Math.ceil(1000.0 / limits.fps()));
                elapsed += delay;
                if (elapsed > limits.seconds()*1000) throw new IOException("Animation duration limit exceeded");
                stored += frame.frames.getFirst().length + 8;
                if (stored > limits.fileBytes()) throw new IOException("Canonical asset byte limit exceeded");
                result.add(frame.frames.getFirst()); delays.add(delay);
            }
            AssetFrames first = sanitize(src.frames.getFirst(), limits);
            return checked(new AssetFrames(result, delays, first.width, first.height), limits);
        }
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            ImageReader reader = reader(input, stream);
            try {
                int w = reader.getWidth(0), h = reader.getHeight(0); dimensions(w, h);
                if (reader.getFormatName().equalsIgnoreCase("gif")) {
                    var metadata = reader.getStreamMetadata();
                    var root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_stream_1.0");
                    var screen = (IIOMetadataNode) root.getElementsByTagName("LogicalScreenDescriptor").item(0);
                    w = Integer.parseInt(screen.getAttribute("logicalScreenWidth"));
                    h = Integer.parseInt(screen.getAttribute("logicalScreenHeight"));
                    dimensions(w, h);
                }
                int count = reader.getNumImages(true);
                if (count < 1 || count > Math.min(AssetLimits.MAX_FRAMES, limits.fps()*limits.seconds())
                        || (long)w*h*count > AssetLimits.PIXEL_BUDGET) throw new IOException("Frame count/pixel budget exceeded");
                long decodedPixels = 0;
                for (int i = 0; i < count; i++) {
                    int fw = reader.getWidth(i), fh = reader.getHeight(i);
                    dimensions(fw, fh);
                    decodedPixels += (long) fw * fh;
                    if (decodedPixels > AssetLimits.PIXEL_BUDGET) throw new IOException("Decoded frame pixel budget exceeded");
                }
                double scale = Math.min(1, (double)limits.dimension()/Math.max(w,h));
                int dw = Math.max(1,(int)(w*scale)), dh = Math.max(1,(int)(h*scale));
                var frames = new ArrayList<byte[]>(); var delays = new ArrayList<Integer>(); int elapsed = 0, stored = 0;
                BufferedImage canvas = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < count; i++) {
                    dimensions(reader.getWidth(i), reader.getHeight(i));
                    int delay = (int)Math.ceil(1000.0/limits.fps()), x = 0, y = 0; String disposal = "none";
                    if (reader.getFormatName().equalsIgnoreCase("gif")) {
                        var root = (IIOMetadataNode)reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                        var control = (IIOMetadataNode)root.getElementsByTagName("GraphicControlExtension").item(0);
                        if (control != null) { delay = Math.max(delay,Integer.parseInt(control.getAttribute("delayTime"))*10); disposal = control.getAttribute("disposalMethod"); }
                        var desc = (IIOMetadataNode)root.getElementsByTagName("ImageDescriptor").item(0);
                        x = Integer.parseInt(desc.getAttribute("imageLeftPosition")); y = Integer.parseInt(desc.getAttribute("imageTopPosition"));
                    }
                    elapsed += delay;
                    if (count > 1 && elapsed > limits.seconds()*1000) throw new IOException("Animation duration limit exceeded");
                    BufferedImage previous = null;
                    if (disposal.equals("restoreToPrevious")) { previous = new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB); var g=previous.createGraphics(); g.drawImage(canvas,0,0,null);g.dispose(); }
                    BufferedImage decoded = reader.read(i);
                    var g=canvas.createGraphics(); g.drawImage(decoded,x,y,null);g.dispose();
                    var resized = new BufferedImage(dw,dh,BufferedImage.TYPE_INT_ARGB);
                    g=resized.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(canvas,0,0,dw,dh,null);g.dispose();
                    var out=new ByteArrayOutputStream(); ImageIO.write(resized,"PNG",out); byte[] png=out.toByteArray(); stored+=png.length+8;
                    if (stored+8 > limits.fileBytes()) throw new IOException("Canonical asset byte limit exceeded");
                    frames.add(png); delays.add(delay);
                    if (disposal.equals("restoreToBackgroundColor")) { g=canvas.createGraphics();g.setComposite(AlphaComposite.Clear);g.fillRect(x,y,decoded.getWidth(),decoded.getHeight());g.dispose(); }
                    else if (previous != null) canvas=previous;
                }
                return checked(new AssetFrames(frames,delays,dw,dh),limits);
            } finally { reader.dispose(); }
        } catch (RuntimeException e) { throw new IOException("Malformed image: " + e.getMessage(),e); }
    }
    private static AssetFrames checked(AssetFrames f, AssetLimits l) throws IOException {
        if ((long)f.width*f.height*f.frames.size() > AssetLimits.PIXEL_BUDGET || f.encode().length > l.fileBytes()) throw new IOException("Canonical asset budget exceeded");
        return f;
    }
    public int frame(double seconds) {
        long duration = delays.stream().mapToLong(Integer::longValue).sum();
        long t = (long)(Math.max(0,Double.isFinite(seconds)?seconds:0)*1000) % duration;
        for(int i=0;i<delays.size();i++) { t-=delays.get(i); if(t<0) return i; } return 0;
    }
}
