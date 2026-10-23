#!/usr/bin/env python3
"""PhonePi bot-API failover front (box side).

Bots keep calling http://127.0.0.1:11043 unchanged. This process owns that port
and forwards each request to ONE backend:

  hp   - the HP's PhonePi server bot API (HP 127.0.0.1:11043), reached through an
         SSH direct-tcpip channel (paramiko) over the box's Tailscale SOCKS5 proxy.
  box  - the box's own PhonePi server, whose bot API moved to 127.0.0.1:11045.

The phone keeps exactly one WebSocket, to one server. Routing follows the phone:
  1. HP up and phoneConnected        -> hp
  2. box up and phoneConnected       -> box
  3. phone on neither: the server the phone was last on (sticky), else hp if up, else box.
A connect failure (nothing sent yet) retries once on the other backend if it is up.
A request that already reached a backend is never replayed (no double commands).

Logs carry method, path, status and backend only. Never headers or bodies (token).
State (no secrets): ~/.phonepi/failover-state.json. GET /api/failover returns it.
Optional override: ~/.phonepi/failover-pin containing "hp" or "box".

History sync (standby only): every PHONEPI_HISTORY_SYNC_SEC (default 1800), when the phone
is on one server and the other is up without it, the standby's notifications/ and
location/ month files become the line-union of both copies, then the standby server is
restarted so it loads them. Both logs are append-merge safe (same id + newer updatedAt
wins). The active server is never written or restarted by this.
"""
import http.client, http.server, json, os, socket, socketserver, sys, threading, time

import paramiko, socks

HOME = os.path.expanduser("~")
PP = os.path.join(HOME, ".phonepi")
LISTEN = ("127.0.0.1", int(os.environ.get("PHONEPI_FRONT_PORT", "11043")))
BOX = ("127.0.0.1", int(os.environ.get("PHONEPI_BOX_HTTP_PORT", "11045")))
HP_HOST = os.environ.get("PHONEPI_HP_HOST", "100.108.138.113")
HP_USER = os.environ.get("PHONEPI_HP_USER", "belhun")
HP_API_PORT = int(os.environ.get("PHONEPI_HP_HTTP_PORT", "11043"))
KEY = os.environ.get("PHONEPI_HP_KEY", os.path.join(PP, "ssh", "grok-assistant"))
KNOWN = os.path.join(PP, "ssh", "known_hosts")
SOCKS = ("127.0.0.1", 1055)
STATE = os.path.join(PP, "failover-state.json")
PIN = os.path.join(PP, "failover-pin")
POLL = float(os.environ.get("PHONEPI_FAILOVER_POLL", "5"))
TIMEOUT = 60
HOP = {"connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "host"}


def log(msg):
    print(time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), msg, flush=True)


class HpLink:
    """One SSH transport to the HP, reopened on demand."""

    def __init__(self):
        self.lock = threading.Lock()
        self.client = None
        self.next_try = 0.0

    def _connect(self):
        key = paramiko.Ed25519Key.from_private_key_file(KEY)
        s = socks.socksocket()
        s.set_proxy(socks.SOCKS5, *SOCKS)
        s.settimeout(15)
        s.connect((HP_HOST, 22))
        c = paramiko.SSHClient()
        if os.path.exists(KNOWN):
            c.load_host_keys(KNOWN)
        if c.get_host_keys().lookup(HP_HOST):
            c.set_missing_host_key_policy(paramiko.RejectPolicy())
        else:
            c.set_missing_host_key_policy(paramiko.AutoAddPolicy())  # first use, then pinned
        c.connect(HP_HOST, username=HP_USER, pkey=key, sock=s, allow_agent=False,
                  look_for_keys=False, timeout=15, banner_timeout=15, auth_timeout=15)
        c.get_transport().set_keepalive(20)
        if not os.path.exists(KNOWN) or not paramiko.HostKeys(KNOWN).lookup(HP_HOST):
            c.save_host_keys(KNOWN)
            os.chmod(KNOWN, 0o600)
        return c

    def channel(self):
        with self.lock:
            t = self.client.get_transport() if self.client else None
            if not (t and t.is_active()):
                if time.time() < self.next_try:
                    raise ConnectionError("hp link backing off")
                try:
                    if self.client:
                        self.client.close()
                    self.client = self._connect()
                    log("hp ssh link up")
                except Exception as e:
                    self.client = None
                    self.next_try = time.time() + 10
                    raise ConnectionError("hp link: %s" % type(e).__name__)
                t = self.client.get_transport()
        ch = t.open_channel("direct-tcpip", ("127.0.0.1", HP_API_PORT), ("127.0.0.1", 0), timeout=10)
        ch.settimeout(TIMEOUT)
        return ch


HP = HpLink()


def open_conn(backend):
    if backend == "box":
        c = http.client.HTTPConnection(*BOX, timeout=TIMEOUT)
        c.connect()
        return c
    c = http.client.HTTPConnection("127.0.0.1", HP_API_PORT, timeout=TIMEOUT)
    c.sock = HP.channel()
    return c


