import { useState } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { ResourceTable } from '../../components/ResourceTable';
import { StatusBadge } from '../../components/StatusBadge';
import { CursorPagination } from '../../components/CursorPagination';
import { listConversations } from '../../api/conversations';
import { queryKeys } from '../../queries/queryKeys';
import { useQuery } from '@tanstack/react-query';

export function ConversationListPage({ projectId }: { projectId: string }) {
  const [cursor, setCursor] = useState<string | undefined>();
  const [cursorHistory, setCursorHistory] = useState<string[]>([]);
  const conversations = useQuery({
    queryKey: queryKeys.conversations(projectId, cursor),
    queryFn: () => listConversations(projectId, { cursor }),
  });
  const items = conversations.data?.items || [];
  const tableItems = items.map((conversation) => ({ ...conversation, id: conversation.sessionId }));

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">CONVERSATION</p>
          <h1>历史对话</h1>
          <p>查看当前 Project 下已保存的历史会话。</p>
        </div>
        <Link className="button button--primary" to={`/${projectId}/conversations/new`}>
          新建对话
        </Link>
      </div>
      <div className="toolbar">
        <button className="button button--ghost" onClick={() => void conversations.refetch()}>
          刷新
        </button>
      </div>
      {conversations.isLoading ? (
        <div className="panel loading-block">加载历史对话…</div>
      ) : conversations.isError ? (
        <ErrorState error={conversations.error} onRetry={() => void conversations.refetch()} />
      ) : items.length ? (
        <ResourceTable
          items={tableItems}
          columns={[
            {
              key: 'conversation',
              header: '会话',
              render: (conversation) => (
                <Link
                  className="resource-link"
                  to={`/${projectId}/conversations/${conversation.sessionId}`}
                >
                  <strong>{conversation.lastMessage || '未命名对话'}</strong>
                  <small>{conversation.sessionId}</small>
                </Link>
              ),
            },
            { key: 'team', header: 'Team', render: (conversation) => conversation.context.team },
            {
              key: 'status',
              header: '状态',
              render: (conversation) => <StatusBadge phase={conversation.status} />,
            },
            {
              key: 'updated',
              header: '更新时间',
              render: (conversation) => new Date(conversation.updatedAt).toLocaleString('zh-CN'),
            },
          ]}
        />
      ) : (
        <EmptyState
          title="暂无历史会话"
          description="创建一个对话后会显示在这里。"
          action={
            <Link className="button button--primary" to={`/${projectId}/conversations/new`}>
              新建对话
            </Link>
          }
        />
      )}
      {!conversations.isLoading && !conversations.isError && items.length > 0 && (
        <CursorPagination
          hasPrevious={cursorHistory.length > 0}
          hasNext={Boolean(conversations.data?.nextCursor)}
          onPrevious={() => {
            const previous = cursorHistory[cursorHistory.length - 1];
            setCursorHistory((history) => history.slice(0, -1));
            setCursor(previous);
          }}
          onNext={() => {
            if (!conversations.data?.nextCursor) return;
            setCursorHistory((history) => [...history, cursor || '']);
            setCursor(conversations.data.nextCursor || undefined);
          }}
        />
      )}
    </div>
  );
}
