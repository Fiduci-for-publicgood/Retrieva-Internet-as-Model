package io.retrieva.arrow;

import io.retrieva.core.Memory;
import io.retrieva.core.Outline;
import io.retrieva.core.TripleStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.ipc.SeekableReadChannel;

/**
 * Persists {@link Memory} as Apache Arrow IPC files (readable zero-copy by pandas/pyarrow) plus the outline text.
 *
 * <pre>
 * DIR/CURRENT                  name of the live generation, replaced atomically
 * DIR/gen-000042/strings.arrow value: utf8            (row index = string id)
 * DIR/gen-000042/triples.arrow s,p,o,src: int32 string ids; added: float64 epoch s; trust_q: int32 0..255; hits: int32
 * DIR/gen-000042/outline.md    the route outline
 * DIR/gen-000042/MANIFEST.json counts + SHA-256 of each file
 * </pre>
 *
 * A save writes a complete new generation, then flips CURRENT; a crash at any point leaves the previous generation
 * intact. Load verifies the manifest hashes, falls back to the previous generation if the newest is damaged, and
 * re-validates every row (see {@link TripleStore#restore}) because the disk is untrusted.
 */
public final class ArrowMemoryRepository {
    public static final int VERSION = 1;
    static final Schema STRINGS = new Schema(List.of(Field.notNullable("value", new ArrowType.Utf8())), Map.of("retrieva.version", "1"));
    static final Schema TRIPLES = new Schema(List.of(
            Field.notNullable("s", new ArrowType.Int(32, true)), Field.notNullable("p", new ArrowType.Int(32, true)),
            Field.notNullable("o", new ArrowType.Int(32, true)), Field.notNullable("src", new ArrowType.Int(32, true)),
            Field.notNullable("added", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            Field.notNullable("trust_q", new ArrowType.Int(32, true)), Field.notNullable("hits", new ArrowType.Int(32, true))),
            Map.of("retrieva.version", "1"));

    public record Loaded(Memory memory, List<String> warnings) {}

    private final Path dir;
    private final int tripleCap, outlineCap, keep;
    private final DoubleSupplier clock;

    public ArrowMemoryRepository(Path dir, int tripleCap, int outlineCap, int generationsToKeep, DoubleSupplier clock) {
        this.dir = dir;
        this.tripleCap = tripleCap;
        this.outlineCap = outlineCap;
        this.keep = Math.max(2, generationsToKeep);
        this.clock = clock;
    }

    // -- load --------------------------------------------------------------------------------------------
    public Loaded load() throws IOException {
        List<String> warnings = new ArrayList<>();
        Files.createDirectories(dir);
        List<String> gens = generations();
        String current = readCurrent();
        if (current != null && gens.remove(current)) gens.add(0, current);       // try CURRENT first, then newest-first
        for (String g : gens) {
            try {
                return new Loaded(readGeneration(dir.resolve(g)), warnings);
            } catch (IOException | RuntimeException e) {
                warnings.add("generation " + g + " unusable: " + e.getMessage());
            }
        }
        if (!gens.isEmpty()) warnings.add("no usable generation; starting with empty memory");
        return new Loaded(Memory.empty(clock), warnings);
    }

    private Memory readGeneration(Path g) throws IOException {
        Map<String, Object> mf = io.retrieva.core.Json.obj(io.retrieva.core.Json.parse(Files.readString(g.resolve("MANIFEST.json"), StandardCharsets.UTF_8)));
        Map<String, Object> hashes = io.retrieva.core.Json.obj(mf.get("sha256"));
        for (String name : List.of("strings.arrow", "triples.arrow", "outline.md")) {
            if (!sha256(g.resolve(name)).equals(hashes.get(name))) throw new IOException(name + " does not match manifest");
        }
        List<String> strings = new ArrayList<>();
        try (BufferAllocator alloc = new RootAllocator();
             FileChannel ch = FileChannel.open(g.resolve("strings.arrow"), StandardOpenOption.READ);
             ArrowFileReader r = new ArrowFileReader(new SeekableReadChannel(ch), alloc)) {
            VectorSchemaRoot root = r.getVectorSchemaRoot();
            while (r.loadNextBatch()) {
                VarCharVector v = (VarCharVector) root.getVector("value");
                for (int i = 0; i < root.getRowCount(); i++) strings.add(new String(v.get(i), StandardCharsets.UTF_8));
            }
        }
        List<int[]> s = new ArrayList<>(), p = new ArrayList<>(), o = new ArrayList<>(), src = new ArrayList<>(), tq = new ArrayList<>(), hits = new ArrayList<>();
        List<double[]> ad = new ArrayList<>();
        try (BufferAllocator alloc = new RootAllocator();
             FileChannel ch = FileChannel.open(g.resolve("triples.arrow"), StandardOpenOption.READ);
             ArrowFileReader r = new ArrowFileReader(new SeekableReadChannel(ch), alloc)) {
            VectorSchemaRoot root = r.getVectorSchemaRoot();
            while (r.loadNextBatch()) {
                int n = root.getRowCount();
                s.add(ints(root, "s", n));
                p.add(ints(root, "p", n));
                o.add(ints(root, "o", n));
                src.add(ints(root, "src", n));
                tq.add(ints(root, "trust_q", n));
                hits.add(ints(root, "hits", n));
                Float8Vector av = (Float8Vector) root.getVector("added");
                double[] a = new double[n];
                for (int i = 0; i < n; i++) a[i] = av.get(i);
                ad.add(a);
            }
        }
        TripleStore.Snapshot snap = new TripleStore.Snapshot(strings, concat(s), concat(p), concat(o), concat(src), concatD(ad), concat(tq), concat(hits));
        TripleStore store = TripleStore.restore(snap, tripleCap, clock);
        Outline outline = Outline.parse(Files.readString(g.resolve("outline.md"), StandardCharsets.UTF_8), outlineCap);
        return new Memory(store, outline);
    }

    private static int[] ints(VectorSchemaRoot root, String name, int n) {
        IntVector v = (IntVector) root.getVector(name);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = v.get(i);
        return a;
    }

    private static int[] concat(List<int[]> parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] out = new int[n];
        int at = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static double[] concatD(List<double[]> parts) {
        int n = 0;
        for (double[] p : parts) n += p.length;
        double[] out = new double[n];
        int at = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    // -- save --------------------------------------------------------------------------------------------
    /** Snapshot under the memory lock (cheap), write outside it. The memory is marked clean at snapshot time. */
    public void save(Memory mem) throws IOException {
        TripleStore.Snapshot snap;
        String outline;
        mem.lock.lock();
        try {
            snap = mem.store.snapshot();
            outline = mem.outline.toText();
            mem.markClean();
        } finally {
            mem.lock.unlock();
        }
        try {
            write(snap, outline);
        } catch (IOException | RuntimeException e) {
            mem.lock.lock();
            try {
                mem.markDirty();      // the write failed: keep the memory marked as needing a save
            } finally {
                mem.lock.unlock();
            }
            throw e;
        }
    }

    private void write(TripleStore.Snapshot snap, String outline) throws IOException {
        Files.createDirectories(dir);
        int next = 1;
        for (String g : generations()) next = Math.max(next, Integer.parseInt(g.substring(4)) + 1);
        String name = String.format("gen-%06d", next);
        Path tmp = dir.resolve(name + ".tmp");
        Files.createDirectories(tmp);
        writeStrings(tmp.resolve("strings.arrow"), snap.strings());
        writeTriples(tmp.resolve("triples.arrow"), snap);
        Files.writeString(tmp.resolve("outline.md"), outline, StandardCharsets.UTF_8);
        String manifest = io.retrieva.core.Json.write(Map.of("version", VERSION, "strings", snap.strings().size(), "triples", snap.s().length,
                "sha256", Map.of("strings.arrow", sha256(tmp.resolve("strings.arrow")), "triples.arrow", sha256(tmp.resolve("triples.arrow")),
                        "outline.md", sha256(tmp.resolve("outline.md")))));
        Files.writeString(tmp.resolve("MANIFEST.json"), manifest, StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        Path ptrTmp = dir.resolve("CURRENT.tmp");
        Files.writeString(ptrTmp, name, StandardCharsets.UTF_8);
        Files.move(ptrTmp, dir.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        prune();
    }

    private static void writeStrings(Path path, List<String> strings) throws IOException {
        try (BufferAllocator alloc = new RootAllocator();
             VectorSchemaRoot root = VectorSchemaRoot.create(STRINGS, alloc);
             FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             ArrowFileWriter w = new ArrowFileWriter(root, null, ch)) {
            w.start();
            VarCharVector v = (VarCharVector) root.getVector("value");
            v.allocateNew();
            for (int i = 0; i < strings.size(); i++) v.setSafe(i, strings.get(i).getBytes(StandardCharsets.UTF_8));
            root.setRowCount(strings.size());
            w.writeBatch();
            w.end();
        }
    }

    private static void writeTriples(Path path, TripleStore.Snapshot sn) throws IOException {
        try (BufferAllocator alloc = new RootAllocator();
             VectorSchemaRoot root = VectorSchemaRoot.create(TRIPLES, alloc);
             FileChannel ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             ArrowFileWriter w = new ArrowFileWriter(root, null, ch)) {
            w.start();
            root.allocateNew();
            int n = sn.s().length;
            fill((IntVector) root.getVector("s"), sn.s());
            fill((IntVector) root.getVector("p"), sn.p());
            fill((IntVector) root.getVector("o"), sn.o());
            fill((IntVector) root.getVector("src"), sn.src());
            fill((IntVector) root.getVector("trust_q"), sn.trust());
            fill((IntVector) root.getVector("hits"), sn.hits());
            Float8Vector a = (Float8Vector) root.getVector("added");
            for (int i = 0; i < n; i++) a.setSafe(i, sn.t()[i]);
            root.setRowCount(n);
            w.writeBatch();
            w.end();
        }
    }

    private static void fill(IntVector v, int[] data) {
        for (int i = 0; i < data.length; i++) v.setSafe(i, data[i]);
    }

    // -- housekeeping ------------------------------------------------------------------------------------
    /** Generation directory names, newest first. Leftover .tmp directories from crashed saves are ignored. */
    List<String> generations() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "gen-[0-9][0-9][0-9][0-9][0-9][0-9]")) {
            for (Path p : ds) out.add(p.getFileName().toString());
        }
        out.sort(java.util.Comparator.reverseOrder());
        return out;
    }

    private String readCurrent() throws IOException {
        Path p = dir.resolve("CURRENT");
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).strip() : null;
    }

    private void prune() throws IOException {
        List<String> gens = generations();
        for (String g : gens.subList(Math.min(keep, gens.size()), gens.size())) deleteTree(dir.resolve(g));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tmp")) {
            for (Path p : ds) if (Files.isDirectory(p)) deleteTree(p);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
            for (Path c : ds) Files.delete(c);
        }
        Files.delete(p);
    }

    static String sha256(Path p) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
