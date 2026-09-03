import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  cancelConversation,
  createConversation,
  getConversationHistory,
  getConversation,
  sendConversationMessage,
  type Conversation,
} from '../../api/conversations';
import { apiClient } from '../../api/httpClient';
import { listTeams } from '../../api/teams';
import {
  conversationEventText,
  streamConversationEvents,
  type ConversationEvent,
} from '../../streams/conversationEvents';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';
import { ErrorState } from '../../components/ErrorState';

type TranscriptItem = {
  key: string;
  role: 'user' | 'assistant' | 'event';
  type?: string;
  text: string;
};

function eventOrder(event: ConversationEvent) {
  if (typeof event.order === 'number') return event.order;
  const numericId = event.id === undefined ? Number.NaN : Number(event.id);
  return Number.isFinite(numericId) ? numericId : Number.POSITIVE_INFINITY;
}

function buildTranscript(events: ConversationEvent[]): TranscriptItem[] {
  const items: TranscriptItem[] = [];
  let assistantDraft = '';
  let assistantKey = '';
  const flushAssistant = () => {
    if (!assistantDraft) return;
    items.push({
      key: assistantKey || `assistant-${items.length}`,
      role: 'assistant',
      text: assistantDraft,
    });
    assistantDraft = '';
    assistantKey = '';
  };
  const ordered = events
    .map((event, index) => ({ event, index }))
    .sort(
      (left, right) => eventOrder(left.event) - eventOrder(right.event) || left.index - right.index,
    )
    .map(({ event }) => event);

  ordered.forEach((event, index) => {
    if (event.type === 'conversation.started') return;
    if (event.type === 'user.message') {
      flushAssistant();
      items.push({
        key: event.id || `user-${index}`,
        role: 'user',
        text: conversationEventText(event) || event.data,
      });
      return;
    }
    if (event.type === 'message.delta') {
      const text = conversationEventText(event);
      if (text) {
        assistantDraft += text;
        assistantKey ||= event.id || `assistant-${index}`;
      }
      return;
    }
    if (event.type === 'message.completed') {
      flushAssistant();
      const text = conversationEventText(event);
      if (text) items.push({ key: event.id || `assistant-${index}`, role: 'assistant', text });
      return;
    }
    flushAssistant();
    items.push({
      key: event.id || `event-${index}`,
      role: 'event',
      type: event.type,
      text: conversationEventText(event) || event.data,
    });
  });
  flushAssistant();
  return items;
}

