#!/usr/bin/env python3
"""ACA Bridge — authenticated loopback HTTP API in front of the existing Termux engine.

Design rules (from the build directive):
  * binds 127.0.0.1 only; every request needs  Authorization: Bearer <token>
  * NO shell execution of client input: only two fixed engine entry points are ever spawned
      ~/.android_skills/run_skill <skill-name>        (name validated + must exist)
      ~/.android_skills/execute_intent "<text>"       (argv element, never a shell string)
  * one task at a time (single phone, single engine)
  * success is reported ONLY from engine evidence (exit code + marker lines), with an explicit
    evidence level; the bridge never invents success.
  * the engine files are never modified.
"""
import json, os, re, secrets, signal, subprocess, sys, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.environ.get("HOME", os.path.expanduser("~"))
SK = os.path.join(HOME, ".android_skills")
MEM = os.path.join(HOME, ".android_memory")
TOKEN_FILE = os.path.join(MEM, "bridge_token")
HIST_FILE = os.path.join(MEM, "bridge_tasks.jsonl")
HOST, PORT = "127.0.0.1", int(os.environ.get("ACA_BRIDGE_PORT", "8787"))
DEFAULT_TIMEOUT = int(os.environ.get("ACA_TASK_TIMEOUT", "120"))
NAME_RE = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
# Skills whose effect is not idempotent / externally visible need an explicit confirmed:true.
SENSITIVE_RE = re.compile(r"like|save|repost|comment|post|share|follow|delete|send|story|upload", re.I)
VERIFIED_MARKERS = ("SUCCESS", "ALREADY_THERE", "ALREADY_DONE", "confirmed")      # resource-id skills verify selected=true
UNVERIFIED_MARKERS = ("MULTI_STEP_SUCCESS", "CLICK_SUCCESS", "BATCH_LIMIT")        # clicks done, end state not checked
FAIL_MARKERS = ("VERIFICATION_FAILED", "ELEMENT_NOT_FOUND", "DUMP_FAILED", "TARGET_NOT_FOUND",
                "CURRENT_TARGET_NOT_FOUND", "SKILL_NOT_FOUND", "NO_EXECUTION_PATH", "UI_DUMP_FAILED",
                "VERIFY_DUMP_FAILED", "REPLAY_CLICK_FAILED", "Shizuku", "Request timeout")

PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
ACT_RE = re.compile(r"^[A-Za-z0-9_.$]+$")
ALLOW_AGENT = os.environ.get("ACA_ALLOW_AGENT") == "1"      # opt-in: lets Hermes (LLM + terminal) handle unknown requests
HERMES = os.environ.get("ACA_HERMES_BIN", "hermes")
AGENT_MAX_TURNS = int(os.environ.get("ACA_AGENT_MAX_TURNS", "25"))

lock = threading.Lock()
tasks = {}          # id -> dict
current = {"id": None, "proc": None}

def load_token():
    os.makedirs(MEM, exist_ok=True)
    if not os.path.exists(TOKEN_FILE):
        fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f: f.write(secrets.token_hex(24))
    return open(TOKEN_FILE).read().strip()

def read_conf(path):
    kv = {}
    try:
        for line in open(path, encoding="utf-8", errors="replace"):
            line = line.rstrip("\n")
            if "=" in line and not line.startswith(("#", " ")) and not line.startswith("STEP_"):
                k, _, v = line.partition("=")
                kv[k.strip()] = v.strip().strip('"')
    except OSError:
        pass
    return kv

