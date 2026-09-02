import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementSkillPage } from '../../src/features/management/ManagementSkillPage';

const mocks = vi.hoisted(() => ({
  listSkills: vi.fn().mockResolvedValue([
    {
      id: 'skill-1',
      name: 'reporting',
      displayName: 'Reporting',
      description: 'Generate reports',
      visibility: 'PRIVATE',
      lifecycle: 'DRAFT',
      version: 0,
    },
  ]),
  createSkill: vi.fn().mockResolvedValue({ id: 'skill-2' }),
  createSkillVersion: vi.fn().mockResolvedValue({
    id: 'version-1',
    skillId: 'skill-1',
    version: '1.0.0',
    digest: 'sha256:abc',
    lifecycle: 'DRAFT',
    reviewStatus: 'PENDING',
    packageUploadStatus: 'NOT_STARTED',
    recordVersion: 0,
  }),
  prepareSkillPackageUpload: vi.fn().mockResolvedValue({
    skillId: 'skill-1',
    versionId: 'version-1',
    storageKey: 'skills/skill-1/versions/version-1/package.tar.gz',
    sizeBytes: 4,
    sha256: 'a'.repeat(64),
    uploadUrl: 'https://minio.test/upload',
    downloadUrl: 'https://minio.test/download',
  }),
  completeSkillPackageUpload: vi.fn().mockResolvedValue({
    id: 'version-1',
    skillId: 'skill-1',
    version: '1.0.0',
    lifecycle: 'DRAFT',
    reviewStatus: 'APPROVED',
    packageUploadStatus: 'COMPLETED',
    recordVersion: 1,
  }),
  reviewSkillVersion: vi.fn().mockResolvedValue({
    id: 'version-1',
    skillId: 'skill-1',
    version: '1.0.0',
    lifecycle: 'DRAFT',
    reviewStatus: 'APPROVED',
    packageUploadStatus: 'NOT_STARTED',
    recordVersion: 1,
  }),
  publishSkillVersion: vi.fn().mockResolvedValue({
    id: 'version-1',
    skillId: 'skill-1',
    version: '1.0.0',
    lifecycle: 'PUBLISHED',
    reviewStatus: 'APPROVED',
    packageUploadStatus: 'COMPLETED',
    recordVersion: 2,
  }),
  disableSkillVersion: vi.fn().mockResolvedValue({
    id: 'version-1',
    skillId: 'skill-1',
    version: '1.0.0',
    lifecycle: 'DISABLED',
    reviewStatus: 'APPROVED',
    packageUploadStatus: 'COMPLETED',
    recordVersion: 3,
  }),
}));

vi.mock('../../src/api/managementCatalog', () => ({ ...mocks }));

function renderPage() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementSkillPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management skill page', () => {
  it('creates a skill and version, then records review without pretending package publish succeeded', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Skills' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('内部名称'), 'analytics');
    await userEvent.type(screen.getByLabelText('显示名称'), 'Analytics');
    await userEvent.click(screen.getByRole('button', { name: '创建 Skill' }));
    expect(mocks.createSkill).toHaveBeenCalledWith({
      name: 'analytics',
      displayName: 'Analytics',
      description: '',
      visibility: 'PRIVATE',
    });

    await userEvent.selectOptions(screen.getByLabelText('Skill'), 'skill-1');
    await userEvent.type(screen.getByLabelText('版本'), '1.0.0');
    await userEvent.type(screen.getByLabelText('Digest'), 'sha256:abc');
    fireEvent.change(screen.getByLabelText('Manifest JSON'), {
      target: { value: '{"name":"reporting"}' },
    });
    await userEvent.click(screen.getByRole('button', { name: '创建版本' }));
    expect(mocks.createSkillVersion).toHaveBeenCalledWith('skill-1', {
      version: '1.0.0',
      digest: 'sha256:abc',
      manifest: { name: 'reporting' },
      visibility: 'PRIVATE',
    });
    await userEvent.click(await screen.findByRole('button', { name: '审核通过' }));
    expect(mocks.reviewSkillVersion).toHaveBeenCalledWith('skill-1', 'version-1', 'APPROVED');
    expect(await screen.findByText('已审核；仍需完成制品上传后才能发布')).toBeInTheDocument();
  });

  it('publishes and disables a reviewed version only after package completion', async () => {
    mocks.createSkillVersion.mockResolvedValueOnce({
      id: 'version-1',
      skillId: 'skill-1',
      version: '1.0.0',
      digest: 'sha256:abc',
      lifecycle: 'DRAFT',
      reviewStatus: 'APPROVED',
      packageUploadStatus: 'COMPLETED',
      recordVersion: 1,
    });
    renderPage();
    await screen.findByRole('option', { name: /Reporting/ });
    await userEvent.selectOptions(screen.getByLabelText('Skill'), 'skill-1');
    await userEvent.type(screen.getByLabelText('版本'), '1.0.0');
    await userEvent.type(screen.getByLabelText('Digest'), 'sha256:abc');
    await userEvent.click(screen.getByRole('button', { name: '创建版本' }));
    await userEvent.click(await screen.findByRole('button', { name: '发布版本' }));
    expect(mocks.publishSkillVersion).toHaveBeenCalledWith('skill-1', 'version-1');
    await userEvent.click(await screen.findByRole('button', { name: '停用版本' }));
    expect(mocks.disableSkillVersion).toHaveBeenCalledWith('skill-1', 'version-1');
  });

  it('uploads a selected package through the presigned URL and completes it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200 }));
    vi.stubGlobal('crypto', {
      subtle: { digest: vi.fn().mockResolvedValue(new Uint8Array(32).buffer) },
    });
    mocks.createSkillVersion.mockResolvedValueOnce({
      id: 'version-1',
      skillId: 'skill-1',
      version: '1.0.0',
      digest: 'sha256:abc',
      lifecycle: 'DRAFT',
      reviewStatus: 'APPROVED',
      packageUploadStatus: 'NOT_STARTED',
      recordVersion: 0,
    });
    renderPage();
    await screen.findByRole('option', { name: /Reporting/ });
    await userEvent.selectOptions(screen.getByLabelText('Skill'), 'skill-1');
    await userEvent.type(screen.getByLabelText('版本'), '1.0.0');
    await userEvent.type(screen.getByLabelText('Digest'), 'sha256:abc');
    await userEvent.click(screen.getByRole('button', { name: '创建版本' }));
    const file = new File(['test'], 'skill.tar.gz', { type: 'application/gzip' });
    await userEvent.upload(screen.getByLabelText('Skill package'), file);
    const uploadButton = await screen.findByRole('button', { name: '上传制品' });
    expect(uploadButton).toBeEnabled();
    await userEvent.click(uploadButton);
    expect(await screen.findByText('制品上传已完成')).toBeInTheDocument();

    expect(mocks.prepareSkillPackageUpload).toHaveBeenCalledWith('skill-1', 'version-1', {
      sizeBytes: 4,
      contentType: 'application/gzip',
      sha256: expect.stringMatching(/^[0-9a-f]{64}$/),
    });
    expect(globalThis.fetch).toHaveBeenCalledWith(
      'https://minio.test/upload',
      expect.objectContaining({ method: 'PUT', body: file }),
    );
    expect(mocks.completeSkillPackageUpload).toHaveBeenCalledWith('skill-1', 'version-1');
  });
});
