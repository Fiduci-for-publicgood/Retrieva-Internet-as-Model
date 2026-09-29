"""Triple store: interned strings + fixed-width records, hard-capped (default 8 MB), scored eviction.

Only bare subject/predicate/object text lives here: no HTML, no URLs, no punctuation. Everything
read back from disk is re-validated ("rationalised at runtime") before it can enter memory.
"""
from __future__ import annotations

import re
import struct
import time
from typing import NamedTuple

from .extract import content
from .sanitize import is_injection

MB = 1024 * 1024
_MAGIC = b"RTV1"
_REC = struct.Struct("<IIIIdBI")  # s p o src ids | added-time | trust*255 | hits
_HDR = struct.Struct("<4sII")
_FIELD = re.compile(r"^[a-z0-9' -]{1,80}$")
_LOCATOR = re.compile(r"^[a-z0-9.-]{1,120}(?:/[A-Za-z0-9._~%()-]{0,120}){0,4}$")
HALF_LIFE_DAYS = 365.0


class Triple(NamedTuple):
    s: str
    p: str
    o: str
    src: str      # locator of the source document (host/path), never a scheme or query
    t: float      # when the source was added/published (epoch seconds)
    trust: float  # prior confidence in the source, 0..1
    hits: int


def valid(s: str, p: str, o: str, src: str) -> bool:
    if not all(_FIELD.match(x) for x in (s, p, o)) or not _LOCATOR.match(src):
        return False
    return not is_injection(f"{s} {p} {o}")


def recency(t: float, now: float, half_life_days: float = HALF_LIFE_DAYS) -> float:
    age_days = max(0.0, (now - t) / 86400.0)
    return max(0.05, 0.5 ** (age_days / half_life_days))


