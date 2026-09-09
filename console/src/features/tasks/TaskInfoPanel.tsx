import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTaskDecisions, useTaskResult, useTaskTree } from '../../queries/useTaskQueries';
import type { Task, TaskProcessEvent, TaskTreeNode } from '../../api/types';
import { TaskDag } from './TaskDag';

type TabName = 'events' | 'artifacts' | 'decisions' | 'details';
const TABS: Array<{ name: TabName; label: string }> = [
  { name: 'events', label: '事件' },
  { name: 'artifacts', label: '成果物' },
  { name: 'decisions', label: '决策' },
  { name: 'details', label: '详情' },
];

/** 过程事件 → 中文动作（actor 语义化：执行期事件归 worker，生命周期归系统）。 */
const SUBTASK_EVENT_LABELS: Record<string, string> = {
  'subtask.planned': '登记子任务',
  'subtask.started': '子任务开始',
  'subtask.succeeded': '子任务完成',
  'subtask.failed': '子任务失败',
  'subtask.cancelled': '子任务取消',
};

export const PROCESS_EVENT_LABELS: Record<string, string> = {
  'task.started': '任务开始执行',
  'task.progress': '进度更新',
  'task.planned': '计划已生成',
  'task.checkpoint': '记录检查点',
  'tool.called': '调用工具',
  'tool.finished': '工具执行完成',
  'task.completed': '任务完成',
  'task.failed': '任务失败',
  ...SUBTASK_EVENT_LABELS,
};

/** 过程事件的精简形态（纯函数测试与归属计算共用）。 */
export type TaskProcessEventLite = {
  eventId: string;
  eventType: string;
  occurredAt: string;
  payload?: string | null;
};

function subtaskPayload(data: Record<string, unknown>): {
  subtaskId?: string;
  title?: string;
  note?: string;
  sequence?: number;
} {
  return {
    subtaskId: typeof data.subtaskId === 'string' ? data.subtaskId : undefined,
    title: typeof data.title === 'string' ? data.title : undefined,
    note: typeof data.note === 'string' ? data.note : undefined,
    sequence: typeof data.sequence === 'number' ? data.sequence : undefined,
  };
}

export function processEventSummary(event: { eventType: string; payload?: string | null }): string {
  if (!event.payload) return '';
  try {
    const data = JSON.parse(event.payload) as Record<string, unknown>;
    if (typeof data.tool === 'string' && event.eventType === 'tool.called') return data.tool;
    if (typeof data.tool === 'string' && event.eventType === 'tool.finished') {
      const ms = typeof data.elapsedMs === 'number' ? `${data.elapsedMs}ms` : '—';
      return `${data.tool} · ${ms} · ${data.ok === false ? '失败' : '成功'}`;
    }
    if (event.eventType.startsWith('subtask.')) {
      const meta = subtaskPayload(data);
      const head = meta.title
        ? `#${meta.sequence ?? '—'} ${meta.title}`
        : (meta.subtaskId?.slice(0, 8) ?? '');
      return meta.note ? `${head} · ${meta.note}` : head;
    }
    return event.payload;
  } catch {
    return event.payload;
  }
}

/** 从 planned 事件构建 subtaskId → title（best-effort，未登记的子任务回落短 id）。 */
export function buildSubtaskContext(events: TaskProcessEventLite[]): {
  titles: Map<string, string>;
} {
  const titles = new Map<string, string>();
  events.forEach((event) => {
    if (event.eventType !== 'subtask.planned' || !event.payload) return;
    try {
      const data = JSON.parse(event.payload) as Record<string, unknown>;
      const meta = subtaskPayload(data);
      if (meta.subtaskId && meta.title) titles.set(meta.subtaskId, meta.title);
    } catch {
      // best-effort：损坏的 payload 不影响其余事件
    }
  });
  return { titles };
}

/** 时间窗口归属：subtask.started 之后、下一子任务终态之前的 tool.* 事件归该子任务。 */
export function subtaskWindow(events: TaskProcessEventLite[]): Map<string, string> {
  const TERMINAL = new Set(['subtask.succeeded', 'subtask.failed', 'subtask.cancelled']);
  const ordered = [...events].sort(
    (a, b) => (Date.parse(a.occurredAt) || 0) - (Date.parse(b.occurredAt) || 0),
  );
  const attribution = new Map<string, string>();
  let current: string | undefined;
  ordered.forEach((event) => {
    if (event.eventType === 'subtask.started') {
      let meta: ReturnType<typeof subtaskPayload> = {};
      if (event.payload) {
        try {
          meta = subtaskPayload(JSON.parse(event.payload) as Record<string, unknown>);
        } catch {
          // best-effort
        }
      }
      current = meta.subtaskId;
    } else if (TERMINAL.has(event.eventType)) {
      current = undefined;
    } else if (event.eventType.startsWith('tool.') && current) {
      attribution.set(event.eventId, current);
    }
  });
  return attribution;
}

/** 时间线条目：生命周期与过程事件归并后的统一形态。 */
export type TimelineItem = {
  id: string;
  title: string;
  description?: string;
  time?: string;
  tone?: string;
  subtaskId?: string;
};

/** 给过程事件标注归属子任务：tool.* 取时间窗口归属；subtask.* 取自身 payload。 */
export function withSubtaskOwnership(
  process: TaskProcessEventLite[],
): Array<TaskProcessEventLite & { subtaskId?: string }> {
  const attribution = subtaskWindow(process);
  return process.map((event) => {
    let subtaskId = attribution.get(event.eventId);
    if (!subtaskId && event.eventType.startsWith('subtask.') && event.payload) {
      try {
        subtaskId = subtaskPayload(JSON.parse(event.payload) as Record<string, unknown>).subtaskId;
      } catch {
        // best-effort：解析失败不阻塞归属
      }
    }
    return { ...event, subtaskId };
  });
}

