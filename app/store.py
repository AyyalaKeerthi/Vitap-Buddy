"""Small file-backed store for (1) unanswered questions and (2) community answers.

Fine for one replica. To run many replicas, swap this class for Postgres/Redis
and keep the same four methods."""
from __future__ import annotations

import json
import re
import threading
import time
import uuid
from collections import defaultdict
from pathlib import Path

from .retriever import tokenize


def normalise(question: str) -> str:
    return " ".join(sorted(set(tokenize(question)))) or question.strip().lower()


class Store:
    def __init__(self, data_dir: str):
        self.dir = Path(data_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.gaps_file = self.dir / "gaps.jsonl"
        self.community_file = self.dir / "community.json"
        self._lock = threading.Lock()

    def log_gap(self, question: str, profile: dict, reason: str) -> None:
        row = {"t": int(time.time()), "q": question[:300], "key": normalise(question), "reason": reason,
               "hostel": (profile or {}).get("hostel")}
        with self._lock, self.gaps_file.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    def gaps(self) -> list[dict]:
        """Grouped by meaning, most asked first."""
        if not self.gaps_file.exists():
            return []
        groups: dict[str, dict] = defaultdict(lambda: {"count": 0, "example": "", "last_seen": 0, "reasons": set()})
        with self._lock, self.gaps_file.open(encoding="utf-8") as f:
            for line in f:
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue
                g = groups[r["key"]]
                g["count"] += 1
                g["example"] = r["q"]
                g["last_seen"] = max(g["last_seen"], r["t"])
                g["reasons"].add(r["reason"])
        rows = [{**g, "reasons": sorted(g["reasons"])} for g in groups.values()]
        return sorted(rows, key=lambda g: (-g["count"], -g["last_seen"]))

    def community(self) -> list[dict]:
        if not self.community_file.exists():
            return []
        try:
            return json.loads(self.community_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            return []

    def add_community(self, title: str, text: str, tags: list[str], category: str, source: str, verified: bool = False) -> dict:
        entry = {
            "id": "c-" + uuid.uuid4().hex[:8],
            "title": title.strip()[:120],
            "category": category.strip()[:40] or "Community",
            "tags": [re.sub(r"[^a-z0-9-]", "", t.lower()) for t in tags if t.strip()][:12],
            "text": text.strip()[:1500],
            "source": source.strip()[:80] or "Senior contribution",
            "verified": bool(verified),
        }
        with self._lock:
            items = self.community()
            items.append(entry)
            self.community_file.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
        return entry
