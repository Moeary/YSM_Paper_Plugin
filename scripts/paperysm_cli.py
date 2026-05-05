from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = REPO_ROOT / "scripts"
STATE_FILE = REPO_ROOT / "test-server" / ".paperysm-cli-state.json"

WORKER_DIR = REPO_ROOT / "test-server" / "freesia-worker"
WORKER_MODEL_ROOT = WORKER_DIR / "config" / "yes_steve_model"
WORKER_CUSTOM_DIR = WORKER_MODEL_ROOT / "custom"
WORKER_CACHE_DIR = WORKER_MODEL_ROOT / "cache" / "server"

DIRECT_PAPER_DIR = REPO_ROOT / "test-server" / "direct-paper"
PAPER_PLUGIN_DIR = DIRECT_PAPER_DIR / "plugins" / "PaperYSM"
PAPER_FIXTURE_DIR = PAPER_PLUGIN_DIR / "captures" / "native-cache" / "freesia-from-velocity"
NATIVE_CACHE_ROOT = PAPER_PLUGIN_DIR / "captures" / "native-cache"

EXPORT_BATCH = SCRIPTS_DIR / "export-worker-cache-batch.bat"
EXPORT_CAPTURE_BATCH = SCRIPTS_DIR / "export-freesia-native-fixture.bat"
DIRECT_PAPER_START = SCRIPTS_DIR / "start-direct-paper.bat"
FREESIA_STACK_START = SCRIPTS_DIR / "start-freesiaii-stack.bat"
VELOCITY_LOG = REPO_ROOT / "test-server" / "velocity-proxy" / "logs" / "latest.log"
VELOCITY_C2S_DIR = REPO_ROOT / "test-server" / "velocity-proxy" / "plugins" / "ysm-sniffer-captures"


def is_windows() -> bool:
    return os.name == "nt"


