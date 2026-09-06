import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  createAgentSpec,
  createWorkerTemplate,
  createWorkerTemplateRevision,
  deactivateAgentSpec,
  instantiateWorkerTemplate,
  listAgentSpecs,
  listWorkerTemplates,
  publishAgentSpec,
  publishWorkerTemplateRevision,
} from '../../src/api/managementCatalog';

function client() {
  return {
    request: vi.fn().mockResolvedValue({}),
    requestText: vi.fn(),
    requestStream: vi.fn(),
  } as unknown as HttpClient & { request: ReturnType<typeof vi.fn> };
}

describe('template domain API contracts: 20 cases', () => {
  it('lists worker templates for a project', async () => {
    const http = client();
    await listWorkerTemplates('project-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/worker-templates', {
      query: { projectId: 'project-1' },
    });
  });

  it('creates an executor template', async () => {
    const http = client();
    const body = { name: 'executor', displayName: '执行节点', workerType: 'EXECUTOR' as const };
    await createWorkerTemplate('project-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/worker-templates', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('creates a leader template', async () => {
    const http = client();
    const body = { name: 'leader', displayName: '负责人节点', workerType: 'LEADER' as const };
    await createWorkerTemplate('project-1', body, http);
    expect(http.request.mock.calls[0][1].body).toEqual(body);
  });

  it('creates a template without an explicit worker type', async () => {
    const http = client();
    const body = { name: 'default-worker', displayName: '默认节点' };
    await createWorkerTemplate('project-1', body, http);
    expect(http.request.mock.calls[0][1]).toEqual({
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('creates a template revision with an actor', async () => {
    const http = client();
    const body = { specJson: '{"runtime":"QWENPAW"}', actor: 'alice' };
    await createWorkerTemplateRevision('project-1', 'template-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/worker-templates/template-1/revisions', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('creates a template revision without an actor', async () => {
    const http = client();
    const body = { specJson: '{}' };
    await createWorkerTemplateRevision('project-1', 'template-1', body, http);
    expect(http.request.mock.calls[0][1].body).toEqual(body);
  });

  it('publishes a first template revision', async () => {
    const http = client();
    await publishWorkerTemplateRevision('project-1', 'template-1', 1, 0, http);
    expect(http.request).toHaveBeenCalledWith(
      '/api/v1/worker-templates/template-1/revisions/1/publish',
      { method: 'POST', body: { expectedVersion: 0 }, query: { projectId: 'project-1' } },
    );
  });

  it('publishes a later template revision with its version', async () => {
    const http = client();
    await publishWorkerTemplateRevision('project-1', 'template-1', 4, 9, http);
    expect(http.request.mock.calls[0][1].body).toEqual({ expectedVersion: 9 });
  });

  it('instantiates a published template revision', async () => {
    const http = client();
    await instantiateWorkerTemplate('project-1', 'template-1', 2, http);
    expect(http.request).toHaveBeenCalledWith(
      '/api/v1/worker-templates/template-1/revisions/2/instances',
      { method: 'POST', query: { projectId: 'project-1' } },
    );
  });

  it('lists agent specs for the project', async () => {
    const http = client();
    await listAgentSpecs('project-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agent-specs', {
      query: { projectId: 'project-1' },
    });
  });

  it('creates an executor agent spec', async () => {
    const http = client();
    const body = {
      name: 'research',
      runtime: 'QWENPAW',
      workerType: 'EXECUTOR' as const,
      modelProvider: 'local',
      modelName: 'qwen',
      desiredState: 'RUNNING',
      spec: { mode: 'safe' },
    };
    await createAgentSpec('project-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agent-specs', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('creates a leader agent spec with a team reference', async () => {
    const http = client();
    const body = {
      name: 'leader',
      runtime: 'QWENPAW',
      workerType: 'LEADER' as const,
      modelProvider: 'local',
      modelName: 'qwen',
      teamRef: 'team-1',
      desiredState: 'RUNNING',
      spec: '{}',
    };
    await createAgentSpec('project-1', body, http);
    expect(http.request.mock.calls[0][1].body).toEqual(body);
  });

  it('creates an agent spec with an object manifest', async () => {
    const http = client();
    const body = {
      name: 'safe',
      runtime: 'QWENPAW',
      modelProvider: 'local',
      modelName: 'qwen',
      desiredState: 'STOPPED',
      spec: { limits: { cpu: '1' } },
    };
    await createAgentSpec('project-1', body, http);
    expect(http.request.mock.calls[0][1].body).toEqual(body);
  });

  it('publishes an agent spec', async () => {
    const http = client();
    await publishAgentSpec('project-1', 'spec-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agent-specs/spec-1/publish', {
      method: 'POST',
      query: { projectId: 'project-1' },
    });
  });

  it('deactivates an agent spec', async () => {
    const http = client();
    await deactivateAgentSpec('project-1', 'spec-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agent-specs/spec-1/deactivate', {
      method: 'POST',
      query: { projectId: 'project-1' },
    });
  });

  it('keeps project scope on revision creation', async () => {
    const http = client();
    await createWorkerTemplateRevision('project-a', 'template-a', { specJson: '{}' }, http);
    expect(http.request.mock.calls[0][1].query).toEqual({ projectId: 'project-a' });
  });

  it('keeps project scope on revision publication', async () => {
    const http = client();
    await publishWorkerTemplateRevision('project-a', 'template-a', 3, 2, http);
    expect(http.request.mock.calls[0][1].query).toEqual({ projectId: 'project-a' });
  });

  it('keeps project scope on instantiation', async () => {
    const http = client();
    await instantiateWorkerTemplate('project-a', 'template-a', 3, http);
    expect(http.request.mock.calls[0][1].query).toEqual({ projectId: 'project-a' });
  });

  it('uses the template id in revision paths', async () => {
    const http = client();
    await createWorkerTemplateRevision('project-1', 'template-special', { specJson: '{}' }, http);
    expect(http.request.mock.calls[0][0]).toBe(
      '/api/v1/worker-templates/template-special/revisions',
    );
  });

  it('uses the agent spec id in lifecycle paths', async () => {
    const http = client();
    await deactivateAgentSpec('project-1', 'spec-special', http);
    expect(http.request.mock.calls[0][0]).toBe('/api/v1/agent-specs/spec-special/deactivate');
  });
});