def get_health(backend):
    try:
        c = open_conn(backend)
        c.request("GET", "/api/health")
        r = c.getresponse()
        body = r.read()
        c.close()
        h = json.loads(body)
        return {"up": bool(h.get("ok")), "phoneConnected": bool(h.get("phoneConnected")),
                "lastConnectedAt": h.get("lastConnectedAt"), "lastDisconnectedAt": h.get("lastDisconnectedAt")}
    except Exception as e:
        return {"up": False, "phoneConnected": False, "error": type(e).__name__}


class Router:
    def __init__(self):
        self.lock = threading.Lock()
        self.state = {"active": "box", "reason": "starting", "hp": {}, "box": {}, "sticky": None}
        try:
            self.state["sticky"] = json.load(open(STATE)).get("sticky")
        except Exception:
            pass

    def decide(self, hp, box):
        pin = None
        try:
            pin = open(PIN).read().strip() or None
        except Exception:
            pass
        sticky = self.state.get("sticky")
        if hp["phoneConnected"] and hp["up"]:
            sticky = "hp"
        elif box["phoneConnected"] and box["up"]:
            sticky = "box"
        if pin in ("hp", "box") and (hp if pin == "hp" else box)["up"]:
            return pin, "pinned to %s" % pin, sticky
        if hp["up"] and hp["phoneConnected"]:
            return "hp", "phone is on the HP", sticky
        if box["up"] and box["phoneConnected"]:
            return "box", "phone is on the box", sticky
        if sticky and (hp if sticky == "hp" else box)["up"]:
            return sticky, "phone offline; last on %s" % sticky, sticky
        if hp["up"]:
            return "hp", "phone offline; HP is primary", sticky
        if box["up"]:
            return "box", "phone offline; HP down", sticky
        return "box", "both servers down", sticky

    def refresh(self):
        hp, box = get_health("hp"), get_health("box")
        active, reason, sticky = self.decide(hp, box)
        with self.lock:
            changed = active != self.state.get("active")
            self.state = {"active": active, "reason": reason, "sticky": sticky, "hp": hp, "box": box,
                          "checkedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        if changed:
            log("route -> %s (%s)" % (active, reason))
        tmp = STATE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.state, f, indent=1)
        os.chmod(tmp, 0o600)
        os.replace(tmp, STATE)

    def active(self):
        with self.lock:
            return self.state["active"], dict(self.state)


ROUTER = Router()


SYNC_EVERY = float(os.environ.get("PHONEPI_HISTORY_SYNC_SEC", "1800"))
HIST_DIRS = ("notifications", "location")
HP_PP = "/home/%s/.phonepi" % HP_USER


def _read_side(side, sftp):
    out = {}
    for d in HIST_DIRS:
        if side == "box":
            base = os.path.join(PP, d)
            names = [n for n in (os.listdir(base) if os.path.isdir(base) else []) if n.endswith(".jsonl")]
            for n in names:
                with open(os.path.join(base, n), "rb") as f:
                    out[(d, n)] = f.read()
        else:
            base = "%s/%s" % (HP_PP, d)
            try:
                names = [n for n in sftp.listdir(base) if n.endswith(".jsonl")]
            except IOError:
                names = []
            for n in names:
                with sftp.open("%s/%s" % (base, n), "rb") as f:
                    out[(d, n)] = f.read()
    return out


def _ver(obj):
    for k in ("updatedAt", "updatedAtMs", "capturedAt", "startMs"):
        v = obj.get(k)
        if isinstance(v, (int, float)):
            return v
    return 0


def _merge(dst, src):
    """dst plus every src entry that dst lacks or has an older version of (by id).
    Returns None when dst already has everything (so nothing is written or restarted)."""
    have = {}
    for ln in dst.splitlines():
        try:
            o = json.loads(ln)
            have[o.get("id")] = max(have.get(o.get("id"), 0), _ver(o))
        except Exception:
            pass
    extra = []
    for ln in src.splitlines():
        try:
            o = json.loads(ln)
        except Exception:
            continue
        i = o.get("id")
        if i is None:
            continue
        if i not in have or _ver(o) > have[i]:
            have[i] = _ver(o)
            extra.append(ln)
    if not extra:
        return None
    base = dst if (not dst or dst.endswith(b"\n")) else dst + b"\n"
    return base + b"\n".join(extra) + b"\n"


