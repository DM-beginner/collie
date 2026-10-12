"""Recover missing Herdr session references from uniquely matching visible Codex messages.

Dry-run by default; --apply reports metadata only. Never sends input or changes
geometry. Replacing a mismatched Herdr integration reference needs the explicit
--replace-mismatched flag and the same unique visible evidence. Logs stay local.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

UUID = re.compile(r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$", re.I)
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
ANCHOR = 64


def normalize(text):
    # Prose is wrapped and Markdown emphasis is rendered by the terminal.
    return re.sub(r"[\s│┃`*#•●⎿]", "", ANSI.sub("", text))


def load_messages(path, root):
    if not path.resolve().is_relative_to(root.resolve()):
        return None
    try:
        with path.open("rb") as stream:
            first = json.loads(stream.readline())
            if first.get("type") != "session_meta":
                return None
            session_id = first.get("payload", {}).get("id", "")
            if not UUID.fullmatch(session_id):
                return None
            if not path.name.lower().endswith("-" + session_id.lower() + ".jsonl"):
                return None
        messages = []
        # A tail-only sample can miss the parent of a copied turn and make an
        # ambiguous fork appear unique. This opt-in repair scans complete logs.
        with path.open("rb") as stream:
            for line in stream:
                try:
                    row = json.loads(line)
                except (ValueError, UnicodeDecodeError):
                    continue
                payload = row.get("payload", {})
                if not isinstance(payload, dict):
                    continue
                if row.get("type") != "response_item":
                    continue
                # Agent-authored command/input text can distinguish a currently working
                # fork whose last prose reply was copied from its parent. Never use
                # tool OUTPUT or developer instructions as session evidence.
                if payload.get("type") in ("function_call", "custom_tool_call"):
                    value = payload.get("arguments", payload.get("input", ""))
                    if isinstance(value, str):
                        try:
                            values = json.loads(value)
                        except ValueError:
                            values = value
                        if isinstance(values, dict):
                            messages.extend(normalize(v) for v in values.values() if isinstance(v, str))
                        elif isinstance(values, str):
                            messages.append(normalize(values))
                    continue
                if payload.get("type") != "message":
                    continue
                if payload.get("role") not in ("user", "assistant"):
                    continue
                for block in payload.get("content", []):
                    if isinstance(block, dict) and isinstance(block.get("text"), str):
                        messages.append(normalize(block["text"]))
        return session_id, messages
    except (OSError, ValueError, TypeError):
        return None


def matching_sessions(screen, sessions):
    visible = normalize(screen)
    anchors = [visible[i:i + ANCHOR] for i in range(0, len(visible) - ANCHOR + 1, 16)]
    # Require a substantial textual anchor, never a timestamp/status or a tiny generic phrase.
    anchors = [a for a in anchors if len(re.findall(r"[\w\u4e00-\u9fff]", a)) >= 40]
    # Newest visible prose wins. Earlier copied turns must not outweigh a fork's
    # latest reply; conversely, an ambiguous latest reply must never fall back to
    # an older unique sentence and attach the wrong fork.
    for anchor in reversed(anchors):
        matches = sorted({session_id for session_id, messages in sessions
                          if any(anchor in message for message in messages)})
        if matches:
            return matches
    return []


def herdr(*args):
    done = subprocess.run(["herdr", *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=15)
    if done.returncode:
        raise RuntimeError("Herdr command failed: " + " ".join(args[:2]))
    return done.stdout


def agents():
    return json.loads(herdr("agent", "list"))["result"]["agents"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--replace-mismatched", action="store_true",
                        help="Also correct existing herdr:codex ID refs proven to name another session")
    parser.add_argument("--pane", help="Limit inspection to one currently running pane")
    args = parser.parse_args()
    if os.environ.get("HERDR_ENV") != "1":
        parser.error("Run from a Herdr-managed environment (HERDR_ENV=1).")
    root = Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))) / "sessions"
    sessions = []
    modified = {}
    for path in root.rglob("rollout-*.jsonl"):
        hit = load_messages(path, root)
        if hit is not None:
            sessions.append(hit)
            modified[hit[0]] = path.stat().st_mtime
    results = []
    for agent in agents():
        pane = agent["pane_id"]
        if agent.get("agent") != "codex" or (args.pane and pane != args.pane):
            continue
        process = json.loads(herdr("pane", "process-info", "--pane", pane))["result"]["process_info"]
        visible = herdr("pane", "read", pane, "--source", "visible", "--lines", "200", "--format", "text")
        matches = matching_sessions(visible, sessions)
        row = {"pane": pane, "status": "unmatched" if not matches else "ambiguous"}
        if len(matches) == 1:
            row.update(status="matched", session=matches[0])
            if agent.get("agent_status") == "working" and time.time() - modified[matches[0]] > 120:
                # A stuck/old terminal frame can uniquely match a yesterday's fork.
                # That is insufficient evidence for the currently working agent.
                row["status"] = "working agent; old journal evidence skipped"
                results.append(row)
                continue
            if agent.get("agent_session"):
                ref = agent["agent_session"]
                if ref.get("value") == matches[0]:
                    row["status"] = "already-reported"
                    results.append(row)
                    continue
                if not args.replace_mismatched or ref.get("source") != "herdr:codex" or ref.get("kind") != "id":
                    row["status"] = "mismatched; unchanged"
                    results.append(row)
                    continue
                if not any("resume" in p.get("argv", []) for p in process.get("foreground_processes", [])):
                    row["status"] = "mismatched; resume not verified"
                    results.append(row)
                    continue
                row["status"] = "mismatched; verified replacement"
            if args.apply:
                # Check identity and visible evidence again immediately before the metadata write.
                current = next((a for a in agents() if a["pane_id"] == pane), None)
                process_now = json.loads(herdr("pane", "process-info", "--pane", pane))["result"]["process_info"]
                if (current is None or current.get("agent") != "codex" or current.get("agent_session") != agent.get("agent_session")
                        or process_now.get("foreground_process_group_id") != process.get("foreground_process_group_id")
                        or process_now.get("shell_pid") != process.get("shell_pid")):
                    row["status"] = "changed; skipped"
                else:
                    reread = herdr("pane", "read", pane, "--source", "visible", "--lines", "200", "--format", "text")
                    if matching_sessions(reread, sessions) != matches:
                        row["status"] = "changed; skipped"
                    else:
                        arguments = ["pane", "report-agent-session", pane,
                            "--source", "herdr:codex", "--agent", "codex",
                            "--seq", str(time.time_ns() // 1_000_000), "--agent-session-id", matches[0]]
                        if agent.get("agent_session"):
                            arguments += ["--session-start-source", "resume"]
                        herdr(*arguments)
                        # The CLI intentionally has no stdout on a successful report.
                        # Verify the stored value rather than treating quiet output as failure.
                        verified = next((a for a in agents() if a["pane_id"] == pane), {})
                        if verified.get("agent_session", {}).get("value") != matches[0]:
                            raise RuntimeError("Herdr did not retain session metadata for " + pane)
                        row["status"] = "repaired"
        results.append(row)
    print(json.dumps({"apply": args.apply, "results": results}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
