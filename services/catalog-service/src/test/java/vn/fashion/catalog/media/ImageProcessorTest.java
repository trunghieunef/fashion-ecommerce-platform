package vn.fashion.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageProcessorTest {

    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";

    private static BufferedImage canvas(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] write(BufferedImage img, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static String reason(Runnable r) {
        try {
            r.run();
        } catch (ImageProcessor.Rejected e) {
            return e.reasonCode();
        }
        return "NO_EXCEPTION";
    }

    private static BufferedImage read(byte[] b) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    /** Valid signature + IHDR (valid CRC) + garbage IDAT, no IEND. */
    private static byte[] pngHeaderOnly(int w, int h) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;
        ihdr[9] = 2;
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", new byte[] {1, 2, 3, 4, 5});
        return out.toByteArray();
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);
        byte[] td = new byte[4 + data.length];
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, td, 0, 4);
        System.arraycopy(data, 0, td, 4, data.length);
        out.writeBytes(td);
        CRC32 crc = new CRC32();
        crc.update(td);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.writeBytes(c);
    }

    /** JPEG with an APP1 Exif segment (big-endian TIFF, IFD0 orientation) after SOI, plus trailing bytes after EOI. */
    private static byte[] jpegWithExif(BufferedImage img, int orientation, String trailing) throws IOException {
        byte[] jpg = write(img, "jpeg");
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 2,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
            (byte) 0x88, 0x25, 0, 2, 0, 0, 0, 4, 'G', 'P', 'S', 0,
            0, 0, 0, 0};
        byte[] hdr = {'E', 'x', 'i', 'f', 0, 0};
        int len = 2 + hdr.length + tiff.length;
        var out = new ByteArrayOutputStream();
        out.write(jpg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(len >> 8);
        out.write(len);
        out.writeBytes(hdr);
        out.writeBytes(tiff);
        out.write(jpg, 2, jpg.length - 2);
        out.writeBytes(trailing.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test
    void approvesPngAndJpegKeepingType() throws Exception {
        var png = ImageProcessor.approve(write(canvas(300, 200, BufferedImage.TYPE_INT_RGB), "png"), PNG);
        var jpg = ImageProcessor.approve(write(canvas(300, 200, BufferedImage.TYPE_INT_RGB), "jpeg"), JPEG);
        assertThat(png.contentType()).isEqualTo(PNG);
        assertThat(jpg.contentType()).isEqualTo(JPEG);
        for (var r : new ImageProcessor.Rendition[] {png, jpg}) {
            assertThat(r.sha256()).isEqualTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.bytes())));
            assertThat(r.width()).isEqualTo(300);
            assertThat(r.height()).isEqualTo(200);
        }
    }

    @Test
    void downscalesLongEdgeTo2560WithoutUpscale() throws Exception {
        var big = ImageProcessor.approve(write(canvas(4000, 1000, BufferedImage.TYPE_INT_RGB), "png"), PNG);
        assertThat(big.width()).isEqualTo(2560);
        assertThat(big.height()).isEqualTo(640);
        var small = ImageProcessor.approve(write(canvas(300, 200, BufferedImage.TYPE_INT_RGB), "png"), PNG);
        assertThat(small.width()).isEqualTo(300);
        assertThat(small.height()).isEqualTo(200);
    }

    @Test
    void thumbnailLongEdge800() throws Exception {
        var big = ImageProcessor.approve(write(canvas(4000, 1000, BufferedImage.TYPE_INT_RGB), "png"), PNG);
        var thumb = ImageProcessor.thumbnail(big);
        assertThat(thumb.width()).isEqualTo(800);
        assertThat(thumb.height()).isEqualTo(200);
        assertThat(thumb.contentType()).isEqualTo(PNG);
    }

    @Test
    void rejectsMagicMismatch() throws Exception {
        byte[] png = write(canvas(10, 10, BufferedImage.TYPE_INT_RGB), "png");
        assertThat(reason(() -> ImageProcessor.approve(png, JPEG))).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
        byte[] gif = write(canvas(10, 10, BufferedImage.TYPE_INT_RGB), "gif");
        assertThat(reason(() -> ImageProcessor.approve(gif, PNG))).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
        assertThat(reason(() -> ImageProcessor.approve(gif, "image/gif"))).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
        assertThat(reason(() -> ImageProcessor.approve(new byte[0], PNG))).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(reason(() -> ImageProcessor.approve(new byte[5_242_881], PNG))).isEqualTo("IMAGE_TOO_LARGE");
    }

    @Test
    void rejectsDimensionsBeforeDecode() {
        assertThat(reason(() -> ImageProcessor.approve(pngHeaderOnly(9000, 10), PNG)))
                .isEqualTo("IMAGE_DIMENSIONS_INVALID");
    }

    @Test
    void rejectsPixelBomb() {
        assertThat(reason(() -> ImageProcessor.approve(pngHeaderOnly(8192, 8192), PNG)))
                .isEqualTo("IMAGE_DIMENSIONS_INVALID");
    }

    @Test
    void rejectsTruncatedAndCmyk() throws Exception {
        byte[] jpg = write(canvas(400, 400, BufferedImage.TYPE_INT_RGB), "jpeg");
        byte[] half = Arrays.copyOf(jpg, jpg.length / 2);
        assertThat(reason(() -> ImageProcessor.approve(half, JPEG))).isEqualTo("IMAGE_DECODE_FAILED");

        // 4-band JPEG (CMYK-like) written through the ImageIO raster path
        Raster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 64, 64, 4, null);
        var out = new ByteArrayOutputStream();
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var ios = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(raster, null, null), null);
        } finally {
            w.dispose();
        }
        byte[] plain = out.toByteArray();
        // real CMYK files carry an Adobe APP14 marker (transform 0); insert one after SOI
        byte[] adobe = {(byte) 0xFF, (byte) 0xEE, 0, 14, 'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 0};
        var withAdobe = new ByteArrayOutputStream();
        withAdobe.write(plain, 0, 2);
        withAdobe.writeBytes(adobe);
        withAdobe.write(plain, 2, plain.length - 2);
        byte[] cmyk = withAdobe.toByteArray();
        assertThat(reason(() -> ImageProcessor.approve(cmyk, JPEG))).isEqualTo("IMAGE_DECODE_FAILED");
    }

    @Test
    void stripsExifAndTrailingPayload() throws Exception {
        byte[] src = jpegWithExif(canvas(120, 80, BufferedImage.TYPE_INT_RGB), 1, "TRAILING_PAYLOAD_XYZ");
        assertThat(new String(src, StandardCharsets.ISO_8859_1)).contains("Exif").contains("TRAILING_PAYLOAD_XYZ");
        var r = ImageProcessor.approve(src, JPEG);
        String s = new String(r.bytes(), StandardCharsets.ISO_8859_1);
        assertThat(s).doesNotContain("Exif").doesNotContain("GPS").doesNotContain("TRAILING_PAYLOAD_XYZ");
        assertThat(read(r.bytes())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-IEND", "truncated-CRC", "wrong-CRC", "oversized-chunk"})
    void rejectsMalformedPngChunks(String corruption) throws Exception {
        byte[] png = write(canvas(40, 20, BufferedImage.TYPE_INT_RGB), "png");
        byte[] broken = switch (corruption) {
            case "missing-IEND" -> Arrays.copyOf(png, png.length - 12);
            case "truncated-CRC" -> Arrays.copyOf(png, png.length - 1);
            default -> png.clone();
        };
        if (corruption.equals("wrong-CRC")) broken[29] ^= 1;
        if (corruption.equals("oversized-chunk")) putInt(broken, 33, Integer.MAX_VALUE);
        assertThat(reason(() -> ImageProcessor.approve(broken, PNG))).isEqualTo("IMAGE_DECODE_FAILED");
        assertThat(reason(() -> ImageProcessor.verify(broken, PNG, 2560))).isEqualTo("APPROVED_OBJECT_INVALID");
    }

    @Test
    void stripsPngTrailingPayload() throws Exception {
        byte[] png = write(canvas(40, 20, BufferedImage.TYPE_INT_RGB), "png");
        var input = new ByteArrayOutputStream();
        input.writeBytes(png);
        input.writeBytes("TRAILING_PAYLOAD_XYZ".getBytes(StandardCharsets.US_ASCII));
        var approved = ImageProcessor.approve(input.toByteArray(), PNG);
        assertThat(new String(approved.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("TRAILING_PAYLOAD_XYZ");
        assertThat(read(approved.bytes()).getWidth()).isEqualTo(40);
    }

    @Test
    void appliesExifOrientation6() throws Exception {
        byte[] src = jpegWithExif(canvas(200, 100, BufferedImage.TYPE_INT_RGB), 6, "");
        var r = ImageProcessor.approve(src, JPEG);
        assertThat(r.width()).isEqualTo(100);
        assertThat(r.height()).isEqualTo(200);
        // unreadable EXIF (APP1 length points past the data) -> treated as 1, never rejected
        assertThat(ImageProcessor.exifOrientation(Arrays.copyOf(src, 30))).isEqualTo(1);
    }

    @Test
    void exifOrientationMovesMarkedCornerToExpectedPlace() throws Exception {
        BufferedImage marked = canvas(20, 10, BufferedImage.TYPE_INT_RGB);
        var g = marked.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 8, 6); // top-left block
        g.dispose();
        // orientation -> {width, height, probe x, probe y} of the block centre after correction
        int[][] cases = {{3, 20, 10, 16, 7}, {6, 10, 20, 6, 3}, {8, 10, 20, 3, 16}};
        for (int[] c : cases) {
            var r = ImageProcessor.approve(jpegWithExif(marked, c[0], ""), JPEG);
            assertThat(r.width()).as("w" + c[0]).isEqualTo(c[1]);
            assertThat(r.height()).as("h" + c[0]).isEqualTo(c[2]);
            Color px = new Color(read(r.bytes()).getRGB(c[3], c[4]));
            assertThat(px.getBlue()).as("blue block orientation " + c[0]).isGreaterThan(150);
            assertThat(px.getRed()).as("blue block orientation " + c[0]).isLessThan(100);
        }
    }

    @Test
    void acceptsAlphaPalette16BitPngAndGrayscaleJpeg() throws Exception {
        BufferedImage alpha = canvas(50, 40, BufferedImage.TYPE_INT_ARGB);
        byte[] lut = {0, (byte) 255};
        BufferedImage palette = new BufferedImage(50, 40, BufferedImage.TYPE_BYTE_BINARY,
                new IndexColorModel(1, 2, lut, lut, lut));
        BufferedImage sixteen = new BufferedImage(50, 40, BufferedImage.TYPE_USHORT_GRAY);
        BufferedImage gray = new BufferedImage(50, 40, BufferedImage.TYPE_BYTE_GRAY);
        Object[][] cases = {{alpha, "png", PNG}, {palette, "png", PNG}, {sixteen, "png", PNG}, {gray, "jpeg", JPEG}};
        for (Object[] c : cases) {
            var r = ImageProcessor.approve(write((BufferedImage) c[0], (String) c[1]), (String) c[2]);
            BufferedImage back = read(r.bytes());
            assertThat(back.getWidth()).isEqualTo(50);
            assertThat(back.getHeight()).isEqualTo(40);
        }
        var a = ImageProcessor.approve(write(alpha, "png"), PNG);
        assertThat(read(a.bytes()).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void rejectsOversizedOutput() throws Exception {
        byte[] png = write(canvas(300, 200, BufferedImage.TYPE_INT_RGB), "png");
        assertThat(reason(() -> ImageProcessor.approve(png, PNG, 10))).isEqualTo("IMAGE_OUTPUT_TOO_LARGE");
    }

    @Test
    void verifyRejectsApprovedObjectOverEdge() throws Exception {
        byte[] png = write(canvas(3000, 10, BufferedImage.TYPE_INT_RGB), "png");
        assertThat(reason(() -> ImageProcessor.verify(png, PNG, 2560))).isEqualTo("APPROVED_OBJECT_INVALID");
        assertThat(ImageProcessor.verify(png, PNG, 3000).width()).isEqualTo(3000);
        assertThat(reason(() -> ImageProcessor.verify(png, JPEG, 3000))).isEqualTo("APPROVED_OBJECT_INVALID");
    }
}
