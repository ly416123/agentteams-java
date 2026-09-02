import { useState, type FormEvent } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createModel,
  createModelProvider,
  deleteModel,
  deleteModelProvider,
  listModels,
  listModelProviders,
  listModelPrices,
  setModelEnabled,
  setModelProviderEnabled,
  testModelProviderConnection,
  type Model,
  type ModelProvider,
} from '../../api/managementCatalog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementModelPage() {
  const queryClient = useQueryClient();
  const providers = useQuery({
    queryKey: ['model-providers'],
    queryFn: () => listModelProviders(),
  });
  const prices = useQuery({
    queryKey: ['model-prices'],
    queryFn: () => listModelPrices(),
  });
  const models = useQueries({
    queries: (providers.data ?? []).map((item) => ({
      queryKey: ['models', item.id],
      queryFn: () => listModels(item.id),
    })),
  });
  const [notice, setNotice] = useState<Notice>();
  const [deleteTarget, setDeleteTarget] = useState<
    { kind: 'provider'; item: ModelProvider } | { kind: 'model'; item: Model }
  >();
  const [provider, setProvider] = useState({
    name: '',
    providerType: 'OPENAI_COMPATIBLE',
    endpoint: '',
    credentialRef: '',
  });
  const [model, setModel] = useState({ providerId: '', name: '', modelId: '' });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['model-providers'] });
  const createProvider = useMutation({
    mutationFn: () =>
      createModelProvider({
        ...provider,
        credentialRef: provider.credentialRef || undefined,
        enabled: true,
      }),
    onSuccess: () => {
      setProvider({ name: '', providerType: 'OPENAI_COMPATIBLE', endpoint: '', credentialRef: '' });
      setNotice({ kind: 'success', text: 'Model Provider 已创建' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const createModelMutation = useMutation({
    mutationFn: () =>
      createModel(model.providerId, {
        name: model.name,
        modelId: model.modelId,
        enabled: true,
      }),
    onSuccess: () => {
      const providerId = model.providerId;
      setModel({ providerId: '', name: '', modelId: '' });
      setNotice({ kind: 'success', text: 'Model 已创建' });
      void queryClient.invalidateQueries({ queryKey: ['models', providerId] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const connectionTest = useMutation({
    mutationFn: (id: string) => testModelProviderConnection(id),
    onSuccess: (result) =>
      setNotice({ kind: 'success', text: `连接测试：${result.status} / ${result.classification}` }),
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const toggleProvider = useMutation({
    mutationFn: (item: ModelProvider) => setModelProviderEnabled(item.id, !item.enabled),
    onSuccess: (value) => {
      setNotice({ kind: 'success', text: `Provider 已${value.enabled ? '启用' : '停用'}` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const removeProvider = useMutation({
    mutationFn: (id: string) => deleteModelProvider(id),
    onSuccess: () => {
      setDeleteTarget(undefined);
      setNotice({ kind: 'success', text: 'Provider 已删除' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const toggleModel = useMutation({
    mutationFn: ({ item }: { item: Model; providerId: string }) =>
      setModelEnabled(item.id, !item.enabled),
    onSuccess: (_value, variables) => {
      setNotice({ kind: 'success', text: 'Model 状态已更新' });
      void queryClient.invalidateQueries({ queryKey: ['models', variables.providerId] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const removeModel = useMutation({
    mutationFn: ({ id }: { id: string; providerId: string }) => deleteModel(id),
    onSuccess: (_value, variables) => {
      setDeleteTarget(undefined);
      setNotice({ kind: 'success', text: 'Model 已删除' });
      void queryClient.invalidateQueries({ queryKey: ['models', variables.providerId] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submitProvider(event: FormEvent) {
    event.preventDefault();
    createProvider.mutate();
  }

  function submitModel(event: FormEvent) {
    event.preventDefault();
    createModelMutation.mutate();
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / MODELS</p>
          <h1>模型与价格</h1>
          <p className="page-subtitle">
            管理 Model Provider 和模型目录。连接测试结果仅代表当前依赖状态，不等同于生产凭据可用。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void providers.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <div className="content-grid">
        <form className="form-panel" onSubmit={submitProvider}>
          <h2>登记 Model Provider</h2>
          <label>
            名称
            <input
              value={provider.name}
              onChange={(event) => setProvider({ ...provider, name: event.target.value })}
              required
            />
          </label>
          <label>
            Provider Type
            <input
              value={provider.providerType}
              onChange={(event) => setProvider({ ...provider, providerType: event.target.value })}
              required
            />
          </label>
          <label>
            Endpoint
            <input
              type="url"
              value={provider.endpoint}
              onChange={(event) => setProvider({ ...provider, endpoint: event.target.value })}
              required
            />
          </label>
          <label>
            Credential Ref（可选）
            <input
              value={provider.credentialRef}
              onChange={(event) => setProvider({ ...provider, credentialRef: event.target.value })}
              placeholder="secret://..."
            />
          </label>
          <button
            className="button button--primary"
            type="submit"
            disabled={createProvider.isPending}
          >
            登记 Provider
          </button>
        </form>
        <form className="form-panel" onSubmit={submitModel}>
          <h2>登记 Model</h2>
          <label>
            Provider
            <select
              value={model.providerId}
              onChange={(event) => setModel({ ...model, providerId: event.target.value })}
              required
            >
              <option value="">选择 Provider</option>
              {(providers.data || []).map((item) => (
                <option value={item.id} key={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            显示名称
            <input
              value={model.name}
              onChange={(event) => setModel({ ...model, name: event.target.value })}
              required
            />
          </label>
          <label>
            Model ID
            <input
              value={model.modelId}
              onChange={(event) => setModel({ ...model, modelId: event.target.value })}
              required
            />
          </label>
          <button
            className="button button--primary"
            type="submit"
            disabled={createModelMutation.isPending}
          >
            登记 Model
          </button>
        </form>
      </div>
      {providers.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : providers.isError ? (
        <ErrorState error={providers.error} onRetry={() => void providers.refetch()} />
      ) : !providers.data?.length ? (
        <EmptyState title="暂无 Model Provider" description="登记 Provider 后可继续登记 Model。" />
      ) : (
        <div className="content-grid">
          {providers.data.map((item, index) => (
            <article className="panel" key={item.id}>
              <div className="panel-heading">
                <div>
                  <h2>{item.name}</h2>
                  <p className="muted-text">
                    {item.providerType} · {item.endpoint}
                  </p>
                </div>
                <span className="status-badge">{item.enabled ? 'ENABLED' : 'DISABLED'}</span>
              </div>
              <p className="muted-text">
                credential: {item.credentialConfigured ? '已配置（仅引用）' : '未配置'} · version{' '}
                {item.version}
              </p>
              <button
                className="button button--ghost"
                disabled={connectionTest.isPending}
                onClick={() => connectionTest.mutate(item.id)}
              >
                连接测试
              </button>
              <button
                className="button button--ghost"
                disabled={toggleProvider.isPending}
                onClick={() => toggleProvider.mutate(item)}
              >
                {item.enabled ? '停用' : '启用'}
              </button>
              <button
                className="button button--danger"
                disabled={removeProvider.isPending}
                onClick={() => setDeleteTarget({ kind: 'provider', item })}
              >
                删除
              </button>
              <div className="stack-list" aria-label={`${item.name} 的 Model`}>
                {models[index]?.isLoading ? (
                  <span className="muted-text">Model 加载中…</span>
                ) : models[index]?.data?.length ? (
                  models[index].data.map((managedModel) => (
                    <div className="stack-list__item" key={managedModel.id}>
                      <span>
                        {managedModel.name} · {managedModel.modelId}{' '}
                        <span className="muted-text">
                          {managedModel.enabled ? 'ENABLED' : 'DISABLED'} · v{managedModel.version}
                        </span>
                      </span>
                      <span>
                        <button
                          className="button button--ghost"
                          disabled={toggleModel.isPending}
                          onClick={() =>
                            toggleModel.mutate({ item: managedModel, providerId: item.id })
                          }
                        >
                          {managedModel.enabled ? '停用 Model' : '启用 Model'}
                        </button>
                        <button
                          className="button button--danger"
                          disabled={removeModel.isPending}
                          onClick={() => setDeleteTarget({ kind: 'model', item: managedModel })}
                        >
                          删除 Model
                        </button>
                      </span>
                    </div>
                  ))
                ) : (
                  <span className="muted-text">暂无 Model</span>
                )}
              </div>
            </article>
          ))}
        </div>
      )}
      <ActionConfirmModal
        open={Boolean(deleteTarget)}
        actionLabel={deleteTarget?.kind === 'model' ? '删除 Model' : '删除 Model Provider'}
        impact={
          deleteTarget?.kind === 'model'
            ? '删除后该 Model 将不能继续用于新任务，且无法从管理端恢复。'
            : '删除后该 Provider 及其关联模型将不能继续用于新任务，且无法从管理端恢复。'
        }
        onCancel={() => setDeleteTarget(undefined)}
        onConfirm={() => {
          if (!deleteTarget) return;
          if (deleteTarget.kind === 'provider') {
            removeProvider.mutate(deleteTarget.item.id);
          } else {
            removeModel.mutate({
              id: deleteTarget.item.id,
              providerId: deleteTarget.item.providerId,
            });
          }
        }}
      />
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">PRICE CATALOG</p>
            <h2>价格目录</h2>
          </div>
          <span className="muted-text">费用计算使用已生效价格版本</span>
        </div>
        {prices.isLoading ? (
          <div className="loading-block">加载价格目录…</div>
        ) : prices.isError ? (
          <ErrorState error={prices.error} onRetry={() => void prices.refetch()} />
        ) : !prices.data?.length ? (
          <EmptyState title="暂无价格记录" description="当前作用域还没有同步到价格目录。" />
        ) : (
          <div className="table-wrap">
            <table className="resource-table">
              <thead>
                <tr>
                  <th>Provider / Model</th>
                  <th>输入 / 1M Tokens</th>
                  <th>输出 / 1M Tokens</th>
                  <th>生效时间</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {prices.data.map((price) => (
                  <tr key={price.id}>
                    <td>
                      {price.provider} / {price.model}
                    </td>
                    <td>
                      {price.currency} {price.inputPricePerMillionTokens}
                    </td>
                    <td>
                      {price.currency} {price.outputPricePerMillionTokens}
                    </td>
                    <td>{new Date(price.effectiveFrom).toLocaleString('zh-CN')}</td>
                    <td>
                      {price.lifecycleStatus} · v{price.version}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
