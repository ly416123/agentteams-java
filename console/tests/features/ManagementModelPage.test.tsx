import { screen } from '@testing-library/react';
import { renderAuthorized } from '../fixtures/authorizedRender';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagementModelPage } from '../../src/features/management/ManagementModelPage';
import { ApiError } from '../../src/api/httpClient';

const mocks = vi.hoisted(() => ({
  listModelProviders: vi.fn().mockResolvedValue([
    {
      id: 'provider-1',
      name: 'local',
      providerType: 'OPENAI_COMPATIBLE',
      endpoint: 'https://model.example.test',
      credentialConfigured: false,
      enabled: true,
      version: 0,
    },
  ]),
  createModelProvider: vi.fn().mockResolvedValue({ id: 'provider-2' }),
  createModel: vi.fn().mockResolvedValue({ id: 'model-1' }),
  listModels: vi.fn().mockResolvedValue([
    {
      id: 'model-1',
      providerId: 'provider-1',
      name: 'Qwen',
      modelId: 'qwen-2.5',
      capabilities: '{}',
      enabled: true,
      version: 0,
    },
  ]),
  setModelEnabled: vi.fn().mockResolvedValue({ id: 'model-1', enabled: false }),
  deleteModel: vi.fn().mockResolvedValue(undefined),
  setModelProviderEnabled: vi.fn().mockResolvedValue({
    id: 'provider-1',
    enabled: false,
  }),
  deleteModelProvider: vi.fn().mockResolvedValue(undefined),
  testModelProviderConnection: vi.fn().mockResolvedValue({
    status: 'UNAVAILABLE',
    classification: 'CREDENTIAL_NOT_CONFIGURED',
    networkCallAttempted: false,
  }),
  listModelPrices: vi.fn().mockResolvedValue([
    {
      id: 'price-1',
      provider: 'local',
      model: 'qwen-2.5',
      currency: 'USD',
      inputPricePerMillionTokens: 1.2,
      outputPricePerMillionTokens: 4.8,
      effectiveFrom: '2026-08-29T00:00:00Z',
      effectiveTo: null,
      lifecycleStatus: 'ACTIVE',
      version: 2,
    },
  ]),
}));

vi.mock('../../src/api/managementCatalog', () => ({ ...mocks }));

function renderPage(permissions?: readonly string[]) {
  return renderAuthorized(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementModelPage />
      </QueryClientProvider>
    </MemoryRouter>,
    undefined,
    permissions,
  );
}

describe('Management model page', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows a generic safe message when the server denies model provider creation', async () => {
    mocks.createModelProvider.mockRejectedValueOnce(
      new ApiError(403, { code: 'FORBIDDEN', message: 'provider config is restricted' }),
    );
    renderPage();
    await screen.findByRole('heading', { name: '模型与价格' });
    await userEvent.click(screen.getByText('新增模型服务商或模型'));
    await userEvent.type(screen.getByLabelText('名称'), 'denied-provider');
    await userEvent.type(screen.getByLabelText('服务地址'), 'https://model.example.test');
    await userEvent.click(screen.getByRole('button', { name: '登记服务商' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/provider config is restricted/)).not.toBeInTheDocument();
  });

  it('keeps model mutation controls hidden for model readers', async () => {
    renderPage(['model:read']);
    expect(await screen.findByRole('heading', { name: '模型与价格' })).toBeInTheDocument();
    await userEvent.click(screen.getByText('新增模型服务商或模型'));

    expect(await screen.findByText('local / qwen-2.5')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '登记服务商' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '登记模型' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '连接测试' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '删除模型' })).not.toBeInTheDocument();
    expect(mocks.createModelProvider).not.toHaveBeenCalled();
    expect(mocks.createModel).not.toHaveBeenCalled();
    expect(mocks.deleteModel).not.toHaveBeenCalled();
  });

  it('registers a provider and model and exposes credential-safe connection test', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '模型与价格' })).toBeInTheDocument();
    await userEvent.click(screen.getByText('新增模型服务商或模型'));
    await userEvent.type(screen.getByLabelText('名称'), 'remote');
    await userEvent.type(screen.getByLabelText('服务地址'), 'https://remote.example.test');
    await userEvent.type(screen.getByLabelText('凭据引用（可选）'), 'secret://model/remote');
    await userEvent.click(screen.getByRole('button', { name: '登记服务商' }));
    expect(mocks.createModelProvider).toHaveBeenCalledWith({
      name: 'remote',
      providerType: 'OPENAI_COMPATIBLE',
      endpoint: 'https://remote.example.test',
      credentialRef: 'secret://model/remote',
      enabled: true,
    });
    await userEvent.selectOptions(screen.getByLabelText('服务商'), 'provider-1');
    await userEvent.type(screen.getByLabelText('显示名称'), 'Qwen');
    await userEvent.type(screen.getByLabelText('模型 ID'), 'qwen-2.5');
    await userEvent.click(screen.getByRole('button', { name: '登记模型' }));
    expect(mocks.createModel).toHaveBeenCalledWith('provider-1', {
      name: 'Qwen',
      modelId: 'qwen-2.5',
      enabled: true,
    });
    await userEvent.click(screen.getByRole('button', { name: '连接测试' }));
    expect(mocks.testModelProviderConnection).toHaveBeenCalledWith('provider-1');
    expect(await screen.findByText(/凭据未配置/)).toBeInTheDocument();
    expect(screen.getByText('local / qwen-2.5')).toBeInTheDocument();
    expect(screen.getByText(/USD 1.2/)).toBeInTheDocument();
    expect(screen.getByText('Qwen · qwen-2.5')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '停用模型' }));
    expect(mocks.setModelEnabled).toHaveBeenCalledWith('model-1', false);
    await userEvent.click(screen.getByRole('button', { name: '删除模型' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除模型' }));
    expect(mocks.deleteModel).toHaveBeenCalledWith('model-1');
    await userEvent.click(screen.getByRole('button', { name: '停用' }));
    expect(mocks.setModelProviderEnabled).toHaveBeenCalledWith('provider-1', false);
    await userEvent.click(screen.getByRole('button', { name: '删除' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除模型服务商' }));
    expect(mocks.deleteModelProvider).toHaveBeenCalledWith('provider-1');
  });
});
