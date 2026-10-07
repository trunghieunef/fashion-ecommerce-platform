package vn.fashion.catalog.media;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** CAT-03: validate and re-encode uploads (no metadata, EXIF orientation applied). Pure JDK, no Spring. */
public final class ImageProcessor {

    static final int MAX_BYTES = 5_242_880;
    static final int MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 25_000_000L;
    static final int APPROVED_EDGE = 2560;
    static final int THUMB_EDGE = 800;
    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    public record Rendition(byte[] bytes, String contentType, int width, int height, String sha256) {}

    public static class Rejected extends RuntimeException {
        private final String reasonCode;

        public Rejected(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }

    private ImageProcessor() {}

    public static Rendition approve(byte[] raw, String declaredType) {
        return approve(raw, declaredType, MAX_BYTES);
    }

    static Rendition approve(byte[] raw, String declaredType, int capBytes) {
        if (raw == null || raw.length < 1 || raw.length > MAX_BYTES) {
            throw new Rejected("IMAGE_TOO_LARGE");
        }
        if (!magicMatches(raw, declaredType)) {
            throw new Rejected("IMAGE_TYPE_NOT_ALLOWED");
        }
        BufferedImage img = decode(raw, declaredType);
        int orientation = "image/jpeg".equals(declaredType) ? exifOrientation(raw) : 1;
        return render(img, orientation, declaredType, APPROVED_EDGE, capBytes);
    }

    public static Rendition thumbnail(Rendition image) {
        return render(decode(image.bytes(), image.contentType()), 1, image.contentType(), THUMB_EDGE, MAX_BYTES);
    }

    public static Rendition verify(byte[] bytes, String contentType, int maxEdge) {
        try {
            if (bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES
                    || !magicMatches(bytes, contentType)) {
                throw new Rejected("APPROVED_OBJECT_INVALID");
            }
            BufferedImage img = decode(bytes, contentType);
            if (Math.max(img.getWidth(), img.getHeight()) > maxEdge) {
                throw new Rejected("APPROVED_OBJECT_INVALID");
            }
            return new Rendition(bytes, contentType, img.getWidth(), img.getHeight(), sha256(bytes));
        } catch (Rejected e) {
            throw new Rejected("APPROVED_OBJECT_INVALID");
        }
    }

    private static boolean magicMatches(byte[] b, String type) {
        if ("image/jpeg".equals(type)) {
            return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        }
        if ("image/png".equals(type)) {
            if (b.length < PNG_SIG.length) {
                return false;
            }
            for (int i = 0; i < PNG_SIG.length; i++) {
                if (b[i] != PNG_SIG[i]) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Dimensions are read from the header and checked BEFORE the pixel decode. */
    private static BufferedImage decode(byte[] raw, String type) {
        String format = "image/jpeg".equals(type) ? "jpeg" : "png";
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(format);
        if (!it.hasNext()) {
            throw new Rejected("IMAGE_DECODE_FAILED");
        }
        ImageReader reader = it.next();
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))) {
            reader.setInput(in, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            if (w < 1 || h < 1 || w > MAX_DIMENSION || h > MAX_DIMENSION || (long) w * h > MAX_PIXELS) {
                throw new Rejected("IMAGE_DIMENSIONS_INVALID");
            }
            AtomicBoolean warned = new AtomicBoolean();
            reader.addIIOReadWarningListener((src, msg) -> warned.set(true)); // truncated JPEG only warns
            BufferedImage img = reader.read(0);
            // JDK 21 decodes 4-band (CMYK/YCCK) JPEG into a custom CMYK image; colour would be wrong, so reject
            if (img == null || warned.get() || img.getColorModel().getNumColorComponents() > 3) {
                throw new Rejected("IMAGE_DECODE_FAILED");
            }
            return img;
        } catch (Rejected e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new Rejected("IMAGE_DECODE_FAILED");
        } finally {
            reader.dispose();
        }
    }

    private static Rendition render(BufferedImage src, int orientation, String type, int maxEdge, int cap) {
        boolean swap = orientation >= 5;
        int dw = swap ? src.getHeight() : src.getWidth();
        int dh = swap ? src.getWidth() : src.getHeight();
        double s = Math.min(1.0, (double) maxEdge / Math.max(dw, dh));
        int tw = Math.max(1, (int) Math.round(dw * s));
        int th = Math.max(1, (int) Math.round(dh * s));
        boolean alpha = "image/png".equals(type) && src.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(tw, th, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        AffineTransform at = AffineTransform.getScaleInstance((double) tw / dw, (double) th / dh);
        at.concatenate(orientTransform(orientation, src.getWidth(), src.getHeight()));
        var g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, at, null);
        } finally {
            g.dispose();
        }
        byte[] bytes = encode(out, type, cap);
        return new Rendition(bytes, type, tw, th, sha256(bytes));
    }

