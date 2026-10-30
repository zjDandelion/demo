#!/usr/bin/env bash
# 提交前本地预检，尽量复现 CI 会跑的检查。
#
#   bash scripts/preflight.sh
#
# 依次执行：compose 配置校验 -> Java 测试 -> Python 测试 -> shell 语法检查。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

log() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
ok()  { printf '\033[32m    %s\033[0m\n' "$1"; }
die() { printf '\033[31m!! %s\033[0m\n' "$1" >&2; exit 1; }

FAILED=0

log "1/4 compose 配置校验"
docker compose config -q && ok "docker-compose.yml" || FAILED=1
docker compose -f docker-compose.yml -f docker-compose.ops.yml config -q \
    && ok "docker-compose.ops.yml" || FAILED=1

log "2/4 Java 测试（Maven Wrapper）"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    ./mvnw -B -q clean verify && ok "Java 测试通过" || FAILED=1
elif command -v javac >/dev/null 2>&1; then
    ./mvnw -B -q clean verify && ok "Java 测试通过" || FAILED=1
else
    printf '\033[33mWSL 内没有 JDK，跳过 Java 测试。\033[0m\n'
    printf '\033[33m  在 Windows 侧跑：$env:JAVA_HOME="D:\\Java\\jdk-1.8"; .\\mvnw.cmd clean verify\033[0m\n'
    printf '\033[33m  或在 WSL 装：sudo apt install -y openjdk-8-jdk（Ubuntu 24.04 需改用 openjdk-11 + 降级编译）\033[0m\n'
fi

log "3/4 Python 测试"
if command -v python3 >/dev/null 2>&1 && python3 -c 'import pytest' 2>/dev/null; then
    (cd na-assistant-service && python3 -m pytest -q) && ok "Python 测试通过" || FAILED=1
else
    printf '\033[33m本机无 pytest，跳过。CI 会跑。\033[0m\n'
    printf '\033[33m安装：python3 -m pip install -r na-assistant-service/requirements-dev.txt\033[0m\n'
fi

log "4/4 shell 脚本语法"
for f in scripts/*.sh; do
    bash -n "$f" || FAILED=1
done
ok "shell 语法检查完成"

echo
if [ "$FAILED" -ne 0 ]; then
    printf '\033[31m预检失败，请修复后再提交\033[0m\n' >&2
    exit 1
fi
printf '\033[32m预检全部通过\033[0m\n'
