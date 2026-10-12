"""真机 / 模拟器 → PC 推理服务的连通性自测。

用法：
    python test_http.py                      # 默认打 127.0.0.1（手机本地引擎）
    set ROLEPLAY_HOST=192.168.1.100 && python test_http.py   # 打 PC 的局域网 IP
"""
import os
import urllib.request

HOST = os.environ.get("ROLEPLAY_HOST", "127.0.0.1")

for port, path in ((8080, "/health"), (8090, "/status")):
    try:
        r = urllib.request.urlopen(f"http://{HOST}:{port}{path}", timeout=5)
        print(f"{port}:", r.read().decode())
    except Exception as e:
        print(f"{port} FAIL:", e)
