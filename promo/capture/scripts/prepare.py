"""Turns the scripted run's words and edits into what the capture harness streams.

texts.json holds everything the agent says or thinks; each text is cut into o200k_base tokens (tiktoken) so the
harness can pace it by real token counts. A token that ends partway through a UTF-8 character is joined with the
next, so every piece decodes on its own and still counts every token it holds.

edits.json holds each edit's file before and after; the diff the app shows and the +/- counts it reports are both
computed from them here, so they always agree. A file edited twice in the run has a key per edit, `path#name` for
the later ones. Each edit also counts the tokens its added lines hold, which the harness takes at least as long to
write as it takes to stream as many; and each file's whole change, first before to last after, is what the pull
request shows.

    python3 promo/capture/scripts/prepare.py   # writes promo/capture/generated/script.json
"""

import difflib
import json
import pathlib

import tiktoken

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "generated" / "script.json"


def pieces(enc, text):
    out = []
    pending = b""
    count = 0
    for token in enc.encode(text):
        pending += enc.decode_single_token_bytes(token)
        count += 1
        try:
            decoded = pending.decode("utf-8")
        except UnicodeDecodeError:
            continue
        out.append([decoded, count])
        pending = b""
        count = 0
    if pending:
        raise ValueError(f"text ends inside a character: {text!r}")
    assert "".join(p for p, _ in out) == text
    return out


def diff(path, before, after):
    lines = list(
        difflib.unified_diff(
            before.splitlines(keepends=True),
            after.splitlines(keepends=True),
            fromfile="/dev/null" if not before else f"a/{path}",
            tofile=f"b/{path}",
            n=3,
        )
    )
    body = [line for line in lines[2:]]
    added = sum(1 for line in body if line.startswith("+"))
    removed = sum(1 for line in body if line.startswith("-"))
    return "".join(body).rstrip("\n"), added, removed


def main():
    enc = tiktoken.get_encoding("o200k_base")
    texts = json.loads((HERE / "texts.json").read_text())
    edits = json.loads((HERE / "edits.json").read_text())
    script = {"texts": {}, "edits": {}, "pr": {}}
    for key, text in texts.items():
        p = pieces(enc, text)
        script["texts"][key] = {"tokens": sum(n for _, n in p), "pieces": p}
    whole = {}
    for key, change in edits.items():
        path = key.split("#")[0]
        d, added, removed = diff(path, change["before"], change["after"])
        written = "".join(line[1:] + "\n" for line in d.splitlines() if line.startswith("+"))
        script["edits"][key] = {"path": path, "diff": d, "added": added, "removed": removed, "tokens": len(enc.encode(written))}
        first = whole.get(path, (change["before"], None))[0]
        whole[path] = (first, change["after"])
    for path, (before, after) in whole.items():
        d, added, removed = diff(path, before, after)
        script["pr"][path] = {"diff": d, "added": added, "removed": removed}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(script, ensure_ascii=False, indent=1) + "\n")
    for key, value in script["texts"].items():
        print(f"{key:18} {value['tokens']:4} tokens")
    for key, value in script["edits"].items():
        print(f"{key:36} +{value['added']} -{value['removed']}  {value['tokens']} tokens")
    for path, value in script["pr"].items():
        print(f"pr {path:33} +{value['added']} -{value['removed']}")


if __name__ == "__main__":
    main()
