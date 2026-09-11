import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TaskDag } from './TaskDag';
import type { TaskTreeNode } from '../../api/types';

const ROOT = '11111111-1111-1111-1111-111111111111';
const A1 = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const A2 = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';

const nodes: TaskTreeNode[] = [
  {
    taskId: ROOT,
    parentTaskId: null,
    sequence: 0,
    status: 'RUNNING',
    dependencyIds: [],
    updatedAt: '2026-09-09T00:00:00Z',
  },
  {
    taskId: A1,
    parentTaskId: ROOT,
    sequence: 1,
    status: 'PENDING',
    dependencyIds: [],
    updatedAt: '2026-09-09T00:00:00Z',
  },
  {
    taskId: A2,
    parentTaskId: ROOT,
    sequence: 2,
    status: 'RUNNING',
    dependencyIds: [A1],
    updatedAt: '2026-09-09T00:00:00Z',
  },
];

describe('TaskDag', () => {
  it('titles 提供时节点显示标题而非短 id', () => {
    render(
      <TaskDag
        nodes={nodes}
        rootTaskId={ROOT}
        titles={
          new Map([
            [A1, '抓取邮件'],
            [A2, '生成摘要'],
          ])
        }
      />,
    );
    expect(screen.getByText('抓取邮件')).toBeTruthy();
    expect(screen.getByText('生成摘要')).toBeTruthy();
    expect(screen.queryByText(A1.slice(0, 8))).toBeNull();
  });

  it('titles 缺失时回落短 id', () => {
    render(<TaskDag nodes={nodes} rootTaskId={ROOT} />);
    expect(screen.getByText(A1.slice(0, 8))).toBeTruthy();
  });

  it('超过 12 字符的标题截断为 11 字符加省略号', () => {
    render(
      <TaskDag
        nodes={nodes}
        rootTaskId={ROOT}
        titles={new Map([[A1, '抓取并解析所有未读邮件附件']])}
      />,
    );
    expect(screen.getByText('抓取并解析所有未读邮件…')).toBeTruthy();
  });

  it('dependencyIds 含重复项时依赖边不重复渲染', () => {
    const duplicated = nodes.map((node) =>
      node.taskId === A2 ? { ...node, dependencyIds: [A1, A1] } : node,
    );
    const { container } = render(<TaskDag nodes={duplicated} rootTaskId={ROOT} />);
    expect(container.querySelectorAll('line.task-dag__edge--dependency')).toHaveLength(1);
  });

  it('dependencyIds 渲染依赖虚线边', () => {
    const { container } = render(<TaskDag nodes={nodes} rootTaskId={ROOT} />);
    const deps = container.querySelectorAll('line.task-dag__edge--dependency');
    expect(deps).toHaveLength(1);
  });

  it('点击子任务节点触发 onSelectSubtask，根任务与未传回调时不触发', () => {
    const onSelect = vi.fn();
    const titles = new Map([
      [A1, '抓取邮件'],
      [A2, '生成摘要'],
    ]);
    const { rerender, container } = render(
      <TaskDag nodes={nodes} rootTaskId={ROOT} titles={titles} onSelectSubtask={onSelect} />,
    );
    fireEvent.click(screen.getByText('抓取邮件').closest('g')!);
    expect(onSelect).toHaveBeenCalledWith(A1);

    rerender(
      <TaskDag nodes={nodes} rootTaskId={ROOT} titles={titles} onSelectSubtask={onSelect} />,
    );
    fireEvent.click(screen.getByText('根任务'));
    expect(onSelect).toHaveBeenCalledTimes(1);

    // 未传回调时不注册点击（无 cursor pointer 样式）
    rerender(<TaskDag nodes={nodes} rootTaskId={ROOT} />);
    const clickables = container.querySelectorAll('g[style*="cursor"]');
    expect(clickables).toHaveLength(0);
  });

  it('selectedSubtaskId 对应节点带选中样式', () => {
    const { container } = render(
      <TaskDag nodes={nodes} rootTaskId={ROOT} selectedSubtaskId={A1} />,
    );
    const selected = container.querySelectorAll('g.task-dag__node-wrapper--selected');
    expect(selected).toHaveLength(1);
    expect(selected[0].getAttribute('data-status')).toBe('PENDING');
  });

  it('提供 subtaskPhases 时节点下方渲染平台 phase 徽章，未提供的节点不渲染', () => {
    render(
      <TaskDag
        nodes={nodes}
        rootTaskId={ROOT}
        subtaskPhases={new Map([[A1, 'RUNNING']])}
      />,
    );
    const phases = screen.getAllByTestId('task-dag-phase');
    expect(phases).toHaveLength(1);
    expect(phases[0].textContent).toBe('RUNNING');
  });

  it('点击「打开 →」调用 onOpenTask 且不冒泡触发 onSelectSubtask', () => {
    const onOpen = vi.fn();
    const onSelect = vi.fn();
    const { container } = render(
      <TaskDag
        nodes={nodes}
        rootTaskId={ROOT}
        titles={new Map([[A1, '抓取邮件']])}
        onOpenTask={onOpen}
        onSelectSubtask={onSelect}
      />,
    );
    const opens = container.querySelectorAll('text.task-dag-open');
    expect(opens).toHaveLength(2); // A1、A2 两个子任务节点各一个，根任务无
    fireEvent.click(opens[0]);
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('未传 onOpenTask 时节点不渲染「打开 →」链接', () => {
    const { container } = render(<TaskDag nodes={nodes} rootTaskId={ROOT} />);
    expect(container.querySelectorAll('text.task-dag-open')).toHaveLength(0);
  });
});
