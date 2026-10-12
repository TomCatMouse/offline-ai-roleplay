#!/usr/bin/env python3
"""
模型切换服务（单实例架构）
==========================
同一时间只运行一个 llama-server（固定 8080 端口，省内存），
本服务监听 8090，负责「停旧模型 → 起新模型」的切换：

    GET  /status          → 当前运行的模型
    POST /switch {id}     → 切换到指定模型（阻塞直到就绪，最多90秒）

用法：
    python model_switcher.py [configs]

配置：
    与同目录 servers.config.json 一致：models 数组中每项
    {id, name, gguf, flags}，port 统一用 infer_port/switch_port。

启动后：
    - 自动启动配置中第一个有 gguf 的模型
    - App 端推理地址永远是 http://<PC_IP>:8080（不变）
    - App 切换模型时调用 http://<PC_IP>:8090/switch
"""

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.path.dirname(os.path.abspath(__file__))
CFG_PATH = os.path.join(BASE, "servers.config.json")

def _resolve(p: str) -> str:
    """相对路径按脚本所在目录解析；绝对路径原样返回（向后兼容旧配置）"""
    return p if os.path.isabs(p) else os.path.join(BASE, "..", p)

# 模型目录（反馈 #11）：只要把 .gguf 丢进这里就会被自动发现，
# 不用再手动往 servers.config.json 里加条目。
# 注意：配置里显式写过的项优先（可以自定义 name/flags），
# 目录里"多出来"的文件按文件名自动生成 id + 用 default_flags 启动。
MODELS_DIR = os.path.abspath(os.path.join(BASE, "..", "models", "gguf"))

# 上次成功加载的模型 id。
# 修 #3「推理服务状态的模型和模型切换不一致」：
# 原来每次启动都拉配置里的第一个模型，用户切到 Q5 之后一重启服务
# 就悄悄回到 Q6 —— App 里"选的"和"实际在跑的"于是对不上。
# 现在把最后一次成功的模型落盘，启动时优先沿用它。
STATE_PATH = os.path.join(BASE, ".last_model")

BIN_DIR = os.path.join(BASE, "..", "models", "x86_64", "llama-b11558-win-x64", "llama-server.exe")
BIN_DIR = os.path.abspath(BIN_DIR)

STATE = {
    "proc": None,          # 当前 llama-server 进程
    "active_id": None,     # 当前模型 id
    "switching": False,   # 是否正在切换中
}
STATE_LOCK = threading.Lock()


def log(msg: str):
    print(f"[switcher] {msg}", flush=True)


