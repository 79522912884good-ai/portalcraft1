package gg.portalcraft;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Декодер текстур Source (.vtf) в ARGB. Берётся самый большой mip первого кадра. */
public final class Vtf {
    public final int width, height;
    public final int[] argb;

    private Vtf(int w, int h, int[] argb) {
        this.width = w;
        this.height = h;
        this.argb = argb;
    }

    // Форматы Source
    private static final int RGBA8888 = 0, ABGR8888 = 1, RGB888 = 2, BGR888 = 3, I8 = 5, IA88 = 6, A8 = 8,
            ARGB8888 = 11, BGRA8888 = 12, DXT1 = 13, DXT3 = 14, DXT5 = 15, BGRX8888 = 16, DXT1_ONEBIT = 20;

    private static int imageSize(int fmt, int w, int h) throws IOException {
        w = Math.max(1, w);
        h = Math.max(1, h);
        switch (fmt) {
            case RGBA8888, ABGR8888, ARGB8888, BGRA8888, BGRX8888: return w * h * 4;
            case RGB888, BGR888: return w * h * 3;
            case I8, A8: return w * h;
            case IA88: return w * h * 2;
            case DXT1, DXT1_ONEBIT: return ((w + 3) / 4) * ((h + 3) / 4) * 8;
            case DXT3, DXT5: return ((w + 3) / 4) * ((h + 3) / 4) * 16;
            default: throw new IOException("unsupported VTF format " + fmt);
        }
    }

    public static Vtf decode(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x00465456) throw new IOException("not a VTF"); // "VTF\0"
        int headerSize = b.getInt(12);
        int w = b.getShort(16) & 0xFFFF;
        int h = b.getShort(18) & 0xFFFF;
        int hiFmt = b.getInt(52);
        int mips = b.get(56) & 0xFF;
        int loFmt = b.getInt(57);
        int loW = b.get(61) & 0xFF;
        int loH = b.get(62) & 0xFF;
        int frames = b.getShort(24) & 0xFFFF;

