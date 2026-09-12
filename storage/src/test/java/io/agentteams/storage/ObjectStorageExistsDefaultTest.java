package io.agentteams.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ObjectStorageExistsDefaultTest {
    private static final class Present implements ObjectStorage {
        public void upload(String k, InputStream c, long l, String t) { }
        public InputStream download(String k) { return new ByteArrayInputStream(new byte[1]); }
        public void delete(String k) { }
        public URL presignGet(String k, Duration e) { return null; }
        public URL presignPut(String k, String t, Duration e) { return null; }
    }

    private static final class Absent implements ObjectStorage {
        public void upload(String k, InputStream c, long l, String t) { }
        public InputStream download(String k) { throw new ObjectStorageException("missing", null); }
        public void delete(String k) { }
        public URL presignGet(String k, Duration e) { return null; }
        public URL presignPut(String k, String t, Duration e) { return null; }
    }

    @Test
    void defaultExistsProbesViaDownload() {
        assertThat(new Present().exists("a")).isTrue();
        assertThat(new Absent().exists("a")).isFalse();
    }
}
