import json, os, tempfile, unittest
from pathlib import Path

from app.retriever import Retriever
from app.store import Store
from app import llm

KB = json.loads((Path(__file__).parent.parent / "app/data/knowledge.json").read_text())


class RetrieverTests(unittest.TestCase):
    def setUp(self):
        self.r = Retriever(KB)

    def top(self, q, profile=None):
        res = self.r.search(q, profile)
        return (res.hits[0].entry["id"] if res.hits else None), res.confidence

    def test_everyday_wording_maps_to_handbook_terms(self):
        self.assertEqual(self.top("where can I eat at night?")[0], "mess")
        self.assertEqual(self.top("how to reach campus from the railway station")[0], "transport")
        self.assertEqual(self.top("my wifi is slow")[0], "wifi")
        self.assertEqual(self.top("how do I register for subjects")[0], "ffcs")

    def test_acronyms(self):
        self.assertEqual(self.top("FFCS")[0], "ffcs")
        self.assertEqual(self.top("what is CAT and FAT")[0], "exams")

    def test_out_of_scope_is_not_answered(self):
        for q in ["who won the world cup", "capital of France", "write a poem about dogs"]:
            self.assertEqual(self.top(q)[1], "none", q)

    def test_ambiguous_word_is_low_confidence_not_high(self):
        # "cats" collides with the CAT exam tag; it must never be a confident answer.
        self.assertIn(self.top("write a poem about cats")[1], ("low", "none"))

    def test_profile_changes_hostel_answer(self):
        self.assertEqual(self.top("hostel curfew timings", {"hostel": "girls"})[0], "hostel-girls")
        self.assertEqual(self.top("hostel curfew timings", {"hostel": "boys"})[0], "hostel-boys")

    def test_confidence_is_high_for_clear_questions(self):
        self.assertEqual(self.top("what is the attendance requirement")[1], "high")

    def test_rebuild_adds_knowledge(self):
        self.assertEqual(self.top("where is the gym")[1], "none")
        self.r.rebuild(KB + [{"id": "c-1", "title": "Gym", "category": "Campus life", "tags": ["gym", "fitness"],
                              "text": "The gym is open in the evening.", "source": "Senior", "verified": False}])
        self.assertEqual(self.top("where is the gym")[0], "c-1")


class StoreTests(unittest.TestCase):
    def test_gaps_group_by_meaning_and_sort(self):
        with tempfile.TemporaryDirectory() as d:
            s = Store(d)
            s.log_gap("Where is the gym?", {}, "no_match")
            s.log_gap("where is gym", {}, "no_match")
            s.log_gap("is there a swimming pool", {}, "no_match")
            g = s.gaps()
            self.assertEqual(g[0]["count"], 2)
            self.assertEqual(len(g), 2)

    def test_community_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            s = Store(d)
            e = s.add_community("Gym", "Open 6 to 9pm for students.", ["Gym", "fit ness!"], "Campus life", "Senior", True)
            self.assertTrue(e["id"].startswith("c-"))
            self.assertEqual(s.community()[0]["tags"], ["gym", "fitness"])


class LlmFallbackTests(unittest.TestCase):
    def test_no_key_means_extractive(self):
        os.environ.pop("ANTHROPIC_API_KEY", None)
        r = Retriever(KB).search("wifi")
        self.assertIsNone(llm.generate("wifi", r.hits, {}, "en", []))
        self.assertIn("Wi-Fi", llm.extractive(r.hits))


if __name__ == "__main__":
    unittest.main()