def load_state() -> dict[str, object]:
    if not STATE_FILE.exists():
        return {}
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def save_state(state: dict[str, object]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def ask(prompt: str, default: str = "") -> str:
    suffix = f" [{default}]" if default else ""
    value = input(f"{prompt}{suffix}: ").strip()
    return value or default


def ask_yes(prompt: str, default: bool = False) -> bool:
    marker = "Y/n" if default else "y/N"
    value = input(f"{prompt} [{marker}]: ").strip().lower()
    if not value:
        return default
    return value in {"y", "yes", "1", "true", "on", "是", "好"}


def run(args: list[str], cwd: Path = REPO_ROOT, check: bool = True) -> subprocess.CompletedProcess[str]:
    print()
    print(">>> " + " ".join(args), flush=True)
    result = subprocess.run(args, cwd=cwd, text=True)
    if check and result.returncode != 0:
        raise SystemExit(result.returncode)
    return result


def robocopy_or_copy(source: Path, dest: Path) -> None:
    if not source.exists():
        raise FileNotFoundError(f"source not found: {source}")
    dest.mkdir(parents=True, exist_ok=True)
    if is_windows() and shutil.which("robocopy"):
        args = [
            "robocopy",
            str(source),
            str(dest),
            "/E",
            "/MT:8",
            "/R:1",
            "/W:1",
            "/NFL",
            "/NDL",
            "/NP",
        ]
        result = run(args, check=False)
        if result.returncode >= 8:
            raise RuntimeError(f"robocopy failed with exit code {result.returncode}")
        print(f"robocopy completed with exit code {result.returncode}.")
        return
    shutil.copytree(source, dest, dirs_exist_ok=True)
    print(f"copied: {source} -> {dest}")


def copy_models(args: argparse.Namespace) -> None:
    source = args.source.resolve()
    group = args.group
    dest = (args.dest or (WORKER_CUSTOM_DIR / group)).resolve()
    if dest.exists() and (dest.is_symlink() or _is_reparse_point(dest)):
        raise RuntimeError(f"destination is a symlink/junction; remove it first: {dest}")
    print(f"model source: {source}")
    print(f"worker target: {dest}")
    if not args.yes and dest.exists() and any(dest.iterdir()):
        if not ask_yes("目标目录已有内容，是否合并复制", default=True):
            print("copy cancelled.")
            return
    robocopy_or_copy(source, dest)


def batch_args(action: str, extra: list[str]) -> list[str]:
    if is_windows():
        return ["cmd", "/c", str(EXPORT_BATCH), action, *extra]
    return [str(EXPORT_BATCH), action, *extra]


def snapshot_worker(args: argparse.Namespace) -> None:
    run(batch_args("snapshot", ["--snapshot-name", args.snapshot_name]))


def export_worker_cache(args: argparse.Namespace) -> None:
    extra = ["--group", args.group, "--snapshot-name", args.snapshot_name]
    if args.unsafe_order_pair:
        extra.append("--unsafe-order-pair")
    if args.force_duplicate_models:
        extra.append("--force-duplicate-models")
    if args.dry_run:
        extra.append("--dry-run")
    run(batch_args("export", extra))


def export_velocity_capture(args: argparse.Namespace) -> None:
    capture = args.capture.resolve()
    c2s = args.c2s.resolve() if args.c2s else VELOCITY_C2S_DIR.resolve()
    out = args.out.resolve() if args.out else (NATIVE_CACHE_ROOT / args.name).resolve()
    command = [str(EXPORT_CAPTURE_BATCH), str(capture), str(out), str(c2s)]
    if is_windows():
        command = ["cmd", "/c", *command]
    run(command)
    print()
    print(f"Paper source: {out.name}")
    print(f"Paper commands: /ysm source default {out.name}")
    print("Paper commands: /ysm sync")


def inspect_type3(args: argparse.Namespace) -> None:
    extra = []
    if getattr(args, "fixture", ""):
        extra.extend(["--fixture-dir", str((NATIVE_CACHE_ROOT / args.fixture).resolve())])
    run(batch_args("type3-inspect", extra))


def start_process(name: str, cwd: Path, bat: str, visible: bool) -> None:
    script = cwd / bat
    if not script.exists():
        raise FileNotFoundError(f"server script not found: {script}")

    creationflags = 0
    stdout = None
    stderr = None
    if is_windows():
        if visible:
            creationflags = getattr(subprocess, "CREATE_NEW_CONSOLE", 0)
        else:
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            stdout = subprocess.DEVNULL
            stderr = subprocess.DEVNULL
        command = ["cmd", "/c", str(script)]
    else:
        command = ["sh", str(script)]

    process = subprocess.Popen(command, cwd=cwd, creationflags=creationflags, stdout=stdout, stderr=stderr)
    state = load_state()
    state[name] = {"pid": process.pid, "cwd": str(cwd), "script": bat}
    save_state(state)
    print(f"{name} started: pid={process.pid}, cwd={cwd}")


def start_worker(args: argparse.Namespace) -> None:
    start_process("worker", WORKER_DIR, "start.bat", args.visible)


def start_paper(args: argparse.Namespace) -> None:
    if args.direct:
        if is_windows():
            run(["cmd", "/c", str(DIRECT_PAPER_START)])
        else:
            run([str(DIRECT_PAPER_START)])
        return
    start_process("paper", DIRECT_PAPER_DIR, "StartServer.bat", args.visible)


def start_freesia_stack(args: argparse.Namespace) -> None:
    if is_windows():
        run(["cmd", "/c", str(FREESIA_STACK_START)])
    else:
        run([str(FREESIA_STACK_START)])


def stop_process(args: argparse.Namespace) -> None:
    state = load_state()
    entry = state.get(args.name)
    if not isinstance(entry, dict) or not entry.get("pid"):
        print(f"no saved pid for {args.name}.")
        return
    pid = str(entry["pid"])
    if is_windows():
        taskkill = ["taskkill", "/PID", pid, "/T"]
        if args.force:
            taskkill.append("/F")
        run(taskkill, check=False)
    else:
        run(["kill", pid], check=False)
    state.pop(args.name, None)
    save_state(state)


def print_status(_args: argparse.Namespace) -> None:
    print(f"repo: {REPO_ROOT}")
    print(f"worker custom: {WORKER_CUSTOM_DIR}")
    print(f"worker cache: {WORKER_CACHE_DIR}")
    print(f"paper fixture: {PAPER_FIXTURE_DIR}")
    print(f"paper model ref config should point to: ../../../freesia-worker/config/yes_steve_model/custom")
    for label, path in (
        ("direct-paper models", PAPER_PLUGIN_DIR / "models"),
        ("worker 游戏IP分类", WORKER_CUSTOM_DIR / "游戏IP分类"),
    ):
        kind = "missing"
        if path.exists():
            kind = "junction/symlink" if path.is_symlink() or _is_reparse_point(path) else "directory"
        print(f"{label}: {kind} -> {path}")
    if WORKER_CACHE_DIR.exists():
        cache_files = [p for p in WORKER_CACHE_DIR.iterdir() if p.is_file()]
        total = sum(p.stat().st_size for p in cache_files)
        print(f"worker cache files: {len(cache_files)} ({total / 1024 / 1024:.1f} MiB)")
    state = load_state()
    if state:
        print(f"saved processes: {state}")


def _is_reparse_point(path: Path) -> bool:
    if not is_windows() or not path.exists():
        return False
    try:
        import ctypes

        attrs = ctypes.windll.kernel32.GetFileAttributesW(str(path))
        return attrs != -1 and bool(attrs & 0x400)
    except Exception:
        return False


def guided_ingest(args: argparse.Namespace) -> None:
    print("PaperYSM 入库向导")
    print("推荐顺序：copy models -> start Velocity/Freesia stack -> client sync capture -> export real type3 -> Paper test")
    group = args.group or ask("本次模型分组名", "游戏IP分类")
    source_text = args.source or ask("外部模型目录，留空表示已经放进 worker custom", "")

    if source_text:
        copy_models(argparse.Namespace(
            source=Path(source_text),
            group=group,
            dest=None,
            yes=args.yes,
        ))

    if ask_yes("现在启动完整 Velocity/Freesia 对照栈", default=True):
        start_freesia_stack(argparse.Namespace())
        print("进 Velocity 服触发一次真实 YSM 同步，确认 Freesia 端能看到模型后回来按回车。")
        input("捕获完成后按回车继续...")

    if ask_yes("从 Velocity/Freesia 捕获导出真实 type3 到 Paper fixture", default=True):
        export_velocity_capture(argparse.Namespace(
            capture=VELOCITY_LOG,
            c2s=VELOCITY_C2S_DIR,
            out=None,
            name=args.name,
        ))
        inspect_type3(argparse.Namespace(fixture=args.name))

    print()
    print("下一步：启动 Paper 测试服，进服后用 /ysm sync 或等待 handshake 自动同步。")


def interactive_menu() -> None:
    while True:
        print()
        print("PaperYSM 全局工具")
        print("1. 查看路径/状态")
        print("2. 复制模型目录到 worker custom")
        print("3. 启动完整 Velocity/Freesia 对照栈")
        print("4. 一键入库向导")
        print("5. 从 Velocity/Freesia 捕获导出真实 type3")
        print("6. 检查 Paper type3 fixture")
        print("7. 启动 Freesia worker（实验）")
        print("8. 停止 Freesia worker（实验）")
        print("9. 启动 direct Paper")
        print("10. 危险：按 worker cache 顺序导出（实验）")
        print("0. 退出")
        choice = ask("选择", "1")
        if choice == "0":
            return
        if choice == "1":
            print_status(argparse.Namespace())
        elif choice == "2":
            source = Path(ask("外部模型目录"))
            group = ask("worker 分组名", source.name)
            copy_models(argparse.Namespace(source=source, group=group, dest=None, yes=False))
        elif choice == "3":
            start_freesia_stack(argparse.Namespace())
        elif choice == "4":
            guided_ingest(argparse.Namespace(group="", source="", visible=True, yes=False, name="freesia-from-velocity"))
        elif choice == "5":
            export_velocity_capture(argparse.Namespace(
                capture=Path(ask("Velocity latest.log", str(VELOCITY_LOG))),
                c2s=Path(ask("C2S sniffer 目录", str(VELOCITY_C2S_DIR))),
                out=None,
                name=ask("Paper fixture 名称", "freesia-from-velocity"),
            ))
        elif choice == "6":
            inspect_type3(argparse.Namespace(fixture=ask("fixture 名称", "freesia-from-velocity")))
        elif choice == "7":
            start_worker(argparse.Namespace(visible=True))
        elif choice == "8":
            stop_process(argparse.Namespace(name="worker", force=True))
        elif choice == "9":
            start_paper(argparse.Namespace(visible=True, direct=False))
        elif choice == "10":
            export_worker_cache(argparse.Namespace(
                group=ask("模型分组名", "游戏IP分类"),
                snapshot_name=ask("baseline snapshot 名称", "default-clean"),
                dry_run=False,
                force_duplicate_models=False,
                unsafe_order_pair=True,
            ))
        else:
            print("未知选项。")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="PaperYSM local workflow CLI")
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("status").set_defaults(func=print_status)

    copy = sub.add_parser("copy-models")
    copy.add_argument("source", type=Path)
    copy.add_argument("--group", required=True)
    copy.add_argument("--dest", type=Path)
    copy.add_argument("-y", "--yes", action="store_true")
    copy.set_defaults(func=copy_models)

    snapshot = sub.add_parser("snapshot")
    snapshot.add_argument("--snapshot-name", default="default-clean")
    snapshot.set_defaults(func=snapshot_worker)

    export = sub.add_parser("export")
    export.add_argument("--group", default="游戏IP分类")
    export.add_argument("--snapshot-name", default="default-clean")
    export.add_argument("--dry-run", action="store_true")
    export.add_argument("--force-duplicate-models", action="store_true")
    export.add_argument("--unsafe-order-pair", action="store_true")
    export.set_defaults(func=export_worker_cache)

    capture = sub.add_parser("export-capture")
    capture.add_argument("--capture", type=Path, default=VELOCITY_LOG)
    capture.add_argument("--c2s", type=Path, default=VELOCITY_C2S_DIR)
    capture.add_argument("--name", default="freesia-from-velocity")
    capture.add_argument("--out", type=Path)
    capture.set_defaults(func=export_velocity_capture)

    ingest = sub.add_parser("ingest")
    ingest.add_argument("--group", default="")
    ingest.add_argument("--source", default="")
    ingest.add_argument("--name", default="freesia-from-velocity")
    ingest.add_argument("--visible", action="store_true")
    ingest.add_argument("-y", "--yes", action="store_true")
    ingest.set_defaults(func=guided_ingest)

    type3 = sub.add_parser("type3-inspect")
    type3.add_argument("--fixture", default="")
    type3.set_defaults(func=inspect_type3)

    worker = sub.add_parser("start-worker")
    worker.add_argument("--visible", action="store_true")
    worker.set_defaults(func=start_worker)

    paper = sub.add_parser("start-paper")
    paper.add_argument("--visible", action="store_true")
    paper.add_argument("--direct", action="store_true", help="call scripts/start-direct-paper.bat")
    paper.set_defaults(func=start_paper)

    sub.add_parser("start-stack").set_defaults(func=start_freesia_stack)

    stop = sub.add_parser("stop")
    stop.add_argument("name", choices=["worker", "paper"])
    stop.add_argument("--force", action="store_true")
    stop.set_defaults(func=stop_process)

    return parser


def main(argv: list[str]) -> int:
    parser = build_parser()
    if not argv:
        interactive_menu()
        return 0
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.print_help()
        return 1
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
