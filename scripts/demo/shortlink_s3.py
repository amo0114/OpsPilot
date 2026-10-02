#!/usr/bin/env python3
"""Demo 验证辅助（08 TASK-093～095）：为 deploy/demo 的 ShortLink 准备演示短链，按固定速率持续访问（09 §32、§62），
或持续创建短链（09 §49，S2）。

只用于本机 Demo 验证，不属于 OpsPilot 运行时；正式负载发生器与 Demo Seed 由 TASK-105/106 提供。
只依赖 Python 3 标准库与 docker compose（准备演示分组时写入 ShortLink 库）。

  SHORTLINK_REPO=/path/to/shortlink python3 scripts/demo/shortlink_s3.py prepare
  python3 scripts/demo/shortlink_s3.py load --rate 10 --duration 600
  python3 scripts/demo/shortlink_s3.py create-load --rate 5 --duration 600
"""

import argparse
import concurrent.futures
import http.client
import json
import math
import os
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy" / "demo" / "docker-compose.yml"

USERNAME = "opspilot-demo"
GID = "opspilotdemo"
# t_group 按 username 做 HASH_MOD(16) 分表：abs(Java "opspilot-demo".hashCode()) % 16 == 6
GROUP_TABLE = "t_group_6"
NOT_FOUND_PATH = "/page/notfound"
# ShortLink 跳转按 User-Agent 解析系统与浏览器，缺少该头时返回错误（范围外问题，记录于 OpsPilot PROGRESS）
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


def java_hash(text):
    value = 0
    for char in text:
        value = (31 * value + ord(char)) & 0xFFFFFFFF
    return value - (1 << 32) if value >= 1 << 31 else value


def request(base, method, path, body=None, timeout=5.0):
    url = urllib.parse.urlsplit(base)
    connection = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=timeout)
    headers = {"username": USERNAME, "User-Agent": USER_AGENT}
    payload = None
    if body is not None:
        payload = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    try:
        connection.request(method, path, body=payload, headers=headers)
        response = connection.getresponse()
        return response.status, response.getheader("Location"), response.read()
    finally:
        connection.close()


def api(base, method, path, body=None):
    status, _, raw = request(base, method, path, body)
    data = json.loads(raw) if raw else {}
    if status != 200 or data.get("code") != "0":
        raise SystemExit(f"{method} {path} failed: HTTP {status} {raw[:300]!r}")
    return data.get("data")


def existing_links(base):
    page = api(base, "GET", f"/api/short-link/v1/page?gid={GID}&current=1&size=200&orderTag=")
    return [record["fullShortUrl"] for record in page.get("records", [])]


def prepare(args):
    if abs(java_hash(USERNAME)) % 16 != int(GROUP_TABLE.rsplit("_", 1)[1]):
        raise SystemExit("group shard table does not match the username hash")
    sql = (
        f"INSERT INTO {GROUP_TABLE} (gid, name, username, sort_order, create_time, update_time, del_flag) "
        f"SELECT '{GID}', 'OpsPilot Demo', '{USERNAME}', 0, NOW(), NOW(), 0 FROM DUAL "
        f"WHERE NOT EXISTS (SELECT 1 FROM {GROUP_TABLE} WHERE gid = '{GID}' AND username = '{USERNAME}')"
    )
    subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), "exec", "-T", "mysql", "sh", "-c",
         'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D link -e "$0"', sql],
        check=True, timeout=60,
    )
    links = existing_links(args.base)
    for index in range(len(links), args.count):
        # ShortLink 对创建接口有 Sentinel 限流（QPS 1）
        time.sleep(1.1)
        api(args.base, "POST", "/api/short-link/v1/create", {
            "originUrl": f"http://opspilot-demo.invalid/item/{index}",
            "gid": GID,
            "createdType": 1,
            "validDateType": 0,
            "describe": f"OpsPilot S3 demo {index}",
        })
    links = existing_links(args.base)
    print(f"{len(links)} demo short links in group {GID}")
    for link in links:
        print(link)


