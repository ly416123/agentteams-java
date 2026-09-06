import { describe, expect, it } from 'vitest';
import {
  labelForErrorCode,
  labelForRole,
  labelForStatus,
  labelForType,
  statusPresentation,
} from '../../src/i18n/statusLabels';

describe('统一中文展示字典', () => {
  it('为已知状态提供稳定 label 和 tone', () => {
    expect(labelForStatus('RUNNING')).toBe('执行中');
    expect(statusPresentation('RUNNING')).toEqual({ label: '执行中', tone: 'info' });
  });

  it('未知状态不回显内部码', () => {
    expect(labelForStatus('NEW_BACKEND_STATE')).toBe('未知状态');
    expect(statusPresentation('NEW_BACKEND_STATE')).toEqual({ label: '未知状态', tone: 'neutral' });
  });

  it('按枚举类别提供统一中文文案和安全兜底', () => {
    expect(labelForRole('OWNER')).toBe('所有者');
    expect(labelForType('ROLLOUT')).toBe('发布');
    expect(labelForErrorCode('NOT_FOUND')).toBe('资源不存在');
    expect(labelForErrorCode('NEW_ERROR_CODE')).toBe('未知错误');
  });
});
