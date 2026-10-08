from __future__ import annotations

import json
import logging
import os
import secrets
import time
from collections import defaultdict, deque
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import llm
from .retriever import Retriever, tokenize
from .store import Store

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("buddy")

BASE = Path(__file__).parent
store = Store(os.getenv("DATA_DIR", "./runtime"))


def _load(name: str):
    return json.loads((BASE / "data" / name).read_text(encoding="utf-8"))


BASE_KB = _load("knowledge.json")
ROADMAP = _load("roadmap.json")
kb = Retriever(BASE_KB + store.community())

app = FastAPI(title="VIT-AP Buddy", version="1.0.0")

# ---------------------------------------------------------------- messages
NO_ANSWER = {
    "en": "I don't have a reliable answer for that yet. I've saved your question so seniors can add it. For now, ask the hostel warden, the student welfare office, or check VTOP.",
    "te": "దీనికి నా దగ్గర ఇంకా నమ్మదగిన సమాధానం లేదు. మీ ప్రశ్నను సేవ్ చేశాను, సీనియర్లు దీనిని జోడిస్తారు. ప్రస్తుతానికి హాస్టల్ వార్డెన్‌ను, స్టూడెంట్ వెల్ఫేర్ ఆఫీస్‌ను అడగండి లేదా VTOP చూడండి.",
    "hi": "इसका भरोसेमंद जवाब मेरे पास अभी नहीं है। मैंने आपका सवाल सेव कर लिया है ताकि सीनियर इसे जोड़ सकें। अभी के लिए हॉस्टल वार्डन, स्टूडेंट वेलफेयर ऑफिस से पूछें या VTOP देखें।",
}
LOW_NOTE = {
    "en": "I'm only partly sure this matches your question. Please double-check it.",
    "te": "ఇది మీ ప్రశ్నకు పూర్తిగా సరిపోతుందో లేదో నాకు కొంచెం సందేహం. దయచేసి ఒకసారి నిర్ధారించుకోండి.",
    "hi": "मुझे पूरा यकीन नहीं कि यह आपके सवाल से मेल खाता है। कृपया एक बार जाँच लें।",
}

# ---------------------------------------------------------------- rate limit
_hits: dict[str, deque] = defaultdict(deque)
RATE_LIMIT, RATE_WINDOW = int(os.getenv("RATE_LIMIT", "20")), 60


def _client_ip(request: Request) -> str:
    fwd = request.headers.get("x-forwarded-for")
    return fwd.split(",")[0].strip() if fwd else (request.client.host if request.client else "unknown")


def _check_rate(request: Request) -> None:
    ip, now = _client_ip(request), time.time()
    q = _hits[ip]
    while q and now - q[0] > RATE_WINDOW:
        q.popleft()
    if len(q) >= RATE_LIMIT:
        raise HTTPException(429, "Too many questions. Wait a minute and try again.")
    q.append(now)


# ---------------------------------------------------------------- models
class Profile(BaseModel):
    hostel: Literal["boys", "girls", "dayscholar"] | None = None
    program: Literal["btech", "mtech", "other"] | None = None


class Turn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=1500)


class AskIn(BaseModel):
    question: str = Field(min_length=2, max_length=500)
    profile: Profile = Profile()
    lang: Literal["en", "te", "hi"] = "en"
    history: list[Turn] = Field(default_factory=list, max_length=12)


class FeedbackIn(BaseModel):
    question: str = Field(max_length=500)
    helpful: bool


class AnswerIn(BaseModel):
    title: str = Field(min_length=3, max_length=120)
    text: str = Field(min_length=10, max_length=1500)
    tags: list[str] = Field(default_factory=list)
    category: str = "Community"
    source: str = "Senior contribution"
    verified: bool = False


# ---------------------------------------------------------------- routes
@app.get("/healthz")
def healthz():
    return {"status": "ok"}


@app.get("/readyz")
def readyz():
    return {"status": "ready", "entries": len(kb.entries), "llm": llm.enabled()}


@app.post("/api/ask")
def ask(body: AskIn, request: Request):
    _check_rate(request)
    profile = body.profile.model_dump()
    history = [t.model_dump() for t in body.history]

    # Short follow-ups like "and for girls?" borrow context from the last question.
    query = body.question
    if body.lang != "en" or not query.isascii():
        query = llm.to_english(query) or query  # retrieval is English; translate first when we can
    if len(tokenize(query)) < 3:
        prev = next((t["content"] for t in reversed(history) if t["role"] == "user"), "")
        query = f"{prev} {query}".strip()

    result = kb.search(query, profile, k=3)

    if result.confidence == "none":
        store.log_gap(body.question, profile, "no_match")
        return {"answer": NO_ANSWER[body.lang], "confidence": "none", "sources": [], "gap_logged": True, "mode": "none"}

    text = llm.generate(body.question, result.hits, profile, body.lang, history)
    mode = "llm"
    if text is None:
        text, mode = llm.extractive(result.hits), "extractive"

    gap = False
    if result.confidence == "low":
        store.log_gap(body.question, profile, "low_confidence")
        text = f"{LOW_NOTE[body.lang]}\n\n{text}"
        gap = True

    sources = [
        {"id": h.entry["id"], "title": h.entry["title"], "source": h.entry["source"],
         "category": h.entry.get("category", ""), "verified": bool(h.entry.get("verified"))}
        for h in result.hits
    ]
    return {"answer": text, "confidence": result.confidence, "sources": sources, "gap_logged": gap, "mode": mode}


@app.post("/api/feedback")
def feedback(body: FeedbackIn, request: Request):
    _check_rate(request)
    if not body.helpful:
        store.log_gap(body.question, {}, "thumbs_down")
    return {"ok": True}


@app.get("/api/roadmap")
def roadmap(hostel: str | None = None):
    """First-week checklist, filtered to what applies to this student."""
    items = [t for t in ROADMAP if not t.get("hostel") or hostel in t["hostel"] or hostel is None]
    phases: list[dict] = []
    for t in items:
        if not phases or phases[-1]["phase"] != t["phase"]:
            phases.append({"phase": t["phase"], "tasks": []})
        phases[-1]["tasks"].append({k: t[k] for k in ("id", "title", "detail", "ask")})
    return {"phases": phases}


# ---------------------------------------------------------------- admin
def _admin(token: str | None) -> None:
    expected = os.getenv("ADMIN_TOKEN", "")
    if not expected:
        raise HTTPException(403, "Admin is disabled. Set ADMIN_TOKEN to enable it.")
    if not token or not secrets.compare_digest(token, expected):
        raise HTTPException(401, "Bad admin token.")


@app.get("/api/admin/gaps")
def admin_gaps(x_admin_token: str | None = Header(default=None)):
    """Questions the agent could not answer, most asked first. Skips ones that are now covered."""
    _admin(x_admin_token)
    open_gaps = [g for g in store.gaps() if kb.search(g["example"]).confidence not in ("high", "medium")]
    return {"gaps": open_gaps[:50]}


@app.post("/api/admin/answers")
def admin_add_answer(body: AnswerIn, x_admin_token: str | None = Header(default=None)):
    """Add a new note. The agent can use it immediately."""
    _admin(x_admin_token)
    entry = store.add_community(body.title, body.text, body.tags, body.category, body.source, body.verified)
    kb.rebuild(BASE_KB + store.community())
    return {"added": entry, "total_entries": len(kb.entries)}


# ---------------------------------------------------------------- UI
app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")


@app.get("/")
def index():
    return FileResponse(BASE / "static" / "index.html")