def list_skills():
    out = []
    if not os.path.isdir(SK): return out
    for name in sorted(os.listdir(SK)):
        p = os.path.join(SK, name)
        if os.path.isfile(os.path.join(p, "skill.conf")):
            kv = read_conf(os.path.join(p, "skill.conf"))
            steps = sum(1 for l in open(os.path.join(p, "skill.conf"), errors="replace") if l.startswith("STEP_TARGET="))
            replay_ok = kv.get("REPLAY_VALID") != "no"
            out.append({"name": name, "app": kv.get("APP", ""), "target": kv.get("TARGET", ""),
                        "control": kv.get("CONTROL", ""), "steps": steps, "runnable": replay_ok,
                        "needs_confirmation": bool(SENSITIVE_RE.search(name)),
                        "evidence": "verified" if kv.get("CONTROL") == "open_and_click_resource_id" else "executed_unverified",
                        "note": "" if replay_ok else "marked REPLAY_VALID=no"})
        elif os.path.isfile(p) and name.startswith(("instagram.like", "instagram.save")):
            out.append({"name": name, "app": "com.instagram.android", "target": "", "control": "script",
                        "steps": 0, "runnable": False, "needs_confirmation": True, "evidence": "n/a",
                        "note": "standalone script; run_skill has no entry point for it"})
    return out

def classify(rc, text, timed_out, cancelled, kind=""):
    """Success needs exit code 0 AND an explicit success line printed by the engine.
    verified            = engine confirmed the target is selected (SUCCESS / ALREADY_THERE)
    executed_unverified = multi-step clicks completed, end state not checked by the engine."""
    if cancelled: return "cancelled", "none"
    if timed_out: return "timeout", "none"
    lines = {l.strip() for l in text.splitlines()}
    if kind == "agent":
        # Hermes output is the LLM's own claim: never "verified". Needs exit 0 and non-empty final text.
        return ("success", "agent_reported") if rc == 0 and text.strip() else ("failed", "none")
    if rc != 0 or "VERIFICATION_FAILED" in lines: return "failed", "none"
    if lines & {"SUCCESS", "ALREADY_THERE", "ALREADY_DONE"}: return "success", "verified"
    if "MULTI_STEP_SUCCESS" in lines or "CLICK_SUCCESS" in lines: return "success", "executed_unverified"
    return "failed", "none"                # exit 0 without evidence => never claim success

def failure_reason(text):
    for m in FAIL_MARKERS:
        if m in text: return m
    return "NO_SUCCESS_EVIDENCE" if text.strip() else "NO_OUTPUT"

def rish(cmd, timeout=12):
    p = os.path.join(HOME, ".rish", "rish")
    if not os.access(p, os.X_OK): return None
    try:
        return subprocess.run([p, "-c", cmd], capture_output=True, text=True, timeout=timeout)
    except Exception:
        return None

def ensure_app(t):
    """Bring the skill's app to the foreground BEFORE the engine runs. Fixed command; package/activity are
    read from the skill.conf and validated; nothing from the client is interpolated. Evidence is added to the task output."""
    app, act = t.get("app"), t.get("activity")
    if not app or not PKG_RE.match(app) or not (act and ACT_RE.match(act)):
        return
    def fg():
        r = rish("dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity", 10)
        return None if r is None else (app in r.stdout)
    cur = fg()
    if cur is None:
        t["lines"].append("APP_LAUNCH_SKIPPED: rish unavailable (engine will try itself)"); return
    if cur:
        t["lines"].append("APP_ALREADY_FOREGROUND=%s" % app); return
    rish("am start -n %s/%s" % (app, act), 12)
    time.sleep(2)
    t["lines"].append("APP_LAUNCHED=%s" % app if fg() else "APP_LAUNCH_UNCONFIRMED=%s (engine will retry)" % app)

def run_task(t):
    ensure_app(t)
    argv = t["argv"]
    try:
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                errors="replace", start_new_session=True, cwd=HOME)
    except OSError as e:
        with lock:
            t.update(state="failed", evidence="none", reason="ENGINE_UNAVAILABLE: %s" % e, ended=time.time())
            current.update(id=None, proc=None)
        save(t); return
    with lock: current["proc"] = proc
    timed_out = [False]
    def killer():
        time.sleep(t["timeout"])
        if proc.poll() is None:
            timed_out[0] = True
            kill(proc)
    threading.Thread(target=killer, daemon=True).start()
    for line in proc.stdout:
        with lock: t["lines"].append(line.rstrip("\n"))
        if len(t["lines"]) > 400:
            with lock: t["lines"] = t["lines"][-400:]
    rc = proc.wait()
    text = "\n".join(t["lines"])
    state, ev = classify(rc, text, timed_out[0], t["cancel"], t["kind"])
    with lock:
        t.update(state=state, evidence=ev, exit_code=rc, ended=time.time(),
                 reason="" if state == "success" else ("CANCELLED" if state == "cancelled" else
                        "TIMEOUT after %ss" % t["timeout"] if state == "timeout" else failure_reason(text)))
        current.update(id=None, proc=None)
    save(t)

