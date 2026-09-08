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
export const PROCESS_EVENT_LABELS: Record<string, string> = {
  'task.started': '任务开始执行',
  'task.progress': '进度更新',
  'task.planned': '计划已生成',
  'task.checkpoint': '记录检查点',
  'tool.called': '调用工具',
  'tool.finished': '工具执行完成',
  'task.completed': '任务完成',
  'task.failed': '任务失败',
};

export function processEventSummary(event: { eventType: string; payload?: string | null }): string {
  if (!event.payload) return '';
  try {
    const data = JSON.parse(event.payload) as Record<string, unknown>;
    if (typeof data.tool === 'string' && event.eventType === 'tool.called') return data.tool;
    if (typeof data.tool === 'string' && event.eventType === 'tool.finished') {
      const ms = typeof data.elapsedMs === 'number' ? `${data.elapsedMs}ms` : '—';
      return `${data.tool} · ${ms} · ${data.ok === false ? '失败' : '成功'}`;
    }
    return event.payload;
  } catch {
    return event.payload;
  }
}

/** 生命周期流与过程事件流按时间归并，供详情页主列时间线渲染。 */
export function mergeTaskTimelines(
  lifecycle: Array<{ id: string; title: string; description?: string; time?: string; tone?: string }>,
  process: Array<{ eventId: string; eventType: string; occurredAt: string; payload?: string | null }>,
) {
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
    tone: event.eventType === 'task.failed' ? 'danger' : undefined,
  }));
  return [...lifecycleItems, ...processItems].sort(
    (left, right) => (left.time || '').localeCompare(right.time || ''),
  );
}

export function TaskInfoPanel({
  projectId,
  taskId,
  runId,
  task,
  processEvents,
}: {
  projectId: string;
  taskId: string;
  runId: string;
  task: Task;
  processEvents: TaskProcessEvent[];
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
        <TaskDag nodes={(tree.data || []) as TaskTreeNode[]} rootTaskId={taskId} />
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
                <span className="muted-text">{artifact.contentType} · {artifact.sizeBytes} B</span>
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
            <span>任务类型<strong>{task.taskType || 'NORMAL'}</strong></span>
            <span>优先级<strong>P{task.priority}</strong></span>
            <span>团队<strong>{task.teamId || '未绑定'}</strong></span>
            <span>创建时间<strong>{new Date(task.createdAt).toLocaleString('zh-CN')}</strong></span>
            <span data-testid="source-backlink">
              来源会话
              <strong>
                {source?.conversationId
                  ? <Link to={`/${projectId}/conversations/${source.conversationId}`}>打开来源会话</Link>
                  : '未关联'}
              </strong>
            </span>
          </div>
        )}
      </section>
    </aside>
  );
}
