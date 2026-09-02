import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ConversationPage } from '../../src/features/conversations/ConversationPage';

const mocks = vi.hoisted(() => ({
  getConversation: vi.fn(),
  getConversationHistory: vi.fn(),
  createConversation: vi.fn(),
  sendConversationMessage: vi.fn(),
  cancelConversation: vi.fn(),
  streamConversationEvents: vi.fn(),
  listTeams: vi.fn(),
}));

vi.mock('../../src/api/conversations', () => mocks);
vi.mock('../../src/api/teams', () => ({ listTeams: mocks.listTeams }));
vi.mock('../../src/streams/conversationEvents', () => ({
  streamConversationEvents: mocks.streamConversationEvents,
  conversationEventText: (event: { payload: Record<string, unknown> }) =>
    typeof event.payload.text === 'string' ? event.payload.text : String(event.payload.delta ?? ''),
}));

function renderPage(conversationId?: string, search = '') {
  return render(
    <MemoryRouter initialEntries={[`/p-1/conversations/${conversationId || 'new'}${search}`]}>
      <QueryClientProvider client={new QueryClient()}>
        <ConversationPage projectId="p-1" conversationId={conversationId} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('ConversationPage', () => {
  it('appends user content and assistant deltas as safe plain text', async () => {
    mocks.getConversation.mockResolvedValue({
      id: 'c-1',
      projectId: 'p-1',
      teamId: 'team-1',
      status: 'ACTIVE',
      version: 1,
    });
    mocks.streamConversationEvents.mockImplementation(async (id, options) => {
      expect(id).toBe('c-1');
      options.onState('connected');
      options.onEvent({
        id: '1',
        type: 'message.delta',
        data: '',
        payload: { delta: '<b>回答</b>' },
      });
      options.onEvent({ id: '2', type: 'message.completed', data: '', payload: {} });
    });
    mocks.sendConversationMessage.mockResolvedValue({ session: { version: 2 } });

    renderPage('c-1');
    expect(await screen.findByText('<b>回答</b>')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '回答' })).not.toBeInTheDocument();
    await userEvent.type(screen.getByPlaceholderText('输入消息'), '请继续');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(await screen.findByText('请继续')).toBeInTheDocument();
    expect(mocks.sendConversationMessage).toHaveBeenCalledWith(
      'c-1',
      { content: '请继续', expectedVersion: 1 },
      expect.anything(),
      expect.any(String),
    );
  });

  it('confirms cancellation and disables sending after the server confirms it', async () => {
    mocks.getConversation.mockResolvedValue({
      id: 'c-1',
      projectId: 'p-1',
      teamId: 'team-1',
      status: 'ACTIVE',
      version: 3,
    });
    mocks.streamConversationEvents.mockResolvedValue(undefined);
    mocks.cancelConversation.mockResolvedValue({ status: 'CANCELLED', version: 4 });

    renderPage('c-1');
    await screen.findByText('Team team-1');
    await userEvent.click(screen.getByRole('button', { name: '取消会话' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('取消后将不能继续发送消息');
    await userEvent.click(screen.getByRole('button', { name: '确认取消会话' }));

    await waitFor(() =>
      expect(mocks.cancelConversation).toHaveBeenCalledWith(
        'c-1',
        { expectedVersion: 3 },
        expect.anything(),
        expect.any(String),
      ),
    );
    expect(await screen.findByText('会话已取消')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled();
  });

  it('restores durable user and assistant history after loading a conversation', async () => {
    mocks.getConversation.mockResolvedValue({
      id: 'c-1',
      projectId: 'p-1',
      teamId: 'team-1',
      status: 'ACTIVE',
      version: 2,
    });
    mocks.getConversationHistory.mockResolvedValue({
      messages: [{ idempotencyKey: 'm-1', content: '历史问题', startCursor: 1, endCursor: 3 }],
      events: [
        { id: 1, event: 'conversation.started', data: {} },
        { id: 2, event: 'message.delta', data: { text: '历史回答' } },
      ],
    });
    mocks.streamConversationEvents.mockResolvedValue(undefined);

    renderPage('c-1');

    expect(await screen.findByText('历史问题')).toBeInTheDocument();
    expect(await screen.findByText('历史回答')).toBeInTheDocument();
    expect(mocks.getConversationHistory).toHaveBeenCalledWith('c-1');
  });

  it('shows send failures and restores the failed draft', async () => {
    mocks.getConversation.mockResolvedValue({
      id: 'c-1',
      projectId: 'p-1',
      teamId: 'team-1',
      status: 'ACTIVE',
      version: 1,
    });
    mocks.streamConversationEvents.mockResolvedValue(undefined);
    mocks.sendConversationMessage.mockRejectedValue(new Error('发送失败'));

    renderPage('c-1');
    await screen.findByText('Team team-1');
    await userEvent.type(screen.getByPlaceholderText('输入消息'), '请重试');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('发送失败');
    expect(screen.getByPlaceholderText('输入消息')).toHaveValue('请重试');
  });

  it('queues supplementary messages and sends them with the latest conversation version', async () => {
    mocks.getConversation.mockResolvedValue({
      id: 'c-1',
      projectId: 'p-1',
      teamId: 'team-1',
      status: 'ACTIVE',
      version: 1,
    });
    mocks.streamConversationEvents.mockResolvedValue(undefined);
    mocks.sendConversationMessage.mockClear();
    let resolveFirst: (value: { session: { version: number } }) => void = () => undefined;
    const firstResponse = new Promise<{ session: { version: number } }>((resolve) => {
      resolveFirst = resolve;
    });
    mocks.sendConversationMessage
      .mockImplementationOnce(() => firstResponse)
      .mockResolvedValueOnce({ session: { version: 3 } });

    renderPage('c-1');
    await screen.findByText('Team team-1');
    await userEvent.type(screen.getByPlaceholderText('输入消息'), '第一条');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));
    await userEvent.type(screen.getByPlaceholderText('输入消息'), '补充信息');
    await userEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(mocks.sendConversationMessage).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('已排队 1 条补充信息')).toBeInTheDocument();
    resolveFirst({ session: { version: 2 } });
    await waitFor(() => expect(mocks.sendConversationMessage).toHaveBeenCalledTimes(2));
    expect(mocks.sendConversationMessage).toHaveBeenNthCalledWith(
      2,
      'c-1',
      { content: '补充信息', expectedVersion: 2 },
      expect.anything(),
      expect.any(String),
    );
  });

  it('offers an actionable team selection state when no team context exists', async () => {
    mocks.listTeams.mockResolvedValue({ items: [] });

    renderPage(undefined);

    expect(await screen.findByText('没有可用 Team')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '前往 Teams' })).toHaveAttribute('href', '/p-1/teams');
  });
});
