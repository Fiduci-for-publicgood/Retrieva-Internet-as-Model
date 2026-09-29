import re
import threading
import time
import unittest

from retrieva import Agent, CorpusSource, Doc, Gate
from retrieva.engine import PRIME_VOICES, group_areas, parse_claims

TOPICS = {"tea": "focus", "exercise": "mood", "sleep": "memory", "sugar": "teeth"}
HOSTS = {"journal.example.org": 0.9, "health.example.gov": 0.85, "news.example.com": 0.6}


def corpus():
    docs = []
    for t, (subj, obj) in enumerate(TOPICS.items()):
        verb = "harms" if subj == "sugar" else "improves"
        for h in HOSTS:
            docs.append(Doc(f"{h}/{subj}", f"{subj.capitalize()} {verb} {obj}. {subj.capitalize()} {verb} {obj} in adults.", 1.75e9 + t))
    return CorpusSource(docs)


class Laggy:
    """Simulated network: each search sleeps, and we record peak concurrent crawlers."""
    name = "laggy"

    def __init__(self, inner, delay):
        self.inner, self.delay, self.now, self.peak, self.total = inner, delay, 0, 0, 0
        self.lock = threading.Lock()

    def search(self, q, n):
        with self.lock:
            self.now += 1
            self.total += 1
            self.peak = max(self.peak, self.now)
        try:
            time.sleep(self.delay)
            return self.inner.search(q, n)
        finally:
            with self.lock:
                self.now -= 1


LONG = ("Tea improves focus. Exercise improves mood. Sleep improves memory. Sugar harms teeth.")


class LongForm(unittest.TestCase):
    def test_budget_scale(self):
        b = Agent.long_budget
        self.assertEqual((b(1), b(2), b(3), b(4)), (7.0, 7.0, 11.0, 15.0))

    def test_area_cap_is_four(self):
        text = LONG + " Coffee boosts alertness. Salt raises pressure."
        areas = group_areas(parse_claims(text))
        self.assertEqual(len(areas), 4)
        self.assertEqual(sum(map(len, areas)), 6)

    def test_prime_set(self):
        self.assertEqual(PRIME_VOICES[:6], (1, 2, 3, 5, 7, 11))
        self.assertEqual(PRIME_VOICES[-1], 127)
        self.assertEqual(len(PRIME_VOICES), 32)   # 1 + 31 primes <= 128

    def test_quick_query_respects_3s_cap(self):
        src = Laggy(corpus(), 1.4)                 # layers 1-2 fit, layer 3 would not
        ag = Agent([src], Gate({"journal.example.org": 0.9}), None)   # one host: cannot resolve -> uses all layers
        t = time.perf_counter()
        ans = ag.ask("tea improves focus")
        self.assertLessEqual(time.perf_counter() - t, 3.3)
        self.assertLessEqual(src.peak, 32)
        self.assertTrue(all(v[0] <= 31 for v in ans.voices))

    def test_long_form_four_areas_128_crawlers_prime_only(self):
        src = Laggy(corpus(), 0.6)
        ag = Agent([src], Gate(HOSTS), None)
        t = time.perf_counter()
        ans = ag.ask_long(LONG)
        wall = time.perf_counter() - t
        self.assertEqual(ans.budget, 15.0)
        self.assertLess(wall, 15.0)
        self.assertEqual(len(ans.areas), 4)
        self.assertTrue(all(a.resolved for area in ans.areas for a in area))
        self.assertGreater(src.peak, 32)           # areas really run concurrently
        self.assertLessEqual(src.peak, 128)
        nums = [int(n) for n in re.findall(r"^(\d+)\. \[", ans.prose, re.M)]
        self.assertEqual(len(nums), len(ans.prose.split("\n")))      # prime lines are the ENTIRE answer
        self.assertEqual(nums, sorted(nums, reverse=True))
        self.assertTrue(set(nums) <= set(PRIME_VOICES))
        self.assertTrue(any(n > 32 for n in nums))                   # voices from later areas
        for word in ("focus", "mood", "memory", "teeth"):
            self.assertIn(word, ans.prose)

    def test_long_form_deadline_cut_with_slow_source(self):
        src = Laggy(corpus(), 20)                  # never answers in time
        ag = Agent([src], Gate(HOSTS), None)
        t = time.perf_counter()
        ans = ag.ask_long(LONG)
        self.assertLess(time.perf_counter() - t, 15.8)
        self.assertLessEqual(src.peak, 128)
        self.assertTrue(all(a.verdict == "unresolved" for area in ans.areas for a in area))


if __name__ == "__main__":
    unittest.main()