export function ConversationPage({
  projectId,
  conversationId,
}: {
  projectId: string;
  conversationId?: string;
}) {
  const [conversation, setConversation] = useState<Conversation>();
  const conversationRef = useRef<Conversation>();
  const [events, setEvents] = useState<ConversationEvent[]>([]);
  const [content, setContent] = useState('');
  const pendingMessages = useRef<{ content: string; idempotencyKey: string }[]>([]);
  const sendingMessages = useRef(false);
  const [queuedMessageCount, setQueuedMessageCount] = useState(0);
  const [streamState, setStreamState] = useState<
    'connecting' | 'connected' | 'reconnecting' | 'error'
  >('connecting');
  const [loadError, setLoadError] = useState<unknown>();
  const [sendError, setSendError] = useState<unknown>();
  const [cancelError, setCancelError] = useState<unknown>();
  const [streamError, setStreamError] = useState<unknown>();
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
        .catch((nextError) => active && setLoadError(nextError));
      return () => {
        active = false;
      };
    }
    const streamController = new AbortController();
    void getConversation(conversationId)
      .then((next) => {
        if (!active) return;
        conversationRef.current = next;
        setConversation(next);
        setTeamId(next.teamId || '');
        void Promise.resolve(getConversationHistory(conversationId))
          .then((history) => {
            if (!active || !history) return;
            const historicalEvents = [
              ...history.messages.map((message) => ({
                id: `message-${message.idempotencyKey}`,
                type: 'user.message',
                data: message.content,
                payload: { text: message.content },
                order: message.startCursor + 0.5,
              })),
              ...history.events.map((event) => ({
                id: String(event.id),
                type: event.event,
                data: typeof event.data === 'string' ? event.data : JSON.stringify(event.data),
                payload:
                  event.data && typeof event.data === 'object'
                    ? (event.data as Record<string, unknown>)
                    : { text: String(event.data ?? '') },
                order: event.id,
              })),
            ].sort((left, right) => left.order - right.order);
            setEvents((current) => {
              const byId = new Map(current.map((item) => [item.id, item]));
              historicalEvents.forEach((item) => byId.set(item.id, item));
              return [...byId.values()].sort((left, right) => {
                return eventOrder(left) - eventOrder(right);
              });
            });
          })
          .catch((nextError) => active && setLoadError(nextError));
        void streamConversationEvents(conversationId, {
          client: apiClient,
          keepAlive: true,
          onEvent: (event) =>
            active &&
            setEvents((current) =>
              current.some((item) => item.id === event.id)
                ? current
                : [...current, event].sort((left, right) => eventOrder(left) - eventOrder(right)),
            ),
          onState: (state) => active && setStreamState(state),
          signal: streamController.signal,
        }).catch((nextError) => {
          if (active && !(nextError instanceof DOMException && nextError.name === 'AbortError')) {
            setStreamError(nextError);
          }
        });
      })
      .catch((nextError) => active && setLoadError(nextError));
    return () => {
      active = false;
      streamController.abort();
    };
  }, [conversationId, projectId]);

  if (!conversationId) {
    if (loadError)
      return (
        <div className="page">
          <ErrorState error={loadError} />
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
                  .catch(setLoadError);
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
  if (loadError && !conversation)
    return (
      <div className="page">
        <ErrorState error={loadError} />
      </div>
    );
  const status = conversation?.status || 'LOADING';
  const transcript = buildTranscript(events);
  async function drainPendingMessages() {
    if (sendingMessages.current) return;
    sendingMessages.current = true;
    try {
      while (pendingMessages.current.length) {
        const pending = pendingMessages.current.shift();
        setQueuedMessageCount(pendingMessages.current.length);
        if (!pending) break;
        const current = conversationRef.current;
        if (!current || current.status === 'CANCELLED') {
          setContent((draft) => (draft ? `${pending.content}\n${draft}` : pending.content));
          continue;
        }
        const sessionId = current.id || current.sessionId || conversationId;
        if (!sessionId) {
          setContent((draft) => (draft ? `${pending.content}\n${draft}` : pending.content));
          setSendError(new Error('会话标识不可用'));
          break;
        }
        try {
          const result = await sendConversationMessage(
            sessionId,
            { content: pending.content, expectedVersion: current.version },
            apiClient,
            pending.idempotencyKey,
          );
          const nextVersion = result?.session?.version;
          if (nextVersion !== undefined && conversationRef.current) {
            const nextConversation = { ...conversationRef.current, version: nextVersion };
            conversationRef.current = nextConversation;
            setConversation(nextConversation);
          }
        } catch (nextError) {
          const queuedDrafts = pendingMessages.current.splice(0).map((item) => item.content);
          setQueuedMessageCount(0);
          setContent((draft) =>
            [pending.content, ...queuedDrafts, draft].filter(Boolean).join('\n'),
          );
          setSendError(nextError);
          break;
        }
      }
    } finally {
      sendingMessages.current = false;
      setQueuedMessageCount(pendingMessages.current.length);
    }
  }
  const send = () => {
    const message = content.trim();
    if (!message || !conversation || status === 'CANCELLED') return;
    setSendError(undefined);
    setContent('');
    pendingMessages.current.push({ content: message, idempotencyKey: crypto.randomUUID() });
    setQueuedMessageCount(pendingMessages.current.length);
    setEvents((current) => {
      const finiteOrders = current.map(eventOrder).filter(Number.isFinite);
      const nextOrder = (finiteOrders.length ? Math.max(...finiteOrders) : 0) + 0.5;
      return [
        ...current,
        {
          id: `local-${crypto.randomUUID()}`,
          type: 'user.message',
          data: message,
          payload: { text: message },
          order: nextOrder,
        },
      ];
    });
    void drainPendingMessages();
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
          error={streamError || new Error('事件流连接失败')}
          onRetry={() => window.location.reload()}
        />
      )}
      {queuedMessageCount > 0 && (
        <div className="info-box" role="status">
          已排队 {queuedMessageCount} 条补充信息
        </div>
      )}
      <section className="panel conversation-transcript" aria-label="对话记录">
        {transcript.map((item) => (
          <div className={`conversation-message conversation-message--${item.role}`} key={item.key}>
            <span className="eyebrow">
              {item.role === 'user' ? 'USER' : item.role === 'assistant' ? 'ASSISTANT' : item.type}
            </span>
            <p>{item.text}</p>
          </div>
        ))}
      </section>
      {status === 'CANCELLED' && <div className="info-box">会话已取消</div>}
      {Boolean(sendError) && (
        <div role="alert" className="error-box">
          发送失败：{String(sendError)}
        </div>
      )}
      {Boolean(cancelError) && (
        <div role="alert" className="error-box">
          取消失败：{String(cancelError)}
        </div>
      )}
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
            disabled={!conversation || status === 'CANCELLED'}
          >
            取消会话
          </button>
          <button
            className="button button--primary"
            onClick={send}
            disabled={!conversation || !content.trim() || status === 'CANCELLED'}
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
          setCancelError(undefined);
          pendingMessages.current = [];
          setQueuedMessageCount(0);
          void cancelConversation(
            conversation?.id || conversation?.sessionId || conversationId,
            { expectedVersion: conversation?.version },
            apiClient,
            crypto.randomUUID(),
          )
            .then((next) => {
              const updated = { ...conversationRef.current, ...next, status: 'CANCELLED' };
              conversationRef.current = updated;
              setConversation(updated);
            })
            .catch(setCancelError);
        }}
      />
    </div>
  );
}
