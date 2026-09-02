import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createMcpServer,
  deleteMcpServer,
  getMcpDiscovery,
  listMcpServers,
  testMcpConnection,
  updateMcpServer,
  updateMcpServerHealth,
  type McpServer,
} from '../../api/managementCatalog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementMcpPage() {
  const queryClient = useQueryClient();
  const servers = useQuery({ queryKey: ['mcp-servers'], queryFn: () => listMcpServers() });
  const [notice, setNotice] = useState<Notice>();
  const [deleteTarget, setDeleteTarget] = useState<McpServer>();
  const [editingTarget, setEditingTarget] = useState<McpServer>();
  const [discoveryTarget, setDiscoveryTarget] = useState<string>();
  const [connectionResults, setConnectionResults] = useState<Record<string, string>>({});
  const [form, setForm] = useState({
    name: '',
    transport: 'STREAMABLE_HTTP',
    endpoint: '',
    credentialRef: '',
    enabled: true,
  });
  const discovery = useQuery({
    queryKey: ['mcp-discovery', discoveryTarget],
    queryFn: () => getMcpDiscovery(discoveryTarget || ''),
    enabled: Boolean(discoveryTarget),
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['mcp-servers'] });
  const create = useMutation({
    mutationFn: () =>
      createMcpServer({
        ...form,
        credentialRef: form.credentialRef || undefined,
      }),
    onSuccess: () => {
      setForm({
        name: '',
        transport: 'STREAMABLE_HTTP',
        endpoint: '',
        credentialRef: '',
        enabled: true,
      });
      setNotice({ kind: 'success', text: 'MCP Server 已创建' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const update = useMutation({
    mutationFn: () => {
      if (!editingTarget) throw new Error('未选择 MCP Server');
      return updateMcpServer(editingTarget.id, {
        ...form,
        credentialRef: form.credentialRef || undefined,
        expectedVersion: editingTarget.version,
      });
    },
    onSuccess: () => {
      setEditingTarget(undefined);
      setForm({
        name: '',
        transport: 'STREAMABLE_HTTP',
        endpoint: '',
        credentialRef: '',
        enabled: true,
      });
      setNotice({ kind: 'success', text: 'MCP Server 已更新' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const health = useMutation({
    mutationFn: (id: string) =>
      updateMcpServerHealth(id, {
        healthStatus: 'HEALTHY',
        lastCheckedAt: new Date().toISOString(),
      }),
    onSuccess: () => {
      setNotice({ kind: 'success', text: 'MCP 健康状态已更新' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const connection = useMutation({
    mutationFn: (id: string) => testMcpConnection(id),
    onSuccess: (result, id) => {
      setConnectionResults((current) => ({
        ...current,
        [id]: `${result.category} · ${result.latencyMillis} ms`,
      }));
      setNotice({ kind: 'success', text: `连接测试完成：${result.status}` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const remove = useMutation({
    mutationFn: (id: string) => deleteMcpServer(id),
    onSuccess: () => {
      setDeleteTarget(undefined);
      setNotice({ kind: 'success', text: 'MCP Server 已删除' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (editingTarget) update.mutate();
    else create.mutate();
  }

  function edit(server: McpServer) {
    setEditingTarget(server);
    setForm({
      name: server.name,
      transport: server.transport === 'SSE' ? 'SSE' : 'STREAMABLE_HTTP',
      endpoint: server.endpoint,
      credentialRef: '',
      enabled: server.enabled,
    });
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / MCP</p>
          <h1>MCP Servers</h1>
          <p className="page-subtitle">
            管理 MCP Server 连接和健康状态。凭据只登记引用，不在 Console 保存或展示 Secret 明文。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void servers.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <form className="panel form-panel" onSubmit={submit}>
        <h2>{editingTarget ? '编辑 MCP Server' : '登记 MCP Server'}</h2>
        <div className="form-grid">
          <label>
            名称
            <input
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
              required
            />
          </label>
          <label>
            Transport
            <select
              value={form.transport}
              onChange={(event) => setForm({ ...form, transport: event.target.value })}
            >
              <option value="STREAMABLE_HTTP">HTTP</option>
              <option value="SSE">SSE</option>
            </select>
          </label>
          <label>
            Endpoint
            <input
              type="url"
              value={form.endpoint}
              onChange={(event) => setForm({ ...form, endpoint: event.target.value })}
              required
            />
          </label>
          <label>
            Credential Ref（可选）
            <input
              value={form.credentialRef}
              onChange={(event) => setForm({ ...form, credentialRef: event.target.value })}
              placeholder="secret://..."
            />
          </label>
        </div>
        <button
          className="button button--primary"
          type="submit"
          disabled={create.isPending || update.isPending}
        >
          {create.isPending || update.isPending
            ? '保存中…'
            : editingTarget
              ? '保存 MCP Server'
              : '登记 MCP Server'}
        </button>
        {editingTarget && (
          <button
            className="button button--ghost"
            type="button"
            onClick={() => {
              setEditingTarget(undefined);
              setForm({
                name: '',
                transport: 'STREAMABLE_HTTP',
                endpoint: '',
                credentialRef: '',
                enabled: true,
              });
            }}
          >
            取消编辑
          </button>
        )}
      </form>

      {servers.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : servers.isError ? (
        <ErrorState error={servers.error} onRetry={() => void servers.refetch()} />
      ) : !servers.data?.length ? (
        <EmptyState title="暂无 MCP Server" description="登记后可查看连接状态和凭据引用状态。" />
      ) : (
        <div className="content-grid">
          {servers.data.map((server) => (
            <article className="panel" key={server.id}>
              <div className="panel-heading">
                <div>
                  <h2>{server.name}</h2>
                  <p className="muted-text">
                    {server.transport} · {server.endpoint}
                  </p>
                </div>
                <span className="status-badge">{server.healthStatus}</span>
              </div>
              <p className="muted-text">
                credential: {server.credentialConfigured ? '已配置（仅引用）' : '未配置'} · version{' '}
                {server.version}
              </p>
              {connectionResults[server.id] && (
                <p className="success-text">最近连接测试：{connectionResults[server.id]}</p>
              )}
              {discoveryTarget === server.id && discovery.data && (
                <p className="muted-text">
                  Discovery：{discovery.data.status} · {discovery.data.healthyInstances}/
                  {discovery.data.freshInstances} 个实例 ·{' '}
                  {discovery.data.toolsDigest || '无工具摘要'}
                </p>
              )}
              <button
                className="button button--ghost"
                disabled={connection.isPending}
                onClick={() => connection.mutate(server.id)}
              >
                连接测试
              </button>
              <button
                className="button button--ghost"
                onClick={() => setDiscoveryTarget(server.id)}
              >
                查看 Discovery
              </button>
              <button className="button button--ghost" onClick={() => edit(server)}>
                编辑
              </button>
              <button
                className="button button--ghost"
                disabled={health.isPending}
                onClick={() => health.mutate(server.id)}
              >
                记录健康检查
              </button>
              <button
                className="button button--danger"
                disabled={remove.isPending}
                onClick={() => setDeleteTarget(server)}
              >
                删除
              </button>
            </article>
          ))}
        </div>
      )}
      <ActionConfirmModal
        open={Boolean(deleteTarget)}
        actionLabel="删除 MCP Server"
        impact="删除后将停止该连接的后续发现和运行时使用，且无法从管理端恢复。"
        onCancel={() => setDeleteTarget(undefined)}
        onConfirm={() => {
          if (!deleteTarget) return;
          remove.mutate(deleteTarget.id);
        }}
      />
    </div>
  );
}
