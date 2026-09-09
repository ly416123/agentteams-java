import { describe, expect, it } from 'vitest';
import {
  buildSubtaskContext,
  mergeTaskTimelines,
  processEventSummary,
  subtaskWindow,
} from './TaskInfoPanel';

const planned = (subtaskId: string, title: string, seq: number) => ({
  eventId: `e-planned-${subtaskId}`,
  eventType: 'subtask.planned',
  occurredAt: `2026-09-09T00:0${seq}:00Z`,
  payload: JSON.stringify({ subtaskId, title, sequence: seq }),
});
const started = (subtaskId: string, at: string) => ({
  eventId: `e-start-${subtaskId}-${at}`,
  eventType: 'subtask.started',
  occurredAt: at,
  payload: JSON.stringify({ subtaskId }),
});
const terminal = (type: string, subtaskId: string, at: string) => ({
  eventId: `e-${type}-${subtaskId}-${at}`,
  eventType: type,
  occurredAt: at,
  payload: JSON.stringify({ subtaskId }),
});
const tool = (name: string, at: string) => ({
  eventId: `e-tool-${name}-${at}`,
  eventType: 'tool.called',
  occurredAt: at,
  payload: JSON.stringify({ tool: name }),
});

describe('buildSubtaskContext', () => {
  it('从 planned 事件构建 subtaskId → title 映射', () => {
    const ctx = buildSubtaskContext([planned('a1', '抓取邮件', 1), planned('a2', '生成摘要', 2)]);
    expect(ctx.titles.get('a1')).toBe('抓取邮件');
    expect(ctx.titles.get('a2')).toBe('生成摘要');
  });

  it('损坏的 payload 被 best-effort 跳过', () => {
    const ctx = buildSubtaskContext([
      {
        eventId: 'e-bad',
        eventType: 'subtask.planned',
        occurredAt: '2026-09-09T00:00:00Z',
        payload: '{not-json',
      },
    ]);
    expect(ctx.titles.size).toBe(0);
  });
});

describe('subtaskWindow', () => {
  it('subtask.started 到下一终态之间的 tool.called 归该子任务', () => {
    const events = [
      started('a1', '2026-09-09T00:01:00Z'),
      tool('http.get', '2026-09-09T00:02:00Z'),
      terminal('subtask.succeeded', 'a1', '2026-09-09T00:03:00Z'),
      tool('orphan', '2026-09-09T00:04:00Z'),
    ];
    const attribution = subtaskWindow(events);
    expect(attribution.get('e-tool-http.get-2026-09-09T00:02:00Z')).toBe('a1');
    expect(attribution.get('e-tool-orphan-2026-09-09T00:04:00Z')).toBeUndefined();
  });

  it('无序输入按时间排序后仍正确归属', () => {
    const events = [tool('inside', '2026-09-09T00:02:00Z'), started('a2', '2026-09-09T00:01:00Z')];
    const attribution = subtaskWindow(events);
    expect(attribution.get('e-tool-inside-2026-09-09T00:02:00Z')).toBe('a2');
  });

  it('未闭合的 started 切换到新子任务后 tool.* 归新窗口', () => {
    const events = [
      started('a1', '2026-09-09T00:01:00Z'),
      tool('stale', '2026-09-09T00:02:00Z'),
      started('a2', '2026-09-09T00:03:00Z'),
      tool('fresh', '2026-09-09T00:04:00Z'),
    ];
    const attribution = subtaskWindow(events);
    expect(attribution.get('e-tool-stale-2026-09-09T00:02:00Z')).toBe('a1');
    expect(attribution.get('e-tool-fresh-2026-09-09T00:04:00Z')).toBe('a2');
  });

  it('failed 与 cancelled 终态同样闭合归属窗口', () => {
    const events = [
      started('a1', '2026-09-09T00:01:00Z'),
      terminal('subtask.failed', 'a1', '2026-09-09T00:02:00Z'),
      started('a2', '2026-09-09T00:03:00Z'),
      terminal('subtask.cancelled', 'a2', '2026-09-09T00:04:00Z'),
      tool('orphan', '2026-09-09T00:05:00Z'),
    ];
    const attribution = subtaskWindow(events);
    expect(attribution.get('e-tool-orphan-2026-09-09T00:05:00Z')).toBeUndefined();
  });
});

describe('mergeTaskTimelines', () => {
  it('subtask.failed 与 task.failed 标 danger，注入的 subtaskId 透传', () => {
    const items = mergeTaskTimelines(
      [],
      [
        {
          eventId: 'e1',
          eventType: 'subtask.failed',
          occurredAt: '2026-09-09T00:01:00Z',
          payload: JSON.stringify({ subtaskId: 'a1', note: '上游超时' }),
          subtaskId: 'a1',
        },
        {
          eventId: 'e2',
          eventType: 'tool.called',
          occurredAt: '2026-09-09T00:02:00Z',
          payload: JSON.stringify({ tool: 'http.get' }),
        },
      ],
    );
    const failed = items.find((item) => item.id === 'process:e1');
    const called = items.find((item) => item.id === 'process:e2');
    expect(failed?.tone).toBe('danger');
    expect(failed?.subtaskId).toBe('a1');
    expect(called?.tone).toBeUndefined();
    expect(called?.subtaskId).toBeUndefined();
  });
});

describe('processEventSummary', () => {
  it('subtask.planned 显示标题与序号', () => {
    const summary = processEventSummary({
      eventType: 'subtask.planned',
      payload: JSON.stringify({ subtaskId: 'a1', title: '抓取邮件', sequence: 1 }),
    });
    expect(summary).toContain('抓取邮件');
    expect(summary).toContain('#1');
  });

  it('subtask.failed 显示备注', () => {
    const summary = processEventSummary({
      eventType: 'subtask.failed',
      payload: JSON.stringify({ subtaskId: 'a1', note: '上游超时' }),
    });
    expect(summary).toContain('上游超时');
  });

  it('未登记子任务回落短 id', () => {
    const summary = processEventSummary({
      eventType: 'subtask.started',
      payload: JSON.stringify({ subtaskId: 'abcdef12-3456-7890-abcd-ef1234567890' }),
    });
    expect(summary).toBe('abcdef12');
  });
});
