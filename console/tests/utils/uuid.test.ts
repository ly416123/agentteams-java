import { afterEach, describe, expect, it, vi } from 'vitest';
import { createUuid } from '../../src/utils/uuid';

describe('UUID compatibility adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uses the native randomUUID implementation when available', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'native-uuid' });

    expect(createUuid()).toBe('native-uuid');
  });

  it('falls back to getRandomValues when randomUUID is unavailable', () => {
    vi.stubGlobal('crypto', {
      getRandomValues(bytes: Uint8Array) {
        bytes.fill(0);
        return bytes;
      },
    });

    expect(createUuid()).toBe('00000000-0000-4000-8000-000000000000');
  });
});