def sync_history(active, standby):
    c = HP.client if HP.client and HP.client.get_transport() and HP.client.get_transport().is_active() else None
    if c is None:
        HP.channel().close()  # opens the link
        c = HP.client
    sftp = c.open_sftp()
    try:
        src, dst = _read_side(active, sftp), _read_side(standby, sftp)
        changed = 0
        for key in set(src):
            merged = _merge(dst.get(key, b""), src.get(key, b""))
            if merged is None:
                continue
            changed += 1
            d, n = key
            if standby == "box":
                base = os.path.join(PP, d)
                os.makedirs(base, mode=0o700, exist_ok=True)
                tmp = os.path.join(base, "." + n + ".sync")
                with open(tmp, "wb") as f:
                    f.write(merged)
                os.chmod(tmp, 0o600)
                os.replace(tmp, os.path.join(base, n))
            else:
                base = "%s/%s" % (HP_PP, d)
                try:
                    sftp.stat(base)
                except IOError:
                    sftp.mkdir(base, 0o700)
                tmp = "%s/.%s.sync" % (base, n)
                with sftp.open(tmp, "wb") as f:
                    f.write(merged)
                sftp.chmod(tmp, 0o600)
                sftp.posix_rename(tmp, "%s/%s" % (base, n))
    finally:
        sftp.close()
    if not changed:
        return 0
    # Re-check right before restarting: never bounce the server the phone is on.
    if get_health(standby).get("phoneConnected"):
        log("history sync: %s got the phone meanwhile; files merged, restart skipped" % standby)
        return changed
    if standby == "hp":
        _, o, _ = c.exec_command("sudo -n systemctl restart phonepi-server", timeout=30)
        o.channel.recv_exit_status()
    else:
        try:
            os.kill(int(open(os.path.join(PP, "server.pid")).read().strip()), 15)  # watchdog restarts it
        except Exception as e:
            log("history sync: box restart failed %s" % type(e).__name__)
    log("history sync %s -> %s: %d file(s) merged, standby restarted" % (active, standby, changed))
    return changed


def sync_loop():
    time.sleep(60)
    while True:
        try:
            _, st = ROUTER.active()
            hp, box = st.get("hp", {}), st.get("box", {})
            if hp.get("phoneConnected") and box.get("up") and not box.get("phoneConnected"):
                sync_history("hp", "box")
            elif box.get("phoneConnected") and hp.get("up") and not hp.get("phoneConnected"):
                sync_history("box", "hp")
        except Exception as e:
            log("history sync error %s" % type(e).__name__)
        time.sleep(SYNC_EVERY)


def poll_loop():
    while True:
        try:
            ROUTER.refresh()
        except Exception as e:
            log("poll error %s" % type(e).__name__)
        time.sleep(POLL)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, obj, backend="front"):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-PhonePi-Server", backend)
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def _proxy(self):
        self.close_connection = True
        path = self.path
        if path.split("?")[0] == "/api/failover":
            return self._send(200, ROUTER.active()[1])
        n = int(self.headers.get("Content-Length") or 0)
        if n > 2 * 1024 * 1024:
            return self._send(413, {"error": "body too large"})
        body = self.rfile.read(n) if n else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP}
        first, st = ROUTER.active()
        order = [first] + ([o] if (o := ("box" if first == "hp" else "hp")) and st.get(o, {}).get("up") else [])
        last_err = None
        for backend in order:
            try:
                conn = open_conn(backend)
            except Exception as e:
                last_err = "%s connect: %s" % (backend, type(e).__name__)
                continue
            try:
                conn.request(self.command, path, body=body, headers=headers)
                r = conn.getresponse()
                data = r.read()
            except Exception as e:
                conn.close()
                log("%s %s -> %s error after send %s" % (self.command, path.split("?")[0], backend, type(e).__name__))
                return self._send(502, {"error": "PhonePi backend %s failed mid-request" % backend}, backend)
            conn.close()
            if path.split("?")[0] == "/api/health" and r.status == 200:
                try:
                    h = json.loads(data)
                    h["servedBy"] = backend
                    data = json.dumps(h).encode()
                except Exception:
                    pass
            self.send_response(r.status)
            for k, v in r.getheaders():
                if k.lower() not in HOP and k.lower() != "content-length":
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("X-PhonePi-Server", backend)
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            if path.split("?")[0] != "/api/health":
                log("%s %s -> %s %d" % (self.command, path.split("?")[0], backend, r.status))
            return
        log("%s %s -> no backend (%s)" % (self.command, path.split("?")[0], last_err))
        return self._send(503, {"error": "No PhonePi server reachable (HP and box both down)"})

    do_GET = do_POST = do_PUT = do_DELETE = _proxy


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 128


def main():
    srv = Server(LISTEN, Handler)  # bind first so a restart leaves 11043 dark as briefly as possible
    ROUTER.refresh()
    threading.Thread(target=poll_loop, daemon=True).start()
    if SYNC_EVERY > 0:
        threading.Thread(target=sync_loop, daemon=True).start()
    log("failover front on %s:%d (box backend :%d, hp %s); route=%s" % (LISTEN[0], LISTEN[1], BOX[1], HP_HOST, ROUTER.active()[0]))
    srv.serve_forever()


if __name__ == "__main__":
    main()