        long off = headerSize;
        if (loFmt != -1 && loW > 0 && loH > 0) off += imageSize(loFmt, loW, loH);
        // данные идут от самого маленького mip к самому большому
        for (int m = mips - 1; m >= 1; m--) {
            off += (long) imageSize(hiFmt, w >> m, h >> m) * Math.max(1, frames);
        }
        int[] out = new int[w * h];
        convert(b, (int) off, hiFmt, w, h, out);
        return new Vtf(w, h, out);
    }

    private static void convert(ByteBuffer b, int o, int fmt, int w, int h, int[] out) throws IOException {
        int n = w * h;
        switch (fmt) {
            case RGBA8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = argb(b.get(o + 3), b.get(o), b.get(o + 1), b.get(o + 2));
                break;
            case ABGR8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = argb(b.get(o), b.get(o + 3), b.get(o + 2), b.get(o + 1));
                break;
            case ARGB8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = argb(b.get(o), b.get(o + 1), b.get(o + 2), b.get(o + 3));
                break;
            case BGRA8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = argb(b.get(o + 3), b.get(o + 2), b.get(o + 1), b.get(o));
                break;
            case BGRX8888:
                for (int i = 0; i < n; i++, o += 4)
                    out[i] = argb((byte) 255, b.get(o + 2), b.get(o + 1), b.get(o));
                break;
            case RGB888:
                for (int i = 0; i < n; i++, o += 3)
                    out[i] = argb((byte) 255, b.get(o), b.get(o + 1), b.get(o + 2));
                break;
            case BGR888:
                for (int i = 0; i < n; i++, o += 3)
                    out[i] = argb((byte) 255, b.get(o + 2), b.get(o + 1), b.get(o));
                break;
            case I8:
                for (int i = 0; i < n; i++, o++) out[i] = argb((byte) 255, b.get(o), b.get(o), b.get(o));
                break;
            case A8:
                for (int i = 0; i < n; i++, o++) out[i] = argb(b.get(o), (byte) 255, (byte) 255, (byte) 255);
                break;
            case IA88:
                for (int i = 0; i < n; i++, o += 2) out[i] = argb(b.get(o + 1), b.get(o), b.get(o), b.get(o));
                break;
            case DXT1, DXT1_ONEBIT, DXT3, DXT5:
                dxt(b, o, fmt, w, h, out);
                break;
            default:
                throw new IOException("unsupported VTF format " + fmt);
        }
    }

    private static int argb(byte a, byte r, byte g, byte bl) {
        return ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (bl & 255);
    }

    private static int rgb565(int c, int[] rgb) {
        rgb[0] = ((c >> 11) & 31) * 255 / 31;
        rgb[1] = ((c >> 5) & 63) * 255 / 63;
        rgb[2] = (c & 31) * 255 / 31;
        return c;
    }

    private static void dxt(ByteBuffer b, int o, int fmt, int w, int h, int[] out) {
        int bw = (w + 3) / 4, bh = (h + 3) / 4;
        int[] c0 = new int[3], c1 = new int[3];
        int[][] pal = new int[4][4]; // r,g,b,a
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                long alpha = 0;
                int[] a8 = new int[16];
                if (fmt == DXT3) {
                    long bits = b.getLong(o);
                    for (int i = 0; i < 16; i++) a8[i] = (int) ((bits >>> (4 * i)) & 15) * 17;
                    o += 8;
                } else if (fmt == DXT5) {
                    int a0 = b.get(o) & 255, a1 = b.get(o + 1) & 255;
                    long bits = 0;
                    for (int i = 0; i < 6; i++) bits |= (long) (b.get(o + 2 + i) & 255) << (8 * i);
                    int[] ap = new int[8];
                    ap[0] = a0;
                    ap[1] = a1;
                    if (a0 > a1) {
                        for (int i = 1; i < 7; i++) ap[i + 1] = ((7 - i) * a0 + i * a1) / 7;
                    } else {
                        for (int i = 1; i < 5; i++) ap[i + 1] = ((5 - i) * a0 + i * a1) / 5;
                        ap[6] = 0;
                        ap[7] = 255;
                    }
                    for (int i = 0; i < 16; i++) a8[i] = ap[(int) ((bits >>> (3 * i)) & 7)];
                    o += 8;
                } else {
                    java.util.Arrays.fill(a8, 255);
                }
                int q0 = b.getShort(o) & 0xFFFF, q1 = b.getShort(o + 2) & 0xFFFF;
                int idx = b.getInt(o + 4);
                o += 8;
                rgb565(q0, c0);
                rgb565(q1, c1);
                for (int k = 0; k < 3; k++) {
                    pal[0][k] = c0[k];
                    pal[1][k] = c1[k];
                }
                boolean fourColor = fmt != DXT1 && fmt != DXT1_ONEBIT ? true : q0 > q1;
                if (fourColor) {
                    for (int k = 0; k < 3; k++) {
                        pal[2][k] = (2 * c0[k] + c1[k]) / 3;
                        pal[3][k] = (c0[k] + 2 * c1[k]) / 3;
                    }
                    pal[2][3] = pal[3][3] = 255;
                } else {
                    for (int k = 0; k < 3; k++) {
                        pal[2][k] = (c0[k] + c1[k]) / 2;
                        pal[3][k] = 0;
                    }
                    pal[2][3] = 255;
                    pal[3][3] = 0; // прозрачный
                }
                pal[0][3] = pal[1][3] = 255;
                for (int py = 0; py < 4; py++) {
                    for (int px = 0; px < 4; px++) {
                        int x = bx * 4 + px, y = by * 4 + py;
                        if (x >= w || y >= h) continue;
                        int i = py * 4 + px;
                        int[] p = pal[(idx >>> (2 * i)) & 3];
                        int a = (fmt == DXT1 || fmt == DXT1_ONEBIT) ? p[3] : a8[i];
                        out[y * w + x] = (a << 24) | (p[0] << 16) | (p[1] << 8) | p[2];
                    }
                }
            }
        }
    }
}
