package gg.portalcraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Читалка Valve Pak (.vpk) версий 1 и 2. Файлы читаются прямо из установки игры. */
public final class Vpk {
    public record Entry(String path, int preloadLen, long preloadPos, int archive, long offset, int length) {}

    private final Path dirFile;
    private final byte[] tree;
    private final long dataStart;
    public final Map<String, Entry> entries = new HashMap<>();

    private Vpk(Path dirFile, byte[] tree, long dataStart) {
        this.dirFile = dirFile;
        this.tree = tree;
        this.dataStart = dataStart;
    }

    public static Vpk open(Path dirFile) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(dirFile.toFile(), "r")) {
            byte[] head = new byte[28];
            f.readFully(head, 0, 12);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getInt(0) != 0x55AA1234) throw new IOException("not a VPK: " + dirFile);
            int version = h.getInt(4);
            int treeSize = h.getInt(8);
            int headerSize = 12;
            if (version == 2) {
                f.readFully(head, 12, 16);
                headerSize = 28;
            } else if (version != 1) {
                throw new IOException("unsupported VPK version " + version);
            }
            byte[] tree = new byte[treeSize];
            f.seek(headerSize);
            f.readFully(tree);
            Vpk v = new Vpk(dirFile, tree, headerSize + (long) treeSize);
            v.parse();
            return v;
        }
    }

    private int pos;

    private String cstr() {
        int s = pos;
        while (tree[pos] != 0) pos++;
        String r = new String(tree, s, pos - s, java.nio.charset.StandardCharsets.ISO_8859_1);
        pos++;
        return r;
    }

    private void parse() {
        ByteBuffer b = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN);
        pos = 0;
        while (true) {
            String ext = cstr();
            if (ext.isEmpty()) break;
            while (true) {
                String dir = cstr();
                if (dir.isEmpty()) break;
                while (true) {
                    String name = cstr();
                    if (name.isEmpty()) break;
                    b.position(pos);
                    b.getInt(); // crc
                    int preload = b.getShort() & 0xFFFF;
                    int archive = b.getShort() & 0xFFFF;
                    long offset = b.getInt() & 0xFFFFFFFFL;
                    int length = b.getInt();
                    b.getShort(); // 0xFFFF terminator
                    pos = b.position();
                    long preloadPos = pos;
                    pos += preload;
                    String d = dir.equals(" ") ? "" : dir + "/";
                    String e = ext.equals(" ") ? "" : "." + ext;
                    String full = (d + name + e).toLowerCase();
                    entries.put(full, new Entry(full, preload, preloadPos, archive, offset, length));
                }
            }
        }
    }

    public byte[] read(Entry e) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(e.preloadLen() + e.length());
        out.write(tree, (int) e.preloadPos(), e.preloadLen());
        if (e.length() > 0) {
            Path file;
            long off = e.offset();
            if (e.archive() == 0x7FFF) {
                file = dirFile;
                off += dataStart;
            } else {
                String n = dirFile.getFileName().toString().replace("_dir.vpk", String.format("_%03d.vpk", e.archive()));
                file = dirFile.resolveSibling(n);
            }
            try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
                byte[] data = new byte[e.length()];
                f.seek(off);
                f.readFully(data);
                out.write(data);
            }
        }
        return out.toByteArray();
    }
}
