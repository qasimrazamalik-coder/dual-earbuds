#!/usr/bin/env python3
"""Checks the help content format (docs/assistant-and-mcp.md section 1). Stdlib only.

Every *.md file here must have: a '# Title' first line, a '<!-- keywords: ... -->' line right after it,
at least one '## ' section, at least one sentence in every section, no emoji, and no '## ' heading that
also appears in another file. _synonyms.txt lines must look like 'word: alt1, alt2'.
Prints the section count per file and exits non-zero on any problem.

Usage: python3 docs/help/check.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

EMOJI = re.compile(
    "[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF"
    "\U00002B00-\U00002BFF\U0000FE0F\U0000200D\U00002300-\U000023FF]"
)
KEYWORDS = re.compile(r"^<!-- keywords: (.+) -->$")
SENTENCE = re.compile(r"[A-Za-z0-9)\"'*%]\s*[.?!](\s|$)")
SYNONYM = re.compile(r"^[^:,]+: [^:]+(, [^:,]+)*$")


def check_file(path, seen, problems):
    name = os.path.basename(path)
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()

    def bad(msg, line=None):
        problems.append("%s%s: %s" % (name, "" if line is None else ":%d" % line, msg))

    if not lines or not lines[0].startswith("# ") or len(lines[0]) < 3:
        bad("first line must be '# Title'", 1)
    m = KEYWORDS.match(lines[1]) if len(lines) > 1 else None
    if not m:
        bad("second line must be '<!-- keywords: ... -->'", 2)
    elif len([k for k in m.group(1).split(",") if k.strip()]) < 3:
        bad("keywords line needs at least 3 words", 2)

    sections = []  # (heading, line number, body lines)
    for i, line in enumerate(lines, 1):
        if EMOJI.search(line):
            bad("emoji or pictograph", i)
        if line.startswith("## "):
            sections.append((line[3:].strip(), i, []))
        elif line.startswith("# ") and i != 1:
            bad("only one '# ' title per file", i)
        elif line.startswith("###"):
            bad("use '##' sections only", i)
        elif sections:
            sections[-1][2].append(line)

    if not sections:
        bad("no '## ' sections")
    for heading, ln, body in sections:
        if not heading:
            bad("empty heading", ln)
        key = heading.lower()
        if key in seen:
            bad("duplicate heading, also in %s: %s" % (seen[key], heading), ln)
        else:
            seen[key] = name
        text = " ".join(l.strip() for l in body if l.strip())
        if not text:
            bad("section has no body: %s" % heading, ln)
        elif not SENTENCE.search(text):
            bad("section has no full sentence: %s" % heading, ln)
        first = next((l.strip() for l in body if l.strip()), "")
        if re.match(r"^(\d+\.\s|\*\*[^*]+:\*\*)", first):
            bad("section should open with a direct answer, not a list or note: %s" % heading, ln)
    return len(sections)


def check_synonyms(path, problems):
    if not os.path.exists(path):
        problems.append("_synonyms.txt is missing")
        return 0
    with open(path, encoding="utf-8") as f:
        lines = [l.rstrip("\n") for l in f]
    n = 0
    for i, line in enumerate(lines, 1):
        if not line.strip():
            continue
        n += 1
        if not SYNONYM.match(line):
            problems.append("_synonyms.txt:%d: expected 'word: alt1, alt2'" % i)
        if EMOJI.search(line):
            problems.append("_synonyms.txt:%d: emoji or pictograph" % i)
    if not 60 <= n <= 120:
        problems.append("_synonyms.txt: %d lines, expected 60-120" % n)
    return n


def main():
    problems, seen, total = [], {}, 0
    files = sorted(f for f in os.listdir(HERE) if f.endswith(".md"))
    if not files:
        problems.append("no help files")
    for f in files:
        n = check_file(os.path.join(HERE, f), seen, problems)
        total += n
        print("%-28s %3d sections" % (f, n))
    print("%-28s %3d sections in %d files" % ("total", total, len(files)))
    syn = check_synonyms(os.path.join(HERE, "_synonyms.txt"), problems)
    print("%-28s %3d lines" % ("_synonyms.txt", syn))
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print("  " + p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
