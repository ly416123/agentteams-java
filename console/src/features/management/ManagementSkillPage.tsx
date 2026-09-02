import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createSkill,
  createSkillVersion,
  completeSkillPackageUpload,
  disableSkillVersion,
  listSkills,
  prepareSkillPackageUpload,
  publishSkillVersion,
  reviewSkillVersion,
  type SkillVersion,
} from '../../api/managementCatalog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

function readFileAsArrayBuffer(file: File): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (reader.result instanceof ArrayBuffer) resolve(reader.result);
      else reject(new Error('无法读取 Skill 制品文件'));
    };
    reader.onerror = () => reject(new Error('无法读取 Skill 制品文件'));
    reader.readAsArrayBuffer(file);
  });
}

export function ManagementSkillPage() {
  const queryClient = useQueryClient();
  const skills = useQuery({ queryKey: ['skills'], queryFn: () => listSkills() });
  const [notice, setNotice] = useState<Notice>();
  const [skill, setSkill] = useState({
    name: '',
    displayName: '',
    description: '',
    visibility: 'PRIVATE',
  });
  const [version, setVersion] = useState({
    skillId: '',
    version: '',
    digest: '',
    manifest: '{}',
    visibility: 'PRIVATE',
  });
  const [lastVersion, setLastVersion] = useState<SkillVersion>();
  const [packageFile, setPackageFile] = useState<File>();
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['skills'] });
  const createSkillMutation = useMutation({
    mutationFn: () => createSkill(skill),
    onSuccess: () => {
      setSkill({ name: '', displayName: '', description: '', visibility: 'PRIVATE' });
      setNotice({ kind: 'success', text: 'Skill 已创建' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const createVersionMutation = useMutation({
    mutationFn: () => {
      let manifest: unknown;
      try {
        manifest = JSON.parse(version.manifest);
      } catch {
        throw new Error('Manifest JSON 格式无效');
      }
      return createSkillVersion(version.skillId, {
        version: version.version,
        digest: version.digest,
        manifest,
        visibility: version.visibility,
      });
    },
    onSuccess: (value) => {
      setLastVersion(value);
      setNotice({ kind: 'success', text: `Skill 版本 ${value.version} 已创建` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const reviewMutation = useMutation({
    mutationFn: (status: 'APPROVED' | 'REJECTED') =>
      reviewSkillVersion(lastVersion?.skillId || '', lastVersion?.id || '', status),
    onSuccess: (value) => {
      setLastVersion(value);
      setNotice({
        kind: 'success',
        text:
          value.reviewStatus === 'APPROVED' && value.packageUploadStatus !== 'COMPLETED'
            ? '已审核；仍需完成制品上传后才能发布'
            : `版本审核状态已更新为 ${value.reviewStatus}`,
      });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const publishMutation = useMutation({
    mutationFn: () => publishSkillVersion(lastVersion?.skillId || '', lastVersion?.id || ''),
    onSuccess: (value) => {
      setLastVersion(value);
      setNotice({ kind: 'success', text: `Skill 版本 ${value.version} 已发布` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const disableMutation = useMutation({
    mutationFn: () => disableSkillVersion(lastVersion?.skillId || '', lastVersion?.id || ''),
    onSuccess: (value) => {
      setLastVersion(value);
      setNotice({ kind: 'success', text: `Skill 版本 ${value.version} 已停用` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const uploadMutation = useMutation({
    mutationFn: async () => {
      if (!lastVersion || !packageFile) throw new Error('请选择 Skill 制品文件');
      if (!globalThis.crypto?.subtle) throw new Error('当前浏览器不支持 SHA-256 校验');
      const digest = await globalThis.crypto.subtle.digest(
        'SHA-256',
        await readFileAsArrayBuffer(packageFile),
      );
      const sha256 = Array.from(new Uint8Array(digest), (byte) =>
        byte.toString(16).padStart(2, '0'),
      ).join('');
      const upload = await prepareSkillPackageUpload(lastVersion.skillId, lastVersion.id, {
        sizeBytes: packageFile.size,
        sha256,
        contentType: packageFile.type || 'application/gzip',
      });
      const response = await fetch(upload.uploadUrl, {
        method: 'PUT',
        headers: { 'Content-Type': packageFile.type || 'application/gzip' },
        body: packageFile,
      });
      if (!response.ok) throw new Error(`制品上传失败（HTTP ${response.status}）`);
      return completeSkillPackageUpload(lastVersion.skillId, lastVersion.id);
    },
    onSuccess: (value) => {
      setLastVersion(value);
      setPackageFile(undefined);
      setNotice({ kind: 'success', text: '制品上传已完成' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submitSkill(event: FormEvent) {
    event.preventDefault();
    createSkillMutation.mutate();
  }

  function submitVersion(event: FormEvent) {
    event.preventDefault();
    createVersionMutation.mutate();
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / SKILLS</p>
          <h1>Skills</h1>
          <p className="page-subtitle">
            管理 Skill 目录、版本和审核。制品上传完成且安全审核通过后才允许发布。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void skills.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <div className="content-grid">
        <form className="form-panel" onSubmit={submitSkill}>
          <h2>创建 Skill</h2>
          <label>
            内部名称
            <input
              value={skill.name}
              onChange={(event) => setSkill({ ...skill, name: event.target.value })}
              required
            />
          </label>
          <label>
            显示名称
            <input
              value={skill.displayName}
              onChange={(event) => setSkill({ ...skill, displayName: event.target.value })}
              required
            />
          </label>
          <label>
            描述
            <textarea
              rows={3}
              value={skill.description}
              onChange={(event) => setSkill({ ...skill, description: event.target.value })}
            />
          </label>
          <label>
            可见性
            <select
              value={skill.visibility}
              onChange={(event) => setSkill({ ...skill, visibility: event.target.value })}
            >
              <option>PRIVATE</option>
              <option>PUBLIC</option>
            </select>
          </label>
          <button
            className="button button--primary"
            type="submit"
            disabled={createSkillMutation.isPending}
          >
            创建 Skill
          </button>
        </form>

        <form className="form-panel" onSubmit={submitVersion}>
          <h2>创建 Skill 版本</h2>
          <label htmlFor="skill-version-skill">Skill</label>
          <select
            id="skill-version-skill"
            value={version.skillId}
            onChange={(event) => setVersion({ ...version, skillId: event.target.value })}
            required
          >
            <option value="">选择 Skill</option>
            {(skills.data || []).map((item) => (
              <option value={item.id} key={item.id}>
                {item.displayName} ({item.name})
              </option>
            ))}
          </select>
          <label htmlFor="skill-version-number">版本</label>
          <input
            id="skill-version-number"
            value={version.version}
            onChange={(event) => setVersion({ ...version, version: event.target.value })}
            required
          />
          <label htmlFor="skill-version-digest">Digest</label>
          <input
            id="skill-version-digest"
            value={version.digest}
            onChange={(event) => setVersion({ ...version, digest: event.target.value })}
            required
          />
          <label htmlFor="skill-version-manifest">Manifest JSON</label>
          <textarea
            id="skill-version-manifest"
            rows={5}
            value={version.manifest}
            onChange={(event) => setVersion({ ...version, manifest: event.target.value })}
            required
          />
          <button
            className="button button--primary"
            type="submit"
            disabled={createVersionMutation.isPending}
          >
            创建版本
          </button>
          {lastVersion && (
            <div className="info-box">
              <div>
                v{lastVersion.version} · {lastVersion.reviewStatus} · package{' '}
                {lastVersion.packageUploadStatus}
              </div>
              <div className="form-actions">
                <button
                  className="button button--small"
                  type="button"
                  disabled={reviewMutation.isPending}
                  onClick={() => reviewMutation.mutate('APPROVED')}
                >
                  审核通过
                </button>
                <button
                  className="button button--small button--ghost"
                  type="button"
                  disabled={reviewMutation.isPending}
                  onClick={() => reviewMutation.mutate('REJECTED')}
                >
                  驳回
                </button>
                <button
                  className="button button--small button--primary"
                  type="button"
                  disabled={
                    publishMutation.isPending ||
                    lastVersion.lifecycle === 'PUBLISHED' ||
                    lastVersion.reviewStatus !== 'APPROVED' ||
                    lastVersion.packageUploadStatus !== 'COMPLETED'
                  }
                  onClick={() => publishMutation.mutate()}
                >
                  发布版本
                </button>
                <button
                  className="button button--small button--ghost"
                  type="button"
                  disabled={disableMutation.isPending || lastVersion.lifecycle !== 'PUBLISHED'}
                  onClick={() => disableMutation.mutate()}
                >
                  停用版本
                </button>
              </div>
              <label htmlFor="skill-package-file">
                Skill package
                <input
                  id="skill-package-file"
                  type="file"
                  accept=".tar.gz,.tgz,application/gzip,application/x-gzip"
                  onChange={(event) => setPackageFile(event.target.files?.[0])}
                />
              </label>
              <button
                className="button button--small"
                type="button"
                disabled={uploadMutation.isPending || !packageFile}
                onClick={() => uploadMutation.mutate()}
              >
                {uploadMutation.isPending ? '上传中…' : '上传制品'}
              </button>
              <p className="muted-text">制品尚未完成上传时，发布操作会被后端拒绝。</p>
            </div>
          )}
        </form>
      </div>

      {skills.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : skills.isError ? (
        <ErrorState error={skills.error} onRetry={() => void skills.refetch()} />
      ) : !skills.data?.length ? (
        <EmptyState title="暂无 Skill" description="创建 Skill 后可继续登记版本和审核。" />
      ) : (
        <div className="content-grid">
          {skills.data.map((item) => (
            <article className="panel" key={item.id}>
              <div className="panel-heading">
                <div>
                  <h2>{item.displayName}</h2>
                  <p className="muted-text">
                    {item.name} · {item.visibility}
                  </p>
                </div>
                <span className="status-badge">{item.lifecycle}</span>
              </div>
              <p className="muted-text">
                {item.description || '暂无描述'} · version {item.version}
              </p>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
