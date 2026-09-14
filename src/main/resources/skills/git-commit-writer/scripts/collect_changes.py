#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""收集 Git 变动记录，输出紧凑的 Markdown 摘要，供生成 commit message 使用。

只读取信息，不修改仓库（不 add / 不 commit / 不改配置）。

用法示例：
    python collect_changes.py
    python collect_changes.py --repo D:/code/my-app --log-count 15
    python collect_changes.py --staged-only --max-file-chars 1500
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys

BINARY_EXT = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bmp", ".pdf", ".zip",
    ".tar", ".gz", ".jar", ".war", ".class", ".exe", ".dll", ".so", ".dylib",
    ".woff", ".woff2", ".ttf", ".eot", ".mp3", ".mp4", ".mov", ".avi",
    ".xlsx", ".xls", ".docx", ".pptx", ".bin", ".lock",
}


def configure_stdout() -> None:
    """Windows 控制台默认 GBK，强制 UTF-8 输出，避免中文/日文 diff 报编码错误。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


def git(repo: str, args, check: bool = False) -> str:
    """执行 git 命令并返回 stdout（失败时返回空串，除非 check=True）。"""
    cmd = ["git", "-C", repo, "-c", "core.quotepath=false",
           "-c", "i18n.logOutputEncoding=UTF-8"] + list(args)
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
    except FileNotFoundError:
        print("未找到 git 命令，请先安装 Git 并确保其在 PATH 中。", file=sys.stderr)
        sys.exit(2)
    if proc.returncode != 0:
        if check:
            print("git 执行失败：%s\n%s" % (" ".join(cmd), (proc.stderr or "").strip()),
                  file=sys.stderr)
            sys.exit(2)
        return ""
    return proc.stdout


def human_size(num: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if num < 1024 or unit == "GB":
            return "%dB" % int(num) if unit == "B" else "%.1f%s" % (num, unit)
        num /= 1024.0
    return "%dB" % int(num)


def parse_status(porcelain: str):
    """解析 git status --porcelain=v1，按 X/Y 拆出暂存与未暂存。"""
    entries = []
    for line in porcelain.splitlines():
        if len(line) < 3:
            continue
        xy, rest = line[:2], line[3:].strip()
        old_path = None
        if " -> " in rest:
            old_path, rest = rest.split(" -> ", 1)
        entries.append({
            "xy": xy,
            "path": rest.strip(),
            "old_path": (old_path or "").strip() or None,
            "staged": xy[0] not in (" ", "?"),
            "unstaged": xy[1] not in (" ", "?"),
            "untracked": xy == "??",
        })
    return entries


def is_binary_path(path: str) -> bool:
    return os.path.splitext(path)[1].lower() in BINARY_EXT


def file_info(repo: str, rel_path: str):
    """未跟踪/新增文件的体量信息（大小、文本行数），用于判断改动规模。"""
    full = os.path.join(repo, rel_path.replace("/", os.sep))
    if not os.path.isfile(full):
        return None
    binary = is_binary_path(rel_path)
    lines = None
    if not binary:
        try:
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                lines = sum(1 for _ in fh)
        except OSError:
            lines = None
    return {"size": os.path.getsize(full), "lines": lines, "binary": binary}


def collect_diff(repo: str, path: str, staged: bool, budget: int):
    """返回 (diff 文本或 None, 说明)。二进制与超大 diff 会被截断/省略。"""
    scope = ["--cached"] if staged else []
    numstat = git(repo, ["diff", "--numstat"] + scope + ["--", path]).strip()
    if numstat.startswith("-\t"):
        return None, "二进制文件，diff 已省略（改动规模见 diffstat）"
    text = git(repo, ["diff", "--no-color", "--no-ext-diff", "-U2"] + scope + ["--", path])
    if not text.strip():
        return None, "无文本差异（可能仅为重命名 / 权限 / 换行符变更）"
    if len(text) > budget:
        text = text[:max(budget, 0)] + "\n…（该文件 diff 已截断）"
    return text, None


def main() -> None:
    parser = argparse.ArgumentParser(
        description="收集 Git 变动记录，输出供生成 commit message 的摘要")
    parser.add_argument("--repo", default=".", help="仓库路径，默认当前目录")
    parser.add_argument("--staged-only", action="store_true",
                        help="只看暂存区（模拟「即将提交什么」）")
    parser.add_argument("--max-file-chars", type=int, default=3000,
                        help="单文件 diff 字符上限，默认 3000")
    parser.add_argument("--max-total-chars", type=int, default=50000,
                        help="diff 明细总字符上限，默认 50000")
    parser.add_argument("--log-count", type=int, default=10,
                        help="参考最近 N 条提交标题，默认 10")
    parser.add_argument("--body-count", type=int, default=3,
                        help="参考最近 N 条提交全文，默认 3")
    parser.add_argument("--untracked-lines", type=int, default=0,
                        help="未跟踪文本文件预览行数，0 表示不预览内容")
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    if git(repo, ["rev-parse", "--is-inside-work-tree"]).strip() != "true":
        print("错误：%s 不是 Git 仓库（或尚未 git init）" % repo, file=sys.stderr)
        sys.exit(2)

    branch = git(repo, ["rev-parse", "--abbrev-ref", "HEAD"]).strip() or "(尚无提交)"
    head = git(repo, ["log", "-1", "--no-color", "--pretty=format:%h %s"]).strip() or "(尚无提交)"
    entries = parse_status(git(repo, ["status", "--porcelain=v1", "-uall"]))

    staged = [e for e in entries if e["staged"] and not e["untracked"]]
    unstaged = [e for e in entries if e["unstaged"] and not e["untracked"]]
    untracked = [e for e in entries if e["untracked"]]

    staged_stat = git(repo, ["diff", "--cached", "--shortstat"]).strip()
    unstaged_stat = git(repo, ["diff", "--shortstat"]).strip()

    out = []
    out.append("# Git 变动记录")
    out.append("")
    out.append("- 仓库：%s" % repo)
    out.append("- 分支：%s" % branch)
    out.append("- HEAD：%s" % head)
    out.append("- 文件数：暂存 %d / 未暂存 %d / 未跟踪 %d"
               % (len(staged), len(unstaged), len(untracked)))
    if staged_stat:
        out.append("- 已暂存 diffstat：%s" % staged_stat)
    if unstaged_stat and not args.staged_only:
        out.append("- 未暂存 diffstat：%s" % unstaged_stat)
    if args.staged_only:
        out.append("- 模式：--staged-only（未暂存与未跟踪内容不作为提交依据）")

    out.append("")
    out.append("## 一、暂存区改动（将进入本次提交）")
    if staged:
        for e in staged:
            extra = "  ← 重命名自 %s" % e["old_path"] if e["old_path"] else ""
            info = file_info(repo, e["path"])
            size = "  (%s)" % human_size(info["size"]) if info else ""
            out.append("- `%s`  %s%s%s" % (e["xy"], e["path"], size, extra))
    else:
        out.append("（无，注意提示用户先 git add）")

    if not args.staged_only:
        out.append("")
        out.append("## 二、未暂存改动（不属于本次提交，除非先 add）")
        if unstaged:
            for e in unstaged:
                out.append("- `%s`  %s" % (e["xy"], e["path"]))
        else:
            out.append("（无）")

        out.append("")
        out.append("## 三、未跟踪文件（新增，需 add 才会提交）")
        if untracked:
            for e in untracked:
                info = file_info(repo, e["path"])
                detail = ""
                if info:
                    detail = "（%s%s）" % (
                        human_size(info["size"]),
                        "，%d 行" % info["lines"] if info["lines"] is not None else "")
                out.append("- `%s`%s" % (e["path"], detail))
                if args.untracked_lines > 0 and info and not info["binary"]:
                    full = os.path.join(repo, e["path"].replace("/", os.sep))
                    try:
                        with open(full, "r", encoding="utf-8", errors="replace") as fh:
                            preview = [next(fh, "") for _ in range(args.untracked_lines)]
                        out.append("  ```")
                        out.extend("  " + ln.rstrip("\n") for ln in preview)
                        out.append("  ```")
                    except OSError:
                        pass
        else:
            out.append("（无）")

    out.append("")
    out.append("## 四、diff 明细")
    plan = [(True, e) for e in staged]
    if not args.staged_only:
        plan += [(False, e) for e in unstaged]
    total = 0
    skipped = []
    for staged_flag, entry in plan:
        label = "staged" if staged_flag else "unstaged"
        if total >= args.max_total_chars:
            skipped.append(entry["path"])
            continue
        text, note = collect_diff(repo, entry["path"], staged_flag,
                                 min(args.max_file_chars, args.max_total_chars - total))
        out.append("")
        out.append("### [%s] %s" % (label, entry["path"]))
        if text:
            total += len(text)
            out.append("```diff")
            out.append(text.rstrip())
            out.append("```")
        else:
            out.append("（%s）" % note)
    if skipped:
        out.append("")
        out.append("> 超出总字符上限，未展开的文件：%s" % "、".join(skipped))

    out.append("")
    out.append("## 五、最近提交风格（新提交请保持一致）")
    log = git(repo, ["log", "-n", str(max(args.log_count, 1)), "--no-color",
                     "--pretty=format:%h %s"]).strip()
    if log:
        out.append("")
        out.append("```")
        out.append(log)
        out.append("```")
        bodies = git(repo, ["log", "-n", str(max(args.body_count, 0)), "--no-color",
                            "--pretty=format:--- %h ---%n%B"]).strip()
        if bodies:
            out.append("")
            out.append("最近 %d 条提交全文（截断展示）：" % args.body_count)
            out.append("```")
            out.append(bodies[:3000])
            out.append("```")
    else:
        out.append("（仓库还没有提交记录，按 Conventional Commits 自行拟定）")

    if not entries:
        out.append("")
        out.append("> 当前工作区没有任何改动，无需生成 commit message。")

    print("\n".join(out))


if __name__ == "__main__":
    configure_stdout()
    main()
