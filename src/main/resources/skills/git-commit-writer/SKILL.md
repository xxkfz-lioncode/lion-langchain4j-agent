---
name: git-commit-writer
description: 读取 Git 变动记录（暂存区 / 未暂存 / 未跟踪文件 / 最近提交风格）并归纳改动，生成符合仓库既有风格与 Conventional Commits 的 commit message，多主题时可给出拆分提交建议。当用户提出「生成 commit 信息 / 提交信息 / 提交说明」「总结一下改动准备提交」「看看改了什么，写个 commit」「按主题拆成几个提交」等诉求时使用。
---

# Git Commit Writer

## 用途

把工作区的 Git 变动自动归纳成可直接复制的 commit message：先确定提交范围，再按类型（feat / fix / refactor / docs 等）归类改动，最后输出与仓库历史风格一致的标题 + 正文，必要时给出拆分多个提交的建议。

默认**只生成文本，不执行提交**。用户明确要求提交时才执行 `git add` / `git commit`。

## 使用流程

### 1. 收集变动记录

运行 `scripts/collect_changes.py`（路径相对本 skill 目录，执行时用其绝对路径），脚本只读不写：

```bash
python "<skill_dir>/scripts/collect_changes.py" --repo "<repo_root>"
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--repo <path>` | 仓库路径，默认当前目录 |
| `--staged-only` | 只看暂存区（判断「即将提交什么」） |
| `--max-file-chars <n>` | 单文件 diff 字符上限，默认 3000 |
| `--max-total-chars <n>` | diff 明细总字符上限，默认 50000；diff 很大时调低以省 context |
| `--log-count <n>` | 参考最近 n 条提交标题，默认 10 |
| `--body-count <n>` | 参考最近 n 条提交全文，默认 3 |
| `--untracked-lines <n>` | 未跟踪文本文件的预览行数，默认 0（大文件谨慎开启） |

脚本输出包含：仓库/分支/HEAD、文件数与 diffstat、暂存区清单、未暂存与未跟踪清单、逐文件 diff（二进制与超长自动省略/截断）、最近提交风格样例。

若 diff 明细过大导致关键信息被截断，针对重点文件追加精确 diff：`git -C <repo> --no-pager diff [--cached] -U2 -- <path>`。

### 2. 确定提交范围

- 暂存区非空 → 以暂存区内容为提交范围（用户未表态时先确认）。
- 暂存区为空但工作区有改动 → 提示「尚未 git add」，并给出建议的 `git add <path>` 分组。
- 工作区无任何改动 → 直接告知无需生成，不编造内容。

### 3. 归纳与生成

阅读 `references/commit-convention.md`，按其规则生成 message：

1. 先判断是**单主题**还是**多主题**：单主题用一行标题 + 要点列表；多主题用 `1) 2) 3)` 编号子项。
2. 每个主题标注 type（`feat` / `fix` / `refactor` / `docs` …），以实际代码行为为准，不夸大（纯配置调整不要写成 `feat`）。
3. 正文写「为什么改 + 关键实现点 + 影响面」，不复述 diff 逐行细节。
4. 输出放在代码块中便于复制；可参考 `assets/commit_message_template.txt` 的三种模板（单主题 / 多主题 / 拆分建议）。
5. 若历史提交明显是中文 + `feat:` 或 `1) 2)` 结构，保持完全一致；历史风格与规范冲突时以历史为准。

### 4. 拆分建议（可选）

当各主题彼此独立时，给出多条 message，并附建议的文件分组命令，例如：

```
【提交 1】git add src/main/java/... src/main/resources/...
refactor: 知识库向量配置解耦为独立配置项

【提交 2】git add frontend/src/views/...
feat: 上传页新增切分方式选择
```

### 5. 提交（仅在用户明确要求时）

- 使用多条 `-m` 传正文，或在用户确认后写入临时文件再 `git commit -F`。
- 不使用 `--no-verify`、`--amend`、`--force` 等需要显式授权的参数。
- 提交前再次 `git status` 复核，避免把 `.env`、密钥、临时文件、构建产物带入。

## 注意事项

- 脚本不会执行任何写操作；不要用等价的手写命令替代它来「顺手」修改仓库状态。
- Windows / PowerShell 下执行提交时，避免直接 `git commit -m "中文信息"`（参数可能按本地代码页写入，导致历史乱码）；改为把 message 写入 UTF-8 临时文件后 `git commit -F <file>`，完成后再删除该文件。
- 脚本已固定 `i18n.logOutputEncoding=UTF-8` 读取提交历史；若仍出现乱码，先确认是控制台编码问题，而不是仓库数据问题。
- 不要修改 git config（包括 `git config user.*`）；提交时的身份配置交由用户自行管理。

## 资源

- `scripts/collect_changes.py`：变动收集脚本（只读，输出 Markdown 摘要）。
- `references/commit-convention.md`：type 取值、多主题结构、拆分原则、反例、与历史风格对齐规则。
- `assets/commit_message_template.txt`：单主题 / 多主题 / 拆分建议三种输出模板。
