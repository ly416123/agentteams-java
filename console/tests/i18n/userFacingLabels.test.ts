import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const sourceFiles = [
  'src/components/ActionConfirmModal.tsx',
  'src/components/VersionConflictModal.tsx',
  'src/features/login/LoginPage.tsx',
  'src/features/overview/OverviewPage.tsx',
  'src/features/projects/ConsoleEntryPage.tsx',
  'src/features/projects/ManagementProjectPage.tsx',
  'src/features/projects/ProjectSwitcher.tsx',
  'src/features/tasks/TaskPage.tsx',
  'src/features/teams/TeamListPage.tsx',
  'src/features/workers/WorkerListPage.tsx',
  'src/features/management/ManagementOrganizationPage.tsx',
  'src/features/management/ManagementIntegrationPage.tsx',
  'src/features/management/ManagementSkillPage.tsx',
  'src/features/management/ManagementMemoryPage.tsx',
];

const legacyUserFacingLabels = [
  '<h1>Tasks</h1>',
  '<h1>Teams</h1>',
  '<h1>Workers</h1>',
  'Project 管理',
  'Organization 与 Tenant',
  'Integrations 与 Credentials',
  '<h1>Skills</h1>',
  'Memory 治理',
  'CONFIRM ACTION',
  'VERSION CONFLICT',
  'SECURE ACCESS',
  'PROJECT OVERVIEW',
  'RESOURCE / TASKS',
  'RESOURCE / TEAMS',
  'RESOURCE / WORKERS',
];

describe('管理界面中文用户文案', () => {
  it('不再渲染已收敛的英文资源名和英文 eyebrow', () => {
    const source = sourceFiles
      .map((file) => readFileSync(resolve(process.cwd(), file), 'utf8'))
      .join('\n');

    for (const label of legacyUserFacingLabels) {
      expect(source).not.toContain(label);
    }
  });
});
