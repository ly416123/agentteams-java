import type { TaskTreeNode } from '../../api/types';

const NODE_WIDTH = 128;
const NODE_HEIGHT = 36;
const H_GAP = 24;
const V_GAP = 48;

type Positioned = { node: TaskTreeNode; x: number; y: number };

/** 分层布局：根为第一层，子节点按 sequence 从左到右排布。 */
function layout(nodes: TaskTreeNode[], rootTaskId: string): Positioned[] {
  const childrenOf = new Map<string, TaskTreeNode[]>();
  nodes.forEach((node) => {
    if (node.taskId === rootTaskId) return;
    const parent = node.parentTaskId || rootTaskId;
    childrenOf.set(parent, [...(childrenOf.get(parent) || []), node]);
  });
  const positioned: Positioned[] = [];
  const place = (taskId: string, depth: number, offset: number): number => {
    const children = childrenOf.get(taskId) || [];
    let cursor = offset;
    let centerX = offset;
    children.forEach((child, index) => {
      const childX = place(child.taskId, depth + 1, cursor);
      cursor = childX + NODE_WIDTH + H_GAP;
      if (index === Math.floor((children.length - 1) / 2)) centerX = childX;
    });
    if (!children.length) {
      centerX = offset;
      cursor = offset + NODE_WIDTH + H_GAP;
    }
    positioned.push({
      node: nodes.find((n) => n.taskId === taskId) as TaskTreeNode,
      x: centerX,
      y: depth * (NODE_HEIGHT + V_GAP),
    });
    return centerX;
  };
  if (nodes.some((node) => node.taskId === rootTaskId)) {
    place(rootTaskId, 0, 0);
  }
  return positioned;
}

type TaskDagProps = {
  nodes: TaskTreeNode[];
  rootTaskId: string;
  /** subtaskId → title（未登记的子任务回落短 id）。 */
  titles?: Map<string, string>;
  /** 当前下钻选中的子任务；null/undefined 表示全量视图。 */
  selectedSubtaskId?: string | null;
  /** 点击子任务节点回调；不传则节点不可点击。 */
  onSelectSubtask?: (subtaskId: string) => void;
};

export function TaskDag({
  nodes,
  rootTaskId,
  titles,
  selectedSubtaskId,
  onSelectSubtask,
}: TaskDagProps) {
  if (!nodes.length) {
    return <p className="muted-text">当前运行暂无任务分解。</p>;
  }
  const positioned = layout(nodes, rootTaskId);
  if (!positioned.length) {
    return <p className="muted-text">当前运行暂无任务分解。</p>;
  }
  const width = Math.max(...positioned.map((p) => p.x + NODE_WIDTH)) + 8;
  const height = Math.max(...positioned.map((p) => p.y + NODE_HEIGHT)) + 8;
  const byId = new Map(positioned.map((p) => [p.node.taskId, p]));
  // 依赖边：dependencyIds 指向同 run 内的其它子任务（不指向根，根边已由 parent 关系绘制）。
  const deps: Array<{ from: Positioned; to: Positioned }> = [];
  positioned.forEach(({ node, x, y }) => {
    (node.dependencyIds || []).forEach((depId) => {
      if (depId === rootTaskId) return;
      const from = byId.get(depId);
      if (from) deps.push({ from, to: { node, x, y } });
    });
  });
  return (
    <div className="task-dag" data-testid="task-dag">
      <svg width="100%" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="任务分解图">
        {positioned.map(({ node, x, y }) => {
          if (node.taskId === rootTaskId) return null;
          const parent = byId.get(node.parentTaskId || rootTaskId);
          if (!parent) return null;
          return (
            <line
              key={`edge-${node.taskId}`}
              x1={parent.x + NODE_WIDTH / 2}
              y1={parent.y + NODE_HEIGHT}
              x2={x + NODE_WIDTH / 2}
              y2={y}
              className="task-dag__edge"
            />
          );
        })}
        {deps.map(({ from, to }) => (
          <line
            key={`dep-${to.node.taskId}-${from.node.taskId}`}
            x1={from.x + NODE_WIDTH}
            y1={from.y + NODE_HEIGHT / 2}
            x2={to.x}
            y2={to.y + NODE_HEIGHT / 2}
            className="task-dag__edge task-dag__edge--dependency"
          />
        ))}
        {positioned.map(({ node, x, y }) => {
          const label =
            node.taskId === rootTaskId
              ? '根任务'
              : titles?.get(node.taskId) || node.taskId.slice(0, 8);
          const selected = node.taskId === selectedSubtaskId;
          const clickable = node.taskId !== rootTaskId && Boolean(onSelectSubtask);
          return (
            <g
              key={node.taskId}
              transform={`translate(${x}, ${y})`}
              data-testid="task-dag-node"
              data-status={node.status}
              className={selected ? 'task-dag__node-wrapper--selected' : undefined}
              onClick={clickable ? () => onSelectSubtask?.(node.taskId) : undefined}
              style={clickable ? { cursor: 'pointer' } : undefined}
            >
              <rect
                width={NODE_WIDTH}
                height={NODE_HEIGHT}
                rx={8}
                className={`task-dag__node task-dag__node--${node.status.toLowerCase()}`}
              />
              <text
                x={NODE_WIDTH / 2}
                y={NODE_HEIGHT / 2 + 4}
                textAnchor="middle"
                className="task-dag__label"
              >
                {label.length > 12 ? `${label.slice(0, 11)}…` : label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