def load_cfg():
    with open(CFG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_last_model(model_id: str):
    """记住这次真正跑起来的模型，供下次启动沿用"""
    try:
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            f.write(model_id or "")
    except Exception as e:
        log(f"记录上次模型失败: {e}")


def load_last_model() -> str:
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def pick_model(cfg, model_id: str):
    """按 id 找到**且 gguf 真实存在**的模型项（含自动扫到的）"""
    if not model_id:
        return None
    return next(
        (m for m in all_models(cfg)
         if m.get("id") == model_id and m.get("gguf") and os.path.exists(m.get("gguf", ""))),
        None,
    )


def _slug(filename: str) -> str:
    """文件名 → 稳定 id。

    必须可复现：App 端要按 id 记住"上次选的模型"、显示"运行中"标记，
    每次启动都变的随机 id 会让这两件事全废。
    """
    stem = os.path.splitext(os.path.basename(filename))[0]
    s = re.sub(r"[^0-9A-Za-z]+", "-", stem).strip("-").lower()
    return s or "model"


def scan_models_dir(cfg):
    """扫描 models/gguf，把配置里没写过的 gguf 自动并成模型项（反馈 #11）"""
    known = set()
    for m in cfg.get("models", []):
        g = m.get("gguf")
        if g:
            # 必须走 _resolve：配置里写的是相对路径（models\gguf\x.gguf），
            # 直接 abspath 会按**进程当前目录**算，和实际加载时用的目录不一致，
            # 结果就是配置项被当成"没登记过"，同一个文件又被自动扫出一份。
            known.add(os.path.normcase(os.path.abspath(_resolve(g))))

    # 自动项的启动参数：优先配置顶层 default_flags，否则抄第一个模型
    default_flags = cfg.get("default_flags")
    if not default_flags and cfg.get("models"):
        default_flags = cfg["models"][0].get("flags") or []

    auto = []
    if not os.path.isdir(MODELS_DIR):
        return auto
    used_ids = {m.get("id") for m in cfg.get("models", [])}
    for fn in sorted(os.listdir(MODELS_DIR)):
        if not fn.lower().endswith(".gguf"):
            continue
        full = os.path.abspath(os.path.join(MODELS_DIR, fn))
        if os.path.normcase(full) in known:
            continue
        mid, n = _slug(fn), 2
        while mid in used_ids:
            mid = f"{_slug(fn)}-{n}"
            n += 1
        used_ids.add(mid)
        auto.append({
            "id": mid,
            "name": fn,
            "gguf": full,
            "flags": list(default_flags or []),
            "auto": True,
        })
    return auto


def all_models(cfg):
    """配置里的模型 + 目录里自动扫到的模型（配置项在前，顺序稳定）

    配置项的 gguf 一律先经 [_resolve] 归一成**绝对路径**再交出去：
    本文件其它地方（/models 的 exists 与 size、pick_model、handle_switch、
    start_model）都是直接 `os.path.exists(m["gguf"])`，而配置里写的是相对路径。
    以往只有 main() 的兜底那一行做了 _resolve，所以只要切换器的启动目录不是
    项目根，配置里的模型就会全部被判成"不存在"（/models 显示 exists:false、
    size:0），App 按 id 切模型时直接报"模型文件不存在"。
    在这里统一归一，下面所有消费点就都对了。
    """
    configured = []
    for m in cfg.get("models", []):
        item = dict(m)
        g = item.get("gguf")
        if g:
            item["gguf"] = _resolve(g)
        configured.append(item)
    return configured + scan_models_dir(cfg)


def model_infer_url(cfg, port) -> str:
    return f"http://127.0.0.1:{port}/health"


def wait_ready(port: int, timeout: int = 60) -> bool:
    """等待 8080 健康检查通过"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(model_infer_url(None, port), timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(1.0)
    return False


def stop_current():
    """停止当前 llama-server"""
    with STATE_LOCK:
        proc, STATE["proc"] = STATE["proc"], None
        active, STATE["active_id"] = STATE["active_id"], None
    if proc is None:
        return
    log(f"停止当前模型 {active} (pid={proc.pid})")
    try:
        proc.terminate()
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        try:
            proc.wait(timeout=3)
        except Exception:
            pass
    time.sleep(1)  # 等端口释放


def start_model(cfg, model: dict) -> bool:
    """启动指定模型"""
    gguf = model.get("gguf", "")
    if not gguf or not os.path.exists(gguf):
        log(f"模型 {model['id']} 的 gguf 不存在: {gguf}")
        return False

    d = cfg.get("defaults", {})
    cmd = [
        BIN_DIR,
        "-m", gguf,
        "--host", str(cfg.get("host", "0.0.0.0")),
        "--port", str(cfg.get("infer_port", 8080)),
        "-ngl", str(d.get("ngl", 0)),
        "--ctx-size", str(d.get("ctx_size", 4096)),
        "--threads", str(d.get("threads", 4)),
    ]
    flags = model.get("flags", [])
    if isinstance(flags, list):
        cmd += flags

    log(f"启动模型 {model['id']} ({model.get('name', '')})")
    log("  cmd: " + " ".join(cmd))

    # 先停旧的
    stop_current()

    # 后台日志文件（避免阻塞）
    log_dir = BASE
    log_file = os.path.join(log_dir, f"llama-{model['id']}.log")
    fh = open(log_file, "a", encoding="utf-8")
    fh.write(f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} start {model['id']} =====\n")
    fh.flush()
    proc = subprocess.Popen(cmd, cwd=os.path.dirname(BIN_DIR), stdout=fh, stderr=subprocess.STDOUT)

    ok = wait_ready(cfg.get("infer_port", 8080), timeout=90)
    with STATE_LOCK:
        STATE["proc"] = proc if ok else None
        STATE["active_id"] = model["id"] if ok else None
    if ok:
        save_last_model(model["id"])
    log(f"模型 {model['id']} 启动{'成功' if ok else '失败'}")
    return ok


def handle_switch(cfg, model_id: str) -> dict:
    """切换到指定模型（阻塞直到就绪）"""
    model = next((m for m in all_models(cfg) if m.get("id") == model_id), None)
    if model is None:
        return {"ok": False, "error": f"未知模型: {model_id}"}
    if not model.get("gguf") or not os.path.exists(model["gguf"]):
        return {"ok": False, "error": f"模型文件不存在: {model.get('gguf')}"}

    with STATE_LOCK:
        if STATE["switching"]:
            return {"ok": False, "error": "已有切换任务进行中，请稍后重试"}
        STATE["switching"] = True

    try:
        ok = start_model(cfg, model)
        return {"ok": ok, "active": model["id"] if ok else STATE["active_id"]}
    finally:
        with STATE_LOCK:
            STATE["switching"] = False


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, obj: dict):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path.startswith("/status"):
            cfg = load_cfg()
            active = None
            with STATE_LOCK:
                active = STATE["active_id"]
            info = next((m for m in all_models(cfg) if m.get("id") == active), None)
            self._send(200, {
                "ok": True,
                "active_id": active,
                "active_name": info.get("name") if info else None,
                "switching": STATE["switching"],
                "infer_port": cfg.get("infer_port", 8080),
            })
        elif self.path.startswith("/models"):
            # 反馈 #11：给 App 用的模型清单（配置项 + 文件夹里自动扫到的）
            # App 端设置页直接用这份列表渲染，所以"丢个 gguf 进 models/gguf"
            # 就能立刻在手机上看到并切换。
            cfg = load_cfg()
            items = []
            for m in all_models(cfg):
                gguf = m.get("gguf", "")
                exists = bool(gguf) and os.path.exists(gguf)
                items.append({
                    "id": m.get("id"),
                    "name": m.get("name"),
                    "file": os.path.basename(gguf) if gguf else "",
                    "size": os.path.getsize(gguf) if exists else 0,
                    "auto": bool(m.get("auto")),
                    "exists": exists,
                })
            with STATE_LOCK:
                active = STATE["active_id"]
            self._send(200, {"ok": True, "active_id": active, "models": items})
        elif self.path.startswith("/disk"):
            # 建议 #6（只读）：本地 gguf 列表 + 文件大小 + 所在磁盘剩余空间
            cfg = load_cfg()
            files = []
            disk_total, disk_free = 0, 0
            for m in all_models(cfg):
                gguf = m.get("gguf", "")
                exists = bool(gguf) and os.path.exists(gguf)
                size = os.path.getsize(gguf) if exists else 0
                files.append({
                    "id": m.get("id"),
                    "name": m.get("name"),
                    "exists": exists,
                    "size": size,
                })
                if exists and disk_free == 0:
                    try:
                        du = shutil.disk_usage(gguf)
                        disk_total, disk_free = du.total, du.free
                    except Exception:
                        pass
            self._send(200, {
                "ok": True,
                "files": files,
                "disk_total": disk_total,
                "disk_free": disk_free,
            })
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path.startswith("/switch"):
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length) if length else b"{}"
            try:
                data = json.loads(raw.decode("utf-8")) if raw else {}
            except Exception:
                data = {}
            model_id = data.get("id", "")
            if not model_id:
                self._send(400, {"ok": False, "error": "缺少参数 id"})
                return
            log(f"收到切换请求: {model_id}")
            result = handle_switch(load_cfg(), model_id)
            self._send(200 if result.get("ok") else 500, result)
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def log_message(self, *args):
        pass  # 静音默认日志


def main():
    cfg = load_cfg()
    switch_port = int(cfg.get("switch_port", 8090))

    # 启动时优先沿用上次成功加载的模型（文件还在才用），否则退回配置里的第一个可用模型
    last = load_last_model()
    first = pick_model(cfg, last)
    if first:
        log(f"沿用上次使用的模型: {last}")
    else:
        if last:
            log(f"上次的模型 {last} 已不可用，回退到配置默认模型")
        first = next((m for m in all_models(cfg) if m.get("gguf") and os.path.exists(_resolve(m.get("gguf", "")))), None)

    if first:
        start_model(cfg, first)
    else:
        log("警告：配置中没有可用的 gguf 模型，等待 /switch 指令")

    srv = ThreadingHTTPServer(("0.0.0.0", switch_port), Handler)
    log(f"模型切换服务已启动: http://0.0.0.0:{switch_port}")
    log(f"推理端口: {cfg.get('infer_port', 8080)}（固定，切换模型时自动更换）")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到退出信号，停止所有服务...")
        stop_current()


if __name__ == "__main__":
    main()