def load(args):
    paths = [urllib.parse.urlsplit("http://" + link.removeprefix("http://")).path for link in existing_links(args.base)]
    if not paths:
        raise SystemExit("no demo short links; run prepare first")
    lock = threading.Lock()
    window = {"sent": 0, "ok": 0, "errors": 0, "latencies": []}

    def visit(path):
        started = time.monotonic()
        try:
            status, location, _ = request(args.base, "GET", path, timeout=args.timeout)
            ok = 300 <= status < 400 and location is not None and NOT_FOUND_PATH not in location
        except OSError:
            ok = False
        elapsed = (time.monotonic() - started) * 1000
        with lock:
            window["ok" if ok else "errors"] += 1
            window["latencies"].append(elapsed)

    interval = 1.0 / args.rate
    deadline = None if args.duration <= 0 else time.monotonic() + args.duration
    next_at = time.monotonic()
    next_report = next_at + args.report
    index = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        while deadline is None or time.monotonic() < deadline:
            now = time.monotonic()
            if now < next_at:
                time.sleep(next_at - now)
            pool.submit(visit, paths[index % len(paths)])
            index += 1
            with lock:
                window["sent"] += 1
            next_at += interval
            if time.monotonic() >= next_report:
                with lock:
                    latencies = sorted(window["latencies"])
                    # 最近秩法：第 ceil(0.99 n) 个
                    p99 = latencies[math.ceil(len(latencies) * 0.99) - 1] if latencies else float("nan")
                    print(
                        f"{time.strftime('%H:%M:%S')} sent={window['sent']} ok={window['ok']} "
                        f"errors={window['errors']} p99_ms={p99:.1f}",
                        flush=True,
                    )
                    window.update(sent=0, ok=0, errors=0, latencies=[])
                next_report += args.report


def create_load(args):
    """持续创建短链：每次唯一 URL（避免应用级去重），足够的客户端并发；成功指 HTTP 200 且业务码为 "0"。"""
    lock = threading.Lock()
    window = {"sent": 0, "ok": 0, "errors": 0, "latencies": []}
    counter = iter(range(10**12))

    def create():
        index = next(counter)
        started = time.monotonic()
        try:
            status, _, raw = request(args.base, "POST", "/api/short-link/v1/create", {
                "originUrl": f"http://opspilot-demo.invalid/create/{int(time.time() * 1000)}-{index}",
                "gid": GID,
                "createdType": 1,
                "validDateType": 0,
                "describe": "OpsPilot S2 demo",
            }, timeout=args.timeout)
            ok = status == 200 and json.loads(raw or b"{}").get("code") == "0"
        except (OSError, ValueError):
            ok = False
        elapsed = (time.monotonic() - started) * 1000
        with lock:
            window["ok" if ok else "errors"] += 1
            window["latencies"].append(elapsed)

    run_at_rate(args, create, lock, window)


def run_at_rate(args, action, lock, window):
    interval = 1.0 / args.rate
    deadline = None if args.duration <= 0 else time.monotonic() + args.duration
    next_at = time.monotonic()
    next_report = next_at + args.report
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        while deadline is None or time.monotonic() < deadline:
            now = time.monotonic()
            if now < next_at:
                time.sleep(next_at - now)
            pool.submit(action)
            with lock:
                window["sent"] += 1
            next_at += interval
            if time.monotonic() >= next_report:
                with lock:
                    latencies = sorted(window["latencies"])
                    p99 = latencies[math.ceil(len(latencies) * 0.99) - 1] if latencies else float("nan")
                    print(
                        f"{time.strftime('%H:%M:%S')} sent={window['sent']} ok={window['ok']} "
                        f"errors={window['errors']} p99_ms={p99:.1f}",
                        flush=True,
                    )
                    window.update(sent=0, ok=0, errors=0, latencies=[])
                next_report += args.report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=os.environ.get("SHORTLINK_BASE", "http://localhost:18001"),
                        help="project-api 地址；Host 须与 ShortLink 域名配置一致")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare", help="写入演示分组并补齐演示短链")
    prepare_parser.add_argument("--count", type=int, default=20)
    load_parser = commands.add_parser("load", help="按固定速率访问演示短链")
    load_parser.add_argument("--rate", type=float, default=10.0, help="每秒请求数（09 §62 默认 10）")
    load_parser.add_argument("--duration", type=float, default=0, help="秒；0 表示一直运行")
    load_parser.add_argument("--timeout", type=float, default=5.0)
    load_parser.add_argument("--report", type=float, default=10.0, help="汇总间隔秒数")
    create_parser = commands.add_parser("create-load", help="按固定速率创建短链（S2）")
    create_parser.add_argument("--rate", type=float, default=5.0, help="每秒创建数（09 §49 默认 5）")
    create_parser.add_argument("--duration", type=float, default=0, help="秒；0 表示一直运行")
    create_parser.add_argument("--timeout", type=float, default=30.0)
    create_parser.add_argument("--concurrency", type=int, default=64, help="客户端并发上限")
    create_parser.add_argument("--report", type=float, default=10.0, help="汇总间隔秒数")
    args = parser.parse_args()
    {"prepare": prepare, "load": load, "create-load": create_load}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
