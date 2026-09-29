package io.retrieva.core;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;

/** The two capped memory structures plus the lock that guards them: 8 MB of triples, 2 MB of routes. */
public final class Memory {
    public static final int TRIPLE_CAP = 8 * TripleStore.MB;
    public static final int OUTLINE_CAP = 2 * TripleStore.MB;

    public final TripleStore store;
    public final Outline outline;
    public final ReentrantLock lock = new ReentrantLock();

    public Memory(TripleStore store, Outline outline) {
        this.store = store;
        this.outline = outline;
    }

    public static Memory empty(DoubleSupplier clock) {
        return new Memory(new TripleStore(TRIPLE_CAP, clock), new Outline(OUTLINE_CAP));
    }

    /** True if either structure changed since it was last persisted. */
    public boolean dirty() {
        return store.dirty() || outline.dirty();
    }

    public void markClean() {
        store.markClean();
        outline.markClean();
    }

    /** Force the next persistence pass to write (used when a save failed). */
    public void markDirty() {
        store.markDirty();
    }
}
