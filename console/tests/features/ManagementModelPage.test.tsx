import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementModelPage } from '../../src/features/management/ManagementModelPage';

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

function renderPage() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementModelPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management model page', () => {
  it('registers a provider and model and exposes credential-safe connection test', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '模型与价格' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('名称'), 'remote');
    await userEvent.type(screen.getByLabelText('Endpoint'), 'https://remote.example.test');
    await userEvent.type(screen.getByLabelText('Credential Ref（可选）'), 'secret://model/remote');
    await userEvent.click(screen.getByRole('button', { name: '登记 Provider' }));
    expect(mocks.createModelProvider).toHaveBeenCalledWith({
      name: 'remote',
      providerType: 'OPENAI_COMPATIBLE',
      endpoint: 'https://remote.example.test',
      credentialRef: 'secret://model/remote',
      enabled: true,
    });
    await userEvent.selectOptions(screen.getByLabelText('Provider'), 'provider-1');
    await userEvent.type(screen.getByLabelText('显示名称'), 'Qwen');
    await userEvent.type(screen.getByLabelText('Model ID'), 'qwen-2.5');
    await userEvent.click(screen.getByRole('button', { name: '登记 Model' }));
    expect(mocks.createModel).toHaveBeenCalledWith('provider-1', {
      name: 'Qwen',
      modelId: 'qwen-2.5',
      enabled: true,
    });
    await userEvent.click(screen.getByRole('button', { name: '连接测试' }));
    expect(mocks.testModelProviderConnection).toHaveBeenCalledWith('provider-1');
    expect(await screen.findByText(/CREDENTIAL_NOT_CONFIGURED/)).toBeInTheDocument();
    expect(screen.getByText('local / qwen-2.5')).toBeInTheDocument();
    expect(screen.getByText(/USD 1.2/)).toBeInTheDocument();
    expect(screen.getByText('Qwen · qwen-2.5')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '停用 Model' }));
    expect(mocks.setModelEnabled).toHaveBeenCalledWith('model-1', false);
    await userEvent.click(screen.getByRole('button', { name: '删除 Model' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除 Model' }));
    expect(mocks.deleteModel).toHaveBeenCalledWith('model-1');
    await userEvent.click(screen.getByRole('button', { name: '停用' }));
    expect(mocks.setModelProviderEnabled).toHaveBeenCalledWith('provider-1', false);
    await userEvent.click(screen.getByRole('button', { name: '删除' }));
    await userEvent.click(screen.getByRole('button', { name: '确认删除 Model Provider' }));
    expect(mocks.deleteModelProvider).toHaveBeenCalledWith('provider-1');
  });
});
