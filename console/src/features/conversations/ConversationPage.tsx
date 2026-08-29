import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  cancelConversation,
  createConversation,
  getConversation,
  sendConversationMessage,
  type Conversation,
} from '../../api/conversations';
import { apiClient } from '../../api/httpClient';
import { listTeams } from '../../api/teams';
import { streamConversationEvents, type ConversationEvent } from '../../streams/conversationEvents';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';
import { ErrorState } from '../../components/ErrorState';

export function ConversationPage({
  projectId,
  conversationId,
}: {
  projectId: string;
  conversationId?: string;
}) {
  const [conversation, setConversation] = useState<Conversation>();
  const [events, setEvents] = useState<ConversationEvent[]>([]);
  const [content, setContent] = useState('');
  const [streamState, setStreamState] = useState<
    'connecting' | 'connected' | 'reconnecting' | 'error'
  >('connecting');
  const [error, setError] = useState<unknown>();
  const [cancelOpen, setCancelOpen] = useState(false);
  const [teams, setTeams] = useState<{ id: string; displayName?: string }[]>([]);
  const [teamId, setTeamId] = useState('');

  useEffect(() => {
    let active = true;
    if (!conversationId) {
      void listTeams(projectId, {})
        .then((page) => {
          if (active) setTeams(page.items as typeof teams);
        })
        .catch((nextError) => active && setError(nextError));
      return () => {
        active = false;
      };
    }
    void getConversation(conversationId)
      .then((next) => {
        if (!active) return;
        setConversation(next);
        setTeamId(next.teamId || '');
        void streamConversationEvents(conversationId, {
          client: apiClient,
          onEvent: (event) =>
            active &&
            setEvents((current) =>
              current.some((item) => item.id === event.id) ? current : [...current, event],
            ),
          onState: (state) => active && setStreamState(state),
        }).catch((nextError) => active && setError(nextError));
      })
      .catch((nextError) => active && setError(nextError));
    return () => {
      active = false;
    };
  }, [conversationId, projectId]);

  if (!conversationId) {
    if (error)
      return (
        <div className="page">
          <ErrorState error={error} />
        </div>
      );
    return (
      <div className="page narrow-page">
        <p className="eyebrow">CONVERSATION</p>
        <h1>选择对话 Team</h1>
        {teams.length ? (
          teams.map((team) => (
            <button
              className="button button--ghost"
              key={team.id}
              onClick={() => {
                const id = crypto.randomUUID();
                void createConversation({ projectId, teamId: team.id, sessionId: id }, apiClient)
                  .then(() => {
                    window.location.assign(`/${projectId}/conversations/${id}`);
                  })
                  .catch(setError);
              }}
            >
              {team.displayName || team.id}
            </button>
          ))
        ) : (
          <>
            <p>没有可用 Team</p>
            <Link className="button button--primary" to={`/${projectId}/teams`}>
              前往 Teams
            </Link>
          </>
        )}
      </div>
    );
  }
  if (error && !conversation)
    return (
      <div className="page">
        <ErrorState error={error} />
      </div>
    );
  const status = conversation?.status || 'LOADING';
  const assistantText = events
    .filter((event) => event.type === 'message.delta')
    .map((event) => String(event.payload.delta ?? event.payload.text ?? ''))
    .join('');
  const send = () => {
    const message = content.trim();
    if (!message || !conversation || status === 'CANCELLED') return;
    setContent('');
    setEvents((current) => [
      ...current,
      {
        id: `local-${Date.now()}`,
        type: 'user.message',
        data: message,
        payload: { text: message },
      },
    ]);
    void sendConversationMessage(
      conversation.id || conversation.sessionId || conversationId,
      { content: message, expectedVersion: conversation.version },
      apiClient,
      crypto.randomUUID(),
    )
      .then((result) =>
        setConversation((current) =>
          current ? { ...current, version: result?.session?.version ?? current.version } : current,
        ),
      )
      .catch(setError);
  };
  return (
    <div className="page conversation-page">
      <Link className="back-link" to={`/${projectId}/overview`}>
        ← 返回概览
      </Link>
      <div className="detail-heading">
        <div>
          <p className="eyebrow">CONVERSATION</p>
          <h1>Worker 对话</h1>
          <p>Team {teamId || conversation?.teamId || '未绑定'}</p>
        </div>
        <span className="version-pill">{status}</span>
      </div>
      {streamState === 'reconnecting' && (
        <div className="info-box" role="status">
          事件流重连中…
        </div>
      )}
      {streamState === 'error' && (
        <ErrorState
          error={error || new Error('事件流连接失败')}
          onRetry={() => window.location.reload()}
        />
      )}
      <section className="panel conversation-transcript" aria-label="对话记录">
        {events
          .filter(
            (event) =>
              event.type !== 'conversation.started' &&
              event.type !== 'message.completed' &&
              event.type !== 'message.delta',
          )
          .map((event) => (
            <div
              className={`conversation-message conversation-message--${event.type}`}
              key={event.id}
            >
              <span className="eyebrow">{event.type}</span>
              <p>
                {String(
                  event.payload.delta ?? event.payload.text ?? event.payload.message ?? event.data,
                )}
              </p>
            </div>
          ))}
        {assistantText && (
          <div className="conversation-message conversation-message--assistant">
            <span className="eyebrow">ASSISTANT</span>
            <p>{assistantText}</p>
          </div>
        )}
      </section>
      {status === 'CANCELLED' && <div className="info-box">会话已取消</div>}
      <div className="conversation-composer">
        <textarea
          aria-label="输入消息"
          placeholder="输入消息"
          value={content}
          disabled={status === 'CANCELLED'}
          onChange={(event) => setContent(event.target.value)}
        />
        <div className="form-actions">
          <button
            className="button button--danger"
            onClick={() => setCancelOpen(true)}
            disabled={status === 'CANCELLED'}
          >
            取消会话
          </button>
          <button
            className="button button--primary"
            onClick={send}
            disabled={!content.trim() || status === 'CANCELLED'}
          >
            发送
          </button>
        </div>
      </div>
      <ActionConfirmModal
        open={cancelOpen}
        actionLabel="取消会话"
        impact="取消后将不能继续发送消息"
        onCancel={() => setCancelOpen(false)}
        onConfirm={() => {
          setCancelOpen(false);
          void cancelConversation(
            conversation?.id || conversation?.sessionId || conversationId,
            { expectedVersion: conversation?.version },
            apiClient,
            crypto.randomUUID(),
          )
            .then((next) =>
              setConversation((current) => ({ ...current, ...next, status: 'CANCELLED' })),
            )
            .catch(setError);
        }}
      />
    </div>
  );
}
