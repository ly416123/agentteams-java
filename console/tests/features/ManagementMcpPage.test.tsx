import { screen } from '@testing-library/react';
import { renderAuthorized } from '../fixtures/authorizedRender';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementMcpPage } from '../../src/features/management/ManagementMcpPage';
import { ApiError } from '../../src/api/httpClient';

const mocks = vi.hoisted(() => ({
  listMcpServers: vi.fn().mockResolvedValue([
    {
      id: 'mcp-1',
      name: 'docs',
      transport: 'STREAMABLE_HTTP',
      endpoint: 'https://mcp.example.test',
      credentialConfigured: false,
      enabled: true,
      healthStatus: 'UNKNOWN',
      lastCheckedAt: null,
      version: 0,
    },
  ]),
  createMcpServer: vi.fn().mockResolvedValue({ id: 'mcp-2' }),
  updateMcpServerHealth: vi.fn().mockResolvedValue({ id: 'mcp-1', healthStatus: 'HEALTHY' }),
  updateMcpServer: vi.fn().mockResolvedValue({ id: 'mcp-1', name: 'docs-v2' }),
  getMcpDiscovery: vi.fn().mockResolvedValue({
    serverId: 'mcp-1',
    serverRevision: 0,
    status: 'AVAILABLE',
    toolsDigest: 'sha256:tools',
    healthyInstances: 1,
    freshInstances: 1,
    latestObservedAt: '2026-09-02T00:00:00Z',
    failureCategories: [],
  }),
  testMcpConnection: vi.fn().mockResolvedValue({
    status: 'HEALTHY',
    category: 'SUCCESS',
    checkedAt: '2026-09-02T00:00:00Z',
    detail: null,
    latencyMillis: 12,
  }),
  deleteMcpServer: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('../../src/api/managementCatalog', () => ({ ...mocks }));

function renderPage(permissions?: readonly string[]) {
  return renderAuthorized(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementMcpPage />
      </QueryClientProvider>
    </MemoryRouter>,
    undefined,
    permissions,
  );
}

describe('Management MCP page', () => {
  it('hides MCP mutations from users with read permission only', async () => {
    renderPage(['mcp:read']);
    expect(await screen.findByRole('heading', { name: 'MCP 服务' })).toBeInTheDocument();
    expect(await screen.findByText('docs')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '登记 MCP Server' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '连接测试' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '记录健康检查' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '删除' })).not.toBeInTheDocument();
    expect(mocks.createMcpServer).not.toHaveBeenCalled();
    expect(mocks.updateMcpServerHealth).not.toHaveBeenCalled();
    expect(mocks.updateMcpServer).not.toHaveBeenCalled();
    expect(mocks.deleteMcpServer).not.toHaveBeenCalled();
  });

  it('registers a server without accepting a secret and records health', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'MCP 服务' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('名称'), 'search');
    await userEvent.click(screen.getByText('登记 MCP 服务'));
    await userEvent.type(screen.getByLabelText('服务地址'), 'https://search.example.test');
    await userEvent.type(screen.getByLabelText('Credential Ref（可选）'), 'secret://mcp/search');
    await userEvent.click(screen.getByRole('button', { name: '登记 MCP Server' }));
    expect(mocks.createMcpServer).toHaveBeenCalledWith({
      name: 'search',
      transport: 'STREAMABLE_HTTP',
      endpoint: 'https://search.example.test',
      credentialRef: 'secret://mcp/search',
      enabled: true,
    });
    await userEvent.click(screen.getByRole('button', { name: '记录健康检查' }));
    expect(mocks.updateMcpServerHealth).toHaveBeenCalledWith(
      'mcp-1',
      expect.objectContaining({
        healthStatus: 'HEALTHY',
      }),
    );
    await userEvent.click(screen.getByRole('button', { name: '删除' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除 MCP Server' }));
    expect(mocks.deleteMcpServer).toHaveBeenCalledWith('mcp-1');
  });

  it('edits a server and exposes real connection and discovery status', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'MCP 服务' })).toBeInTheDocument();
    expect(await screen.findByText('docs')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '连接测试' }));
    expect(mocks.testMcpConnection).toHaveBeenCalledWith('mcp-1');
    expect(await screen.findByText(/成功 · 12 毫秒/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '查看 Discovery' }));
    expect(mocks.getMcpDiscovery).toHaveBeenCalledWith('mcp-1');
    expect(await screen.findByText(/可用 · 1\/1 个实例/)).toBeInTheDocument();
    expect(screen.getByText(/发现状态：.*sha256:tools/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '编辑' }));
    const name = screen.getByLabelText('名称');
    await userEvent.clear(name);
    await userEvent.type(name, 'docs-v2');
    await userEvent.click(screen.getByRole('button', { name: '保存 MCP Server' }));
    expect(mocks.updateMcpServer).toHaveBeenCalledWith(
      'mcp-1',
      expect.objectContaining({ name: 'docs-v2', expectedVersion: 0 }),
    );
  });

  it('shows a generic safe message when the server denies MCP deletion', async () => {
    mocks.deleteMcpServer.mockRejectedValueOnce(
      new ApiError(403, { code: 'FORBIDDEN', message: 'MCP is owned by another tenant' }),
    );
    renderPage();
    await screen.findByRole('heading', { name: 'MCP 服务' });
    await userEvent.click(await screen.findByRole('button', { name: '删除' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除 MCP Server' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/owned by another tenant/)).not.toBeInTheDocument();
  });
});
