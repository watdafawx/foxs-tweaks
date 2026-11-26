package dev.watdafawx.foxstweaks.client.asset;

import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.asset.AssetLimits;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Makes a picture fit the server's upload rules before it is sent, the in-game counterpart of the web page's
 * {@code fit.ts}: a still that is too big or too wide is scaled down and re-encoded, an animated GIF that is too long,
 * too fast or too heavy is thinned out into a frame bundle. Anything that already fits is returned untouched, so a
 * normal PNG keeps its exact bytes.
 *
 * <p>Pure Java (ImageIO, the same reader the server validates with), no Minecraft classes, so it runs on a worker thread.
 */
public final class HologramAssetFit {
    /** What the server's decoder refuses outright (see {@link AssetFrames#sanitize}). */
    private static final int BOMB_SIDE = 4096;
    private static final long BOMB_PIXELS = 4_194_304L;
    /** Largest source we will even try to open. */
    private static final long OPEN_PIXELS = 100_000_000L;
    /** The server re-encodes every frame, so aim below its byte cap. */
    private static final double STILL_TARGET = 0.85;
    private static final double BUNDLE_TARGET = 0.70;

    public record Result(byte[] bytes, String note) {
    }

    private HologramAssetFit() {
    }

    /**
     * @param bytes a PNG, JPEG, BMP or GIF (anything ImageIO opens)
     * @return the bytes to upload and a short note on what changed (empty when nothing did)
     * @throws IOException when the picture cannot be read or cannot be made to fit
     */
    public static Result fit(byte[] bytes, AssetLimits limits) throws IOException {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delaysMs = new ArrayList<>();
        int width;
        int height;
        boolean gif;
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
                throw new IOException("Cannot read this picture. Use PNG, JPEG or GIF (convert WebP first)");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, false);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > OPEN_PIXELS)
                    throw new IOException("Picture is too large to open (" + width + "x" + height + ")");
                gif = "gif".equalsIgnoreCase(reader.getFormatName());
                if (gif) {
                    readGif(reader, frames, delaysMs);
                } else {
                    frames.add(toArgb(reader.read(0)));
                }
            } finally {
                reader.dispose();
            }
        }
        if (frames.isEmpty())
            throw new IOException("The picture has no frames");

        if (frames.size() == 1)
            return fitStill(bytes, frames.getFirst(), width, height, limits);
        return fitAnimation(bytes, frames, delaysMs, width, height, limits);
    }

    // ---------------------------------------------------------------- stills

    private static Result fitStill(byte[] original, BufferedImage image, int width, int height, AssetLimits limits)
            throws IOException {
        int maxSide = Math.max(16, Math.min(limits.dimension(), BOMB_SIDE));
        boolean fine = original.length <= limits.fileBytes() && width <= BOMB_SIDE && height <= BOMB_SIDE
                && (long) width * height <= BOMB_PIXELS;
        if (fine)
            return new Result(original, "");

        int[] size = within(width, height, maxSide, BOMB_PIXELS);
        boolean alpha = hasAlpha(image);
        long target = (long) (limits.fileBytes() * STILL_TARGET);
        double factor = 1.0;
        for (int round = 0; round < 8; round++) {
            int w = Math.max(1, (int) Math.round(size[0] * factor));
            int h = Math.max(1, (int) Math.round(size[1] * factor));
            BufferedImage scaled = scale(image, w, h);
            byte[] out = png(scaled);
            if (out.length > target && !alpha)
                out = jpeg(scaled, out.length > target * 3 ? 0.7F : 0.85F);
            if (out.length <= target)
                return new Result(out, "resized " + width + "x" + height + " to " + w + "x" + h);
            factor *= 0.75;
        }
        throw new IOException("Could not shrink this picture under the " + limits.fileBytes() / 1024 + " KB limit");
    }

    // ---------------------------------------------------------------- animations

    private static Result fitAnimation(byte[] original, List<BufferedImage> frames, List<Integer> delaysMs, int width,
                                       int height, AssetLimits limits) throws IOException {
        int minDelay = (int) Math.ceil(1000.0 / Math.max(1, limits.fps()));
        long total = 0;
        for (int d : delaysMs)
            total += Math.max(d, minDelay);
        boolean fine = original.length <= limits.fileBytes() && width <= Math.min(limits.dimension(), BOMB_SIDE)
                && height <= Math.min(limits.dimension(), BOMB_SIDE) && (long) width * height <= BOMB_PIXELS
                && frames.size() <= AssetLimits.MAX_FRAMES && total <= limits.seconds() * 1000L
                && delaysMs.stream().allMatch(d -> d >= minDelay);
        if (fine)
            return new Result(original, "");

        // Thin the frames first: merge the time of dropped frames into the kept ones and cut at the length cap.
        List<BufferedImage> kept = new ArrayList<>();
        List<Integer> keptDelay = new ArrayList<>();
        long budget = (long) (limits.seconds() * 1000L * 0.98);
        long used = 0;
        int carry = 0;
        for (int i = 0; i < frames.size(); i++) {
            carry += Math.max(delaysMs.get(i), 10);
            boolean last = i == frames.size() - 1;
            if (carry < minDelay && !last)
                continue;
            int d = Math.max(carry, minDelay);
            if (used + d > budget)
                break;
            kept.add(frames.get(i));
            keptDelay.add(d);
            used += d;
            carry = 0;
        }
        if (kept.isEmpty())
            throw new IOException("Could not fit this animation inside " + limits.seconds() + " seconds");
        while (kept.size() > AssetLimits.MAX_FRAMES) {
            kept.removeLast();
            keptDelay.removeLast();
        }

        boolean alpha = kept.stream().anyMatch(HologramAssetFit::hasAlpha);
        int maxSide = Math.max(16, Math.min(limits.dimension(), BOMB_SIDE));
        int[] size = within(width, height, maxSide, BOMB_PIXELS);
        long target = (long) (limits.fileBytes() * BUNDLE_TARGET);
        double factor = 1.0;
        for (int round = 0; round < 8; round++) {
            int w = Math.max(1, (int) Math.round(size[0] * factor));
            int h = Math.max(1, (int) Math.round(size[1] * factor));
            List<byte[]> encoded = new ArrayList<>();
            long sum = 0;
            for (BufferedImage f : kept) {
                BufferedImage s = scale(f, w, h);
                byte[] b = alpha ? png(s) : jpeg(s, 0.8F);
                encoded.add(b);
                sum += b.length;
                if (sum > target)
                    break;
            }
            if (sum <= target) {
                byte[] bundle = new AssetFrames(encoded, keptDelay, w, h).encode();
                if (bundle.length <= target)
                    return new Result(bundle, "thinned " + frames.size() + " frames " + width + "x" + height + " to "
                            + kept.size() + " frames " + w + "x" + h);
            }
            factor *= 0.8;
        }
        throw new IOException("Could not shrink this animation under the " + limits.fileBytes() / 1024 + " KB limit");
    }

    /** All frames drawn onto the logical screen, honouring offsets, disposal and transparency. */
    private static void readGif(ImageReader reader, List<BufferedImage> out, List<Integer> delaysMs) throws IOException {
        int count = reader.getNumImages(true);
        int sw = 0;
        int sh = 0;
        IIOMetadata stream = reader.getStreamMetadata();
        if (stream != null) {
            Node root = stream.getAsTree("javax_imageio_gif_stream_1.0");
            Node lsd = child(root, "LogicalScreenDescriptor");
            if (lsd != null) {
                sw = attr(lsd, "logicalScreenWidth", 0);
                sh = attr(lsd, "logicalScreenHeight", 0);
            }
        }
        if (sw < 1 || sh < 1) {
            sw = reader.getWidth(0);
            sh = reader.getHeight(0);
        }
        if (count > 4000)
            throw new IOException("This GIF has too many frames to open");

        BufferedImage canvas = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        BufferedImage previous = null;
        int lastDisposal = 0;
        int lastX = 0;
        int lastY = 0;
        int lastW = 0;
        int lastH = 0;
        for (int i = 0; i < count; i++) {
            BufferedImage frame = reader.read(i);
            Node meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node desc = child(meta, "ImageDescriptor");
            Node gce = child(meta, "GraphicControlExtension");
            int x = desc == null ? 0 : attr(desc, "imageLeftPosition", 0);
            int y = desc == null ? 0 : attr(desc, "imageTopPosition", 0);
            int delay = gce == null ? 10 : attr(gce, "delayTime", 10);
            String disposal = gce == null ? "none" : ((IIOMetadataNode) gce).getAttribute("disposalMethod");

            // Undo the previous frame's disposal before drawing this one.
            if (lastDisposal == 2) {
                Graphics2D g = canvas.createGraphics();
                g.setComposite(AlphaComposite.Clear);
                g.fillRect(lastX, lastY, lastW, lastH);
                g.dispose();
            } else if (lastDisposal == 3 && previous != null) {
                Graphics2D g = canvas.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.drawImage(previous, 0, 0, null);
                g.dispose();
            }
            int d = disposal.equals("restoreToBackgroundColor") ? 2 : disposal.equals("restoreToPrevious") ? 3 : 0;
            if (d == 3) {
                previous = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = previous.createGraphics();
                g.drawImage(canvas, 0, 0, null);
                g.dispose();
            }
            Graphics2D g = canvas.createGraphics();
            g.drawImage(frame, x, y, null);
            g.dispose();

            BufferedImage copy = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D cg = copy.createGraphics();
            cg.setComposite(AlphaComposite.Src);
            cg.drawImage(canvas, 0, 0, null);
            cg.dispose();
            out.add(copy);
            delaysMs.add(Math.max(1, delay) * 10);

            lastDisposal = d;
            lastX = x;
            lastY = y;
            lastW = frame.getWidth();
            lastH = frame.getHeight();
        }
    }

    // ---------------------------------------------------------------- pixels

    private static Node child(Node parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name))
                return n;
        }
        return null;
    }

    private static int attr(Node node, String name, int fallback) {
        try {
            return Integer.parseInt(((IIOMetadataNode) node).getAttribute(name));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Largest size with the same shape under both the side and the pixel cap (never larger than the source). */
    static int[] within(int w, int h, int maxSide, long maxPixels) {
        double s = Math.min(1.0, Math.min(maxSide / (double) Math.max(w, h), Math.sqrt(maxPixels / ((double) w * h))));
        return new int[]{Math.max(1, (int) Math.floor(w * s)), Math.max(1, (int) Math.floor(h * s))};
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB)
            return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static boolean hasAlpha(BufferedImage image) {
        if (!image.getColorModel().hasAlpha())
            return false;
        int step = Math.max(1, image.getWidth() * image.getHeight() / 40000);
        int w = image.getWidth();
        for (int i = 0; i < w * image.getHeight(); i += step) {
            if ((image.getRGB(i % w, i / w) >>> 24) < 255)
                return true;
        }
        return false;
    }

    /** Scales in halving steps, which keeps a big reduction from turning into nearest-neighbour noise. */
    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        int cw = src.getWidth();
        int ch = src.getHeight();
        if (cw == w && ch == h)
            return src;
        while (cw > w * 2 || ch > h * 2) {
            cw = Math.max(w, cw / 2);
            ch = Math.max(h, ch / 2);
            cur = draw(cur, cw, ch);
        }
        return draw(cur, w, h);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", out))
            throw new IOException("No PNG writer");
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgb, null, null), param);
            stream.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
