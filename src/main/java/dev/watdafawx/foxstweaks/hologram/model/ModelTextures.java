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

    public static ModelData.Texture fromBytes(String name, byte[] bytes) throws IOException {
        if (bytes.length > ModelData.MAX_TEXTURE_BYTES * 2)
            throw new IOException("A texture file is too large");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null)
            throw new IOException("A texture is not a PNG or JPEG image");
        return fromImage(name, image);
    }

    public static ModelData.Texture fromImage(String name, BufferedImage image) throws IOException {
        int w = image.getWidth(), h = image.getHeight();
        if (w < 1 || h < 1)
            throw new IOException("A texture is empty");
        int max = ModelData.MAX_TEXTURE_DIMENSION;
        BufferedImage out = image;
        if (w > max || h > max || image.getType() != BufferedImage.TYPE_INT_ARGB) {
            double scale = Math.min(1.0, (double) max / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * scale)), nh = Math.max(1, (int) Math.round(h * scale));
            out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < 1.0
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, 0, 0, nw, nh, null);
            } finally {
                g.dispose();
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", png))
            throw new IOException("Could not encode a texture");
        ModelData.Texture t = new ModelData.Texture();
        t.name = name == null ? "" : name;
        t.width = out.getWidth();
        t.height = out.getHeight();
        t.png = png.toByteArray();
        if (t.png.length > ModelData.MAX_TEXTURE_BYTES)
            throw new IOException("A texture is too large even after scaling");
        return t;
    }
}
