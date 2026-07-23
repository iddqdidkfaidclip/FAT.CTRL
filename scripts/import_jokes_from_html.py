#!/usr/bin/env python3
"""One-off importer: Telegram HTML export -> src/main/resources/jokes.txt"""

import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/jokes.txt"
EXPORT_DIR = Path("/Users/iddqdpwn/Downloads/Telegram Desktop/ChatExport_2026-07-23")

AD_MARKERS = [
    "http://", "https://", "t.me/", "telegram.me/",
    "подписывай", "подпис", "реклам", "партнер", "розыгрыш", "конкурс",
    "промокод", "скидк", "донат", "ваканси",
    "сотрудничеств", "переходи", "казино",
    "взято из:", "читать продолжение", "залетай к нам",
    "инфоцыган", "рабочую схему",
]


def html_files() -> list[Path]:
    files = [EXPORT_DIR / "messages.html"]
    files.extend(EXPORT_DIR / f"messages{i}.html" for i in range(2, 20))
    return [p for p in files if p.is_file()]


def html_to_plain(raw: str) -> str:
    text = re.sub(r"<br\s*/?>", "\n", raw, flags=re.I)
    text = re.sub(r"</?strong>", "", text, flags=re.I)
    text = re.sub(r"<a\b[^>]*>", "", text, flags=re.I)
    text = re.sub(r"</a>", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    return html.unescape(text).strip()


def normalize(text: str) -> str:
    text = text.replace("\u00a0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def looks_like_joke(text: str) -> bool:
    if not (25 <= len(text) <= 1500):
        return False
    lower = text.lower()
    if any(m in lower for m in AD_MARKERS):
        return False
    if re.search(r"@\w{3,}", text):
        return False
    if lower.startswith(("—", "-")):
        return False
    if re.fullmatch(r"[\W\d\s]+", text):
        return False
    if re.fullmatch(r"[а-яёa-z]{1,18}", lower, flags=re.I):
        return False

    score = 0
    if "\n" in text:
        score += 1
    if "—" in text or ":" in text:
        score += 1
    if "— " in text or "- " in text:
        score += 1
    if re.search(r"[!?]\s*$", text):
        score += 1
    if re.search(
        r"\b(муж|жена|врач|психолог|блондин|теща|программист|бармен|штирлиц)\b",
        text,
        flags=re.I,
    ):
        score += 1
    if re.search(r"\b(скидка|подпишись|промокод|акция|реклама)\b", text, flags=re.I):
        score -= 3
    return score >= 1


def extract_from_html(content: str) -> list[str]:
    jokes: list[str] = []
    for chunk in re.split(r"""<div class="message """, content):
        if not chunk.startswith("default"):
            continue
        if "forwarded body" in chunk:
            continue
        marker = '<div class="text">'
        start = chunk.find(marker)
        if start == -1:
            continue
        start += len(marker)
        end = chunk.find("</div>", start)
        if end == -1:
            continue
        plain = html_to_plain(chunk[start:end])
        if plain:
            jokes.append(plain)
    return jokes


def main() -> int:
    files = html_files()
    if not files:
        print("No HTML files found", file=sys.stderr)
        return 1

    raw: list[str] = []
    for path in files:
        raw.extend(extract_from_html(path.read_text(encoding="utf-8")))
        print(f"  {path.name}: running total {len(raw)}")

    cleaned = [normalize(t) for t in raw if normalize(t)]
    jokes: list[str] = []
    seen: set[str] = set()
    for joke in cleaned:
        if not looks_like_joke(joke):
            continue
        key = joke.lower()
        if key in seen:
            continue
        seen.add(key)
        jokes.append(joke)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n---\n".join(jokes) + "\n", encoding="utf-8")

    print(f"Processed files: {len(files)}")
    print(f"Raw text messages: {len(raw)}")
    print(f"Candidate jokes: {len(jokes)}")
    print(f"Saved to: {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
