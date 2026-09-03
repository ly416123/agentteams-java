import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ConversationListPage } from '../../src/features/conversations/ConversationListPage';

const { listConversations } = vi.hoisted(() => ({ listConversations: vi.fn() }));

vi.mock('../../src/api/conversations', () => ({ listConversations }));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/p-1/conversations']}>
      <QueryClientProvider client={new QueryClient()}>
        <ConversationListPage projectId="p-1" />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('ConversationListPage', () => {
  it('shows historical conversations and links to their detail pages', async () => {
    listConversations.mockResolvedValue({
      items: [
        {
          sessionId: 'c-1',
          context: { project: 'p-1', team: 'team-1' },
          status: 'ACTIVE',
          lastMessage: '历史问题',
          updatedAt: '2026-09-03T01:00:00Z',
        },
      ],
      hasMore: false,
    });

    renderPage();

    expect(await screen.findByText('历史问题')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /历史问题/ })).toHaveAttribute(
      'href',
      '/p-1/conversations/c-1',
    );
    expect(screen.getByRole('link', { name: '新建对话' })).toHaveAttribute(
      'href',
      '/p-1/conversations/new',
    );
  });
});