def kill(proc):
    try: os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except Exception: pass
    def hard():
        time.sleep(2)
        if proc.poll() is None:
            try: os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except Exception: pass
    threading.Thread(target=hard, daemon=True).start()

def save(t):
    row = {k: v for k, v in t.items() if k not in ("argv", "cancel", "app", "activity")}
    row["lines"] = row["lines"][-60:]
    try:
        with open(HIST_FILE, "a") as f: f.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError: pass

def view(t, full=True):
    d = {k: t[k] for k in ("id", "kind", "skill", "intent", "state", "evidence", "reason", "exit_code", "started", "ended", "timeout")}
    d["steps_seen"] = [l for l in t["lines"] if l.startswith(("STEP_TARGET=", "TARGET_READY=", "WAITING_FOR_TARGET", "EXECUTION_SKILL", "FAST_REPLAY", "COMPOSITE_"))]
    if full: d["output"] = t["lines"][-120:]
    return d

def health(deep):
    h = {"bridge": "ok", "engine_present": os.access(os.path.join(SK, "run_skill"), os.X_OK),
         "execute_intent_present": os.access(os.path.join(SK, "execute_intent"), os.X_OK),
         "rish_present": os.access(os.path.join(HOME, ".rish", "rish"), os.X_OK),
         "control_sh_present": os.path.isfile(os.path.join(HOME, ".android_control.sh")),
         "busy": current["id"], "timeout_default": DEFAULT_TIMEOUT, "agent_mode": ALLOW_AGENT}
    if deep and h["rish_present"]:
        try:   # fixed read-only command, no client input
            r = subprocess.run([os.path.join(HOME, ".rish", "rish"), "-c",
                                "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity"],
                               capture_output=True, text=True, timeout=10)
            m = re.search(r"([a-zA-Z0-9_.]+)/", r.stdout)
            h["foreground"] = m.group(1) if m else ""
            h["gateway"] = "ok" if r.returncode == 0 and r.stdout.strip() else "degraded"
        except Exception as e:
            h["gateway"] = "unreachable: %s" % type(e).__name__
    return h

