# 每日自动备份（WSL 内 crontab）
#
# 安装：bash scripts/install-cron.sh
# 查看：crontab -l
# 卸载：bash scripts/install-cron.sh --remove

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
MARKER="# na-order-daily-backup"

if [ "${1:-}" = "--remove" ]; then
    crontab -l 2>/dev/null | grep -v "$MARKER" | grep -v 'scripts/backup.sh' | crontab -
    echo "已移除每日备份任务"
    exit 0
fi

mkdir -p "$HOME/.local/state/na-backups"

JOB="0 3 * * * cd $PROJECT_DIR && /bin/bash scripts/backup.sh >> $HOME/.local/state/na-backups/backup.log 2>&1 $MARKER"

# 先删旧任务再装，保证幂等
crontab -l 2>/dev/null | grep -v "$MARKER" | grep -v 'scripts/backup.sh' > /tmp/na-cron.tmp || true
printf '%s\n' "$JOB" >> /tmp/na-cron.tmp
crontab /tmp/na-cron.tmp
rm -f /tmp/na-cron.tmp

echo "已安装每日备份任务（每天 03:00）"
echo "日志：$HOME/.local/state/na-backups/backup.log"
crontab -l | grep "$MARKER"