class TripleStore:
    """Records are kept as raw rows with no eager index: load is O(read), and a query is a C-speed
    substring scan over the string table. Rows are validated the first time a query touches them."""

    def __init__(self, cap_bytes: int = 8 * MB):
        self.cap = cap_bytes
        self._stem_cache: dict[str, frozenset] = {}
        self.dirty = False
        self._clear()

    def _clear(self) -> None:
        self.strings: list[str] = []
        self._sid: dict[str, int] = {}
        self.recs: list[list] = []  # [s, p, o, src, t, trust_q, hits]
        self._keys: dict[tuple, int] | None = {}
        self._ok: dict[int, bool] = {}     # per-string validity, filled lazily
        self._bytes = _HDR.size

    def __len__(self) -> int:
        return len(self.recs)

    def size_bytes(self) -> int:
        return self._bytes

    def _intern(self, x: str) -> int:
        i = self._sid.get(x)
        if i is None:
            i = self._sid[x] = len(self.strings)
            self.strings.append(x)
            self._bytes += len(x.encode()) + 4
        return i

    def _key_index(self) -> dict[tuple, int]:
        if self._keys is None:  # built on first write after a load
            self._keys = {tuple(r[:4]): i for i, r in enumerate(self.recs)}
        return self._keys

    def add(self, s: str, p: str, o: str, src: str, t: float, trust: float) -> bool:
        """Insert a validated triple. Returns True if new. Enforces the byte cap."""
        if not valid(s, p, o, src):
            return False
        ids = (self._intern(s), self._intern(p), self._intern(o), self._intern(src))
        q = max(0, min(255, round(trust * 255)))
        keys = self._key_index()
        i = keys.get(ids)
        self.dirty = True
        if i is not None:
            r = self.recs[i]
            r[4], r[5] = max(r[4], t), max(r[5], q)
            return False
        keys[ids] = len(self.recs)
        self.recs.append([*ids, t, q, 0])
        self._bytes += _REC.size
        if self._bytes > self.cap:
            self._evict()
        return True

    def _stem(self, sid: int) -> frozenset:
        x = self.strings[sid]
        c = self._stem_cache.get(x)
        if c is None:
            c = self._stem_cache[x] = content(x)
        return c

    def _valid_row(self, r) -> bool:
        ok, st = self._ok, self.strings
        for j, is_loc in ((0, False), (1, False), (2, False), (3, True)):
            sid = r[j]
            v = ok.get(sid)
            if v is None:
                x = st[sid]
                v = ok[sid] = bool(_LOCATOR.match(x)) if is_loc else (bool(_FIELD.match(x)) and not is_injection(x))
            if not v:
                return False
        return True

    def candidates(self, stems) -> list[int]:
        """Rows whose subject or object mentions any stem. Substring scan, then exact checks by caller."""
        stems = [w for w in stems if len(w) > 1]
        if not stems:
            return []
        hit = {i for i, x in enumerate(self.strings) if any(w in x for w in stems)}
        return [i for i, r in enumerate(self.recs) if (r[0] in hit or r[2] in hit) and self._valid_row(r)]

    def get(self, i: int) -> Triple:
        s, p, o, src, t, q, h = self.recs[i]
        st = self.strings
        return Triple(st[s], st[p], st[o], st[src], t, q / 255.0, h)

    def key_of(self, i: int) -> tuple:
        return tuple(self.recs[i][:4])

    def stems(self, i: int) -> tuple[frozenset, frozenset]:
        r = self.recs[i]
        return self._stem(r[0]), self._stem(r[2])

    def touch(self, ids) -> None:
        for i in ids:
            self.recs[i][6] += 1

    def _evict(self) -> None:
        """Drop the lowest-value triples (trust x recency x usage) down to 90% of the cap."""
        now = time.time()
        score = [(r[5] / 255.0) * recency(r[4], now) * (1 + r[6]) for r in self.recs]
        order = sorted(range(len(self.recs)), key=score.__getitem__, reverse=True)
        old_strings, old_recs = self.strings, self.recs
        self._clear()
        self.dirty = True
        for i in order:
            s, p, o, src, t, q, h = old_recs[i]
            if self._bytes + _REC.size + 4 * len(old_strings[s]) > self.cap * 0.9:
                break
            ids = (self._intern(old_strings[s]), self._intern(old_strings[p]),
                   self._intern(old_strings[o]), self._intern(old_strings[src]))
            self._keys[ids] = len(self.recs)
            self.recs.append([*ids, t, q, h])
            self._bytes += _REC.size

    def to_bytes(self) -> bytes:
        used = sorted({x for r in self.recs for x in r[:4]})
        remap = {old: new for new, old in enumerate(used)}
        parts = [_HDR.pack(_MAGIC, len(used), len(self.recs))]
        for old in used:
            b = self.strings[old].encode()
            parts.append(struct.pack("<I", len(b)) + b)
        parts += [_REC.pack(remap[r[0]], remap[r[1]], remap[r[2]], remap[r[3]], r[4], r[5], r[6]) for r in self.recs]
        return b"".join(parts)

    def save(self, path) -> int:
        data = self.to_bytes()
        with open(path, "wb") as f:
            f.write(data)
        self.dirty = False
        return len(data)

    @classmethod
    def from_bytes(cls, data: bytes, cap_bytes: int = 8 * MB) -> "TripleStore":
        st = cls(cap_bytes)
        magic, n_str, n_rec = _HDR.unpack_from(data, 0)
        if magic != _MAGIC:
            raise ValueError("not a retrieva triple file")
        off, strings = _HDR.size, []
        for _ in range(n_str):
            (ln,) = struct.unpack_from("<I", data, off)
            off += 4
            strings.append(data[off:off + ln].decode("utf-8", "replace"))
            off += ln
        n = len(strings)
        body = memoryview(data)[off:off + n_rec * _REC.size]
        recs = [list(r) for r in _REC.iter_unpack(body) if max(r[:4]) < n]  # bounds only; rest is lazy
        st.strings, st.recs, st._keys = strings, recs, None
        st._sid = {x: i for i, x in enumerate(strings)}
        st._bytes = len(data)
        return st

    @classmethod
    def load(cls, path, cap_bytes: int = 8 * MB) -> "TripleStore":
        try:
            with open(path, "rb") as f:
                return cls.from_bytes(f.read(), cap_bytes)
        except FileNotFoundError:
            return cls(cap_bytes)