class H(BaseHTTPRequestHandler):
    server_version = "ACABridge/1"
    def log_message(self, *a): pass           # never log headers/tokens
    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _auth(self):
        given = self.headers.get("Authorization", "")
        ok = given.startswith("Bearer ") and secrets.compare_digest(given[7:], self.server.token)
        if not ok: self._send(401, {"error": "unauthorized"})
        return ok
    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 4096: raise ValueError("body too large")
        return json.loads(self.rfile.read(n) or b"{}")
    def do_GET(self):
        if not self._auth(): return
        path, _, qs = self.path.partition("?")
        if path == "/health": return self._send(200, health("deep=1" in qs))
        if path == "/skills": return self._send(200, {"skills": list_skills()})
        if path == "/tasks":
            with lock: rows = sorted(tasks.values(), key=lambda t: -t["started"])[:30]
            return self._send(200, {"tasks": [view(t, False) for t in rows]})
        m = re.fullmatch(r"/tasks/([0-9a-f]{12})", path)
        if m:
            t = tasks.get(m.group(1))
            return self._send(200, view(t)) if t else self._send(404, {"error": "unknown task"})
        self._send(404, {"error": "not found"})
    def do_POST(self):
        if not self._auth(): return
        m = re.fullmatch(r"/tasks/([0-9a-f]{12})/cancel", self.path)
        if m:
            t = tasks.get(m.group(1))
            if not t: return self._send(404, {"error": "unknown task"})
            with lock:
                if t["state"] != "running": return self._send(409, {"error": "task not running", "state": t["state"]})
                t["cancel"] = True
                if current["proc"]: kill(current["proc"])
            return self._send(202, {"id": t["id"], "state": "cancelling"})
        if self.path != "/tasks": return self._send(404, {"error": "not found"})
        try: body = self._body()
        except Exception as e: return self._send(400, {"error": "bad request: %s" % e})
        skill, intent, agent = body.get("skill"), body.get("intent"), body.get("agent")
        if sum(x is not None for x in (skill, intent, agent)) != 1:
            return self._send(400, {"error": "provide exactly one of skill|intent|agent"})
        app = activity = None
        timeout = max(10, min(int(body.get("timeout", DEFAULT_TIMEOUT)), 600))
        if skill is not None:
            if not isinstance(skill, str) or not NAME_RE.match(skill): return self._send(400, {"error": "invalid skill name"})
            known = {s["name"]: s for s in list_skills()}
            if skill not in known or not known[skill]["runnable"]:
                return self._send(404, {"error": "skill not runnable or unknown", "skill": skill})
            sensitive = known[skill]["needs_confirmation"]
            argv = [os.path.join(SK, "run_skill"), skill]
            kv = read_conf(os.path.join(SK, skill, "skill.conf")); app, activity = kv.get("APP"), kv.get("ACTIVITY")
        elif agent is not None:
            if not ALLOW_AGENT: return self._send(403, {"error": "agent_mode_disabled", "detail": "start the bridge with --agent to enable"})
            if not isinstance(agent, str) or not (1 <= len(agent) <= 400) or "\x00" in agent:
                return self._send(400, {"error": "invalid agent request"})
            sensitive = True                                   # an LLM with a terminal: ALWAYS needs explicit confirmation
            prompt = ("Android Control Agent task. Use the android-control skill first (known deterministic skills), then "
                      "android-discovery for anything unknown. Open the target app yourself if needed. Verify every UI transition "
                      "from real UI evidence; never claim success without it. Do not touch API keys, credentials or files outside "
                      "~/.android_skills and ~/.android_memory. Final answer: what was done, what was verified, what failed. "
                      "Task: " + agent)
            argv = [HERMES, "chat", "-q", prompt, "-Q", "--max-turns", str(AGENT_MAX_TURNS), "-s", "android-control,android-discovery"]
        else:
            if not isinstance(intent, str) or not (1 <= len(intent) <= 200) or "\x00" in intent:
                return self._send(400, {"error": "invalid intent"})
            sensitive = bool(SENSITIVE_RE.search(intent))
            argv = [os.path.join(SK, "execute_intent"), intent]
        if sensitive and body.get("confirmed") is not True:
            return self._send(428, {"error": "confirmation_required", "detail": "resend with confirmed:true after user approval"})
        if os.sep in argv[0]: ok_bin = os.access(argv[0], os.X_OK)
        else: ok_bin = any(os.access(os.path.join(d, argv[0]), os.X_OK) for d in os.environ.get("PATH", "").split(os.pathsep))
        if not ok_bin: return self._send(503, {"error": "engine_unavailable", "missing": argv[0]})
        with lock:
            if current["id"]: return self._send(409, {"error": "busy", "task": current["id"]})
            tid = uuid.uuid4().hex[:12]
            t = {"id": tid, "kind": "skill" if skill else ("agent" if agent is not None else "intent"), "skill": skill,
                 "intent": intent if intent is not None else agent, "argv": argv, "app": app, "activity": activity,
                 "state": "running", "evidence": "none", "reason": "", "exit_code": None, "started": time.time(),
                 "ended": None, "timeout": timeout, "lines": [], "cancel": False}
            tasks[tid] = t; current["id"] = tid
        threading.Thread(target=run_task, args=(t,), daemon=True).start()
        self._send(202, {"id": tid, "state": "running"})

def main():
    srv = ThreadingHTTPServer((HOST, PORT), H)
    srv.token = load_token()
    print("ACA bridge on http://%s:%d  (token in %s)" % (HOST, PORT, TOKEN_FILE), flush=True)
    srv.serve_forever()

if __name__ == "__main__":
    main()