/** 生命周期流与过程事件流按时间归并，供详情页主列时间线渲染。 */
export function mergeTaskTimelines(
  lifecycle: Array<{
    id: string;
    title: string;
    description?: string;
    time?: string;
    tone?: string;
  }>,
  process: Array<{
    eventId: string;
    eventType: string;
    occurredAt: string;
    payload?: string | null;
    subtaskId?: string;
  }>,
): TimelineItem[] {
  const lifecycleItems = lifecycle.map((event) => ({
    id: `lifecycle:${event.id}`,
    title: event.title,
    description: event.description,
    time: event.time,
    tone: event.tone,
  }));
  const processItems = process.map((event) => ({
    id: `process:${event.eventId}`,
    title: PROCESS_EVENT_LABELS[event.eventType] || event.eventType,
    description: processEventSummary(event),
    time: event.occurredAt,
    tone:
      event.eventType === 'task.failed' || event.eventType === 'subtask.failed'
        ? 'danger'
        : undefined,
    // subtaskId 由任务 7 在 TaskDetailPage 经 withSubtaskOwnership 预计算注入。
    subtaskId: event.subtaskId,
  }));
  // ISO 时间戳可能省略尾零（Instant.toString 同秒混合精度），用时间戳数值比较而非字典序。
  return [...lifecycleItems, ...processItems].sort(
    (left, right) => (Date.parse(left.time || '') || 0) - (Date.parse(right.time || '') || 0),
  );
}

export function TaskInfoPanel({
  projectId,
  taskId,
  runId,
  task,
  processEvents,
  selectedSubtaskId,
  onSelectSubtask,
  subtaskTitles,
}: {
  projectId: string;
  taskId: string;
  runId: string;
  task: Task;
  processEvents: TaskProcessEvent[];
  /** 当前下钻选中的子任务（TaskDetailPage 状态提升）。 */
  selectedSubtaskId?: string | null;
  onSelectSubtask?: (subtaskId: string) => void;
  subtaskTitles?: Map<string, string>;
}) {
  const [tab, setTab] = useState<TabName>('events');
  const tree = useTaskTree(projectId, taskId, runId);
  const decisions = useTaskDecisions(projectId, taskId, runId);
  const result = useTaskResult(projectId, taskId, runId);
  const source = task.source;
  return (
    <aside className="info-panel">
      <section className="panel">
        <p className="eyebrow">任务结构</p>
        <h2>分解图</h2>
        <TaskDag
          nodes={(tree.data || []) as TaskTreeNode[]}
          rootTaskId={taskId}
          titles={subtaskTitles}
          selectedSubtaskId={selectedSubtaskId}
          onSelectSubtask={onSelectSubtask}
        />
      </section>
      <section className="panel">
        <div className="info-panel__tabs" role="tablist">
          {TABS.map((item) => (
            <button
              key={item.name}
              role="tab"
              aria-selected={tab === item.name}
              className={`button button--ghost ${tab === item.name ? 'is-active' : ''}`}
              onClick={() => setTab(item.name)}
            >
              {item.label}
            </button>
          ))}
        </div>
        {tab === 'events' && (
          <ul className="info-panel__list">
            {processEvents.length === 0 && <li className="muted-text">暂无过程事件。</li>}
            {processEvents.map((event) => (
              <li key={event.eventId}>
                <strong>{PROCESS_EVENT_LABELS[event.eventType] || event.eventType}</strong>
                <span className="muted-text">{processEventSummary(event)}</span>
                <time>{new Date(event.occurredAt).toLocaleTimeString('zh-CN')}</time>
              </li>
            ))}
          </ul>
        )}
        {tab === 'artifacts' && (
          <ul className="info-panel__list">
            {!result.data?.artifacts?.length && <li className="muted-text">尚未产出成果物。</li>}
            {result.data?.artifacts?.map((artifact) => (
              <li key={`${artifact.name}-${artifact.version}`}>
                <strong>{artifact.name}</strong>
                <span className="muted-text">
                  {artifact.contentType} · {artifact.sizeBytes} B
                </span>
              </li>
            ))}
          </ul>
        )}
        {tab === 'decisions' && (
          <ul className="info-panel__list">
            {!decisions.data?.length && <li className="muted-text">暂无决策记录。</li>}
            {decisions.data?.map((decision) => (
              <li key={decision.id}>
                <strong>{decision.goalSummary}</strong>
                <span className="muted-text">{decision.selectedAction}</span>
              </li>
            ))}
          </ul>
        )}
        {tab === 'details' && (
          <div className="detail-list">
            <span>
              任务类型<strong>{task.taskType || 'NORMAL'}</strong>
            </span>
            <span>
              优先级<strong>P{task.priority}</strong>
            </span>
            <span>
              团队<strong>{task.teamId || '未绑定'}</strong>
            </span>
            <span>
              创建时间<strong>{new Date(task.createdAt).toLocaleString('zh-CN')}</strong>
            </span>
            {source?.conversationId && (
              <span data-testid="source-backlink">
                来源会话
                <strong>
                  <Link to={`/${projectId}/conversations/${source.conversationId}`}>
                    打开来源会话
                  </Link>
                </strong>
              </span>
            )}
          </div>
        )}
      </section>
    </aside>
  );
}
