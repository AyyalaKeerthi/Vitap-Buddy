"""Tiny, dependency-free retriever (BM25 + synonyms + profile-aware boosting).

The agent never "memorises" university facts. It looks them up here, then the
LLM phrases the answer from only what was found. Updating the agent's knowledge
means editing data/knowledge.json, not retraining anything.
"""
from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

STOP = set(
    "a an the is are am was were to of in on at for and or do does did how what when where "
    "which who whom whose why i me my we our you your can could should would will shall be "
    "been it its this that there these those with about from by as if so any get got have has "
    "had tell please hi hello hey need want know just also into than then them they he she".split()
)


def stem(word: str) -> str:
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def tokenize(text: str, drop_stop: bool = True) -> list[str]:
    words = re.findall(r"[a-z0-9]+", text.lower())
    return [stem(w) for w in words if not (drop_stop and w in STOP)]


# Freshers rarely use the words the handbook uses. Map their words to ours.
_RAW_SYNONYMS = {
    "mess": ["food", "canteen", "dining"],
    "food": ["mess", "canteen", "dining"],
    "eat": ["food", "mess", "canteen"],
    "hungry": ["food", "mess", "canteen"],
    "canteen": ["food", "mess"],
    "wifi": ["internet", "network"],
    "internet": ["wifi", "network"],
    "net": ["wifi", "internet"],
    "hostel": ["room", "accommodation"],
    "room": ["hostel", "accommodation"],
    "stay": ["hostel", "accommodation"],
    "bus": ["transport", "shuttle"],
    "transport": ["bus", "shuttle"],
    "reach": ["transport", "travel", "bus"],
    "travel": ["transport", "bus"],
    "train": ["railway", "station", "transport"],
    "airport": ["transport", "travel"],
    "exam": ["cat", "fat", "test"],
    "test": ["exam", "cat", "fat"],
    "marks": ["grade", "exam"],
    "grades": ["marks", "gpa", "cgpa"],
    "register": ["registration", "ffcs"],
    "registration": ["ffcs", "course"],
    "subjects": ["course", "ffcs"],
    "subject": ["course", "ffcs"],
    "courses": ["course", "ffcs"],
    "class": ["course", "timetable"],
    "portal": ["vtop"],
    "attendence": ["attendance"],
    "bunk": ["attendance"],
    "ragging": ["antiragging", "harassment"],
    "scared": ["ragging", "safe"],
    "doctor": ["medical", "health", "hospital"],
    "sick": ["medical", "health", "doctor"],
    "ill": ["medical", "health", "doctor"],
    "club": ["chapter", "activities"],
    "join": ["club", "chapter"],
    "pay": ["fee", "payment"],
    "money": ["bank", "fee", "upi"],
    "atm": ["bank", "cash"],
    "bring": ["pack", "carry"],
    "luggage": ["pack", "bring"],
    "clothes": ["laundry", "dress"],
    "wash": ["laundry"],
    "books": ["library"],
    "job": ["placement", "career"],
    "internship": ["placement", "career"],
    "phone": ["sim", "mobile"],
    "signal": ["network", "sim", "mobile"],
    "outing": ["hostel", "permission"],
    "curfew": ["hostel", "outing", "night"],
    "timing": ["time", "schedule"],
    "timings": ["time", "schedule"],
}
SYNONYMS = {stem(k): [stem(v) for v in vs] for k, vs in _RAW_SYNONYMS.items()}


@dataclass
class Hit:
    entry: dict
    score: float
    coverage: float


@dataclass
class SearchResult:
    hits: list[Hit] = field(default_factory=list)
    confidence: str = "none"  # none | low | medium | high
    coverage: float = 0.0


class Retriever:
    K1 = 1.4
    B = 0.75

    def __init__(self, entries: list[dict]):
        self.rebuild(entries)

    def rebuild(self, entries: list[dict]) -> None:
        self.entries = list(entries)
        self._docs: list[tuple[Counter, int]] = []
        self._keys: list[set[str]] = []  # title + tag terms: the words a note is *about*
        df: Counter = Counter()
        for e in self.entries:
            toks = (
                tokenize(e["title"]) * 2
                + tokenize(" ".join(e.get("tags", []))) * 3
                + tokenize(e["text"])
            )
            tf = Counter(toks)
            self._keys.append(set(tokenize(e["title"])) | set(tokenize(" ".join(e.get("tags", [])))))
            self._docs.append((tf, len(toks)))
            df.update(tf.keys())
        n = max(len(self.entries), 1)
        self._avgdl = sum(length for _, length in self._docs) / n if self._docs else 1.0
        self._idf = {t: math.log(1 + (n - c + 0.5) / (c + 0.5)) for t, c in df.items()}

    # ------------------------------------------------------------------
    def _profile_factor(self, entry: dict, profile: dict) -> float:
        """Boost notes meant for this student, demote notes meant for others."""
        factor = 1.0
        for key, allowed in (entry.get("audience") or {}).items():
            value = (profile or {}).get(key)
            if not value:
                continue
            factor *= 1.3 if value in allowed else 0.5
        return factor

    def search(self, query: str, profile: dict | None = None, k: int = 3) -> SearchResult:
        base = list(dict.fromkeys(tokenize(query)))  # unique, ordered
        if not base:
            return SearchResult()

        weights: dict[str, float] = {t: 1.0 for t in base}
        for t in base:
            for s in SYNONYMS.get(t, []):
                weights.setdefault(s, 0.5)

        scored: list[Hit] = []
        for entry, (tf, dl), keys in zip(self.entries, self._docs, self._keys):
            score = 0.0
            for term, w in weights.items():
                f = tf.get(term, 0)
                if not f:
                    continue
                idf = self._idf.get(term, 0.0)
                score += w * idf * (f * (self.K1 + 1)) / (f + self.K1 * (1 - self.B + self.B * dl / self._avgdl))
            if score <= 0:
                continue
            matched = sum(1 for t in base if t in tf or any(s in tf for s in SYNONYMS.get(t, [])))
            about = sum(1 for t in base if t in keys or any(s in keys for s in SYNONYMS.get(t, [])))
            if about == 0 and matched / len(base) < 0.5:
                continue  # only incidental body-text words matched, e.g. "write" in an exam note
            score *= self._profile_factor(entry, profile or {})
            scored.append(Hit(entry, score, matched / len(base)))

        if not scored:
            return SearchResult()

        scored.sort(key=lambda h: h.score, reverse=True)
        top = scored[0]
        hits = [h for h in scored[:k] if h.score >= 0.35 * top.score]

        if top.coverage >= 0.6:
            conf = "high"
        elif top.coverage >= 0.34:
            conf = "medium"
        else:
            conf = "low"
        return SearchResult(hits=hits, confidence=conf, coverage=top.coverage)
