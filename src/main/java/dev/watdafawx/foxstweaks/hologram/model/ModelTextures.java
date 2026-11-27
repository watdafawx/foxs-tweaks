package dev.watdafawx.foxstweaks.hologram.model;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Decodes any image a model came with and re-encodes it as a capped PNG. Server side only (ImageIO). */
public final class ModelTextures {
    private ModelTextures() {
    }

    private static final int MAX_INPUT_TEXTURE_BYTES = 64 * 1024 * 1024;
    private static final int TARGET_DIMENSION = 1024;
    private static final int TARGET_PNG_BYTES = 2 * 1024 * 1024;

    public static ModelData.Texture fromBytes(String name, byte[] bytes) throws IOException {
        if (bytes.length > MAX_INPUT_TEXTURE_BYTES)
            throw new IOException("A texture file is too large (max 64 MB)");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null)
            throw new IOException("A texture is not a PNG or JPEG image");
        return fromImage(name, image);
    }

    public static ModelData.Texture fromImage(String name, BufferedImage image) throws IOException {
        int w = image.getWidth(), h = image.getHeight();
        if (w < 1 || h < 1)
            throw new IOException("A texture is empty");
        int cap = Math.min(ModelData.MAX_TEXTURE_DIMENSION, TARGET_DIMENSION);
        byte[] encoded = null;
        int outW = w, outH = h;
        for (int attempt = 0; attempt < 4; attempt++) {
            BufferedImage scaled = scale(image, cap);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, "png", png))
                throw new IOException("Could not encode a texture");
            encoded = png.toByteArray();
            outW = scaled.getWidth();
            outH = scaled.getHeight();
            if (encoded.length <= TARGET_PNG_BYTES || Math.max(outW, outH) <= 256)
                break;
            cap = Math.max(256, cap / 2);
        }
        if (encoded == null || encoded.length > ModelData.MAX_TEXTURE_BYTES)
            throw new IOException("A texture is too large even after scaling");
        ModelData.Texture t = new ModelData.Texture();
        t.name = name == null ? "" : name;
        t.width = outW;
        t.height = outH;
        t.png = encoded;
        return t;
    }

    private static BufferedImage scale(BufferedImage image, int max) {
        int w = image.getWidth(), h = image.getHeight();
        if (w <= max && h <= max && image.getType() == BufferedImage.TYPE_INT_ARGB)
            return image;
        double s = Math.min(1.0, (double) max / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s < 1.0
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
