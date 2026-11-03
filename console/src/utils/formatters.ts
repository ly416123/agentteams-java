export function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间无效' : date.toLocaleString('zh-CN');
}

export function formatDurationSeconds(value?: number | null): string {
  if (value === null || value === undefined || !Number.isFinite(value) || value < 0) return '—';
  if (value === 0) return '0 秒';
  const units: Array<[number, string]> = [
    [86400, '天'],
    [3600, '小时'],
    [60, '分钟'],
    [1, '秒'],
  ];
  let remaining = Math.floor(value);
  const parts: string[] = [];
  for (const [seconds, label] of units) {
    const amount = Math.floor(remaining / seconds);
    if (amount > 0) {
      parts.push(`${amount} ${label}`);
      remaining %= seconds;
    }
    if (parts.length === 2) break;
  }
  return parts.join(' ') || '0 秒';
}

export function isExpired(value?: string | null, now = Date.now()): boolean {
  if (!value) return false;
  const timestamp = new Date(value).getTime();
  return Number.isFinite(timestamp) && timestamp <= now;
}