    /** EXIF orientation 1..8 -> transform (AffineTransform m00,m10,m01,m11,m02,m12). */
    private static AffineTransform orientTransform(int o, int w, int h) {
        return switch (o) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    private static byte[] encode(BufferedImage img, String type, int cap) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("image/jpeg".equals(type) ? "jpeg" : "png").next();
        var sink = new CappedOutput(cap);
        try (var ios = new MemoryCacheImageOutputStream(sink)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("image/jpeg".equals(type)) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85f);
            }
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param); // null metadata: nothing carried over
            ios.flush();
        } catch (IOException e) {
            throw new Rejected("IMAGE_DECODE_FAILED");
        } finally {
            writer.dispose();
        }
        return sink.toByteArray();
    }

    private static final class CappedOutput extends ByteArrayOutputStream {
        private final int cap;

        CappedOutput(int cap) {
            this.cap = cap;
        }

        @Override
        public synchronized void write(int b) {
            check(1);
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            check(len);
            super.write(b, off, len);
        }

        private void check(int add) {
            if ((long) count + add > cap) {
                throw new Rejected("IMAGE_OUTPUT_TOO_LARGE");
            }
        }
    }

    /** IFD0 tag 0x0112 from the APP1 Exif segment; anything odd -> 1. All reads bounds-checked. */
    static int exifOrientation(byte[] b) {
        try {
            int p = 2;
            while (p + 4 <= b.length && (b[p] & 0xFF) == 0xFF) {
                int marker = b[p + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    break;
                }
                int len = u16(b, p + 2, true);
                if (len < 2 || p + 2 + len > b.length) {
                    break;
                }
                if (marker == 0xE1 && len >= 16 && b[p + 4] == 'E' && b[p + 5] == 'x' && b[p + 6] == 'i'
                        && b[p + 7] == 'f' && b[p + 8] == 0 && b[p + 9] == 0) {
                    int tiff = p + 10;
                    int end = p + 2 + len;
                    boolean be = b[tiff] == 'M' && b[tiff + 1] == 'M';
                    if (!be && !(b[tiff] == 'I' && b[tiff + 1] == 'I')) {
                        return 1;
                    }
                    long ifd = u32(b, tiff + 4, be);
                    long entries = ifd + 2 <= end - tiff ? u16(b, (int) (tiff + ifd), be) : 0;
                    for (int i = 0; i < entries; i++) {
                        long e = tiff + ifd + 2 + 12L * i;
                        if (e + 12 > end) {
                            return 1;
                        }
                        if (u16(b, (int) e, be) == 0x0112) {
                            int v = u16(b, (int) e + 8, be);
                            return v >= 1 && v <= 8 ? v : 1;
                        }
                    }
                    return 1;
                }
                p += 2 + len;
            }
        } catch (RuntimeException e) {
            // unreadable EXIF is never a reason to reject
        }
        return 1;
    }

    private static int u16(byte[] b, int o, boolean be) {
        int x = b[o] & 0xFF;
        int y = b[o + 1] & 0xFF;
        return be ? x << 8 | y : y << 8 | x;
    }

    private static long u32(byte[] b, int o, boolean be) {
        long hi = u16(b, be ? o : o + 2, be);
        long lo = u16(b, be ? o + 2 : o, be);
        return hi << 16 | lo;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
