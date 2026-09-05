package io.agentteams.storage;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * Provider-neutral object storage port used by the control plane.
 */
public interface ObjectStorage {

    void upload(String objectKey, InputStream content, long contentLength, String contentType);

    InputStream download(String objectKey);

    void delete(String objectKey);

    URL presignGet(String objectKey, Duration expiry);

    URL presignPut(String objectKey, String contentType, Duration expiry);
}
