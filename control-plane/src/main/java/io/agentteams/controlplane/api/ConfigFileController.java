package io.agentteams.controlplane.api;

import io.agentteams.controlplane.config.ConfigFileRecord;
import io.agentteams.controlplane.config.ConfigUploadService;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(ConfigUploadService.class)
@RequestMapping("/api/v1/config/snapshots/{snapshotId}/files")
public final class ConfigFileController {
    private final ConfigUploadService uploads;

    public ConfigFileController(ConfigUploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/uploads")
    public PrepareResponse prepare(@PathVariable UUID snapshotId, @RequestBody PrepareRequest request) {
        if (request == null) throw new IllegalArgumentException("upload request is required");
        ConfigUploadService.PreparedUpload prepared = uploads.prepare(snapshotId, request.path(),
                request.contentType(), request.sha256(), request.sizeBytes(),
                Duration.ofSeconds(request.expirySeconds() <= 0 ? 900 : request.expirySeconds()));
        return PrepareResponse.from(prepared);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public CompleteResponse complete(@PathVariable UUID snapshotId, @PathVariable UUID uploadId) {
        ConfigFileRecord file = uploads.complete(snapshotId, uploadId);
        return CompleteResponse.from(file);
    }

    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID snapshotId,
            @org.springframework.web.bind.annotation.RequestParam String path) {
        ConfigUploadService.ConfigFileDownload download = uploads.downloadCompleted(snapshotId, path);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.file().contentType());
        } catch (IllegalArgumentException ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(download.file().sizeBytes()))
                .body(new InputStreamResource(download.content()));
    }

    public record PrepareRequest(String path, String contentType, String sha256, long sizeBytes, long expirySeconds) { }

    public record PrepareResponse(UUID uploadId, String path, String storageKey, URL uploadUrl, Instant expiresAt) {
        static PrepareResponse from(ConfigUploadService.PreparedUpload prepared) {
            var upload = prepared.upload();
            return new PrepareResponse(upload.id(), upload.path(), upload.storageKey(), prepared.uploadUrl(),
                    upload.expiresAt());
        }
    }

    public record CompleteResponse(UUID snapshotId, String path, String storageKey, long sizeBytes, String sha256) {
        static CompleteResponse from(ConfigFileRecord file) {
            return new CompleteResponse(file.snapshotId(), file.path(), file.storageKey(), file.sizeBytes(), file.checksum());
        }
    }

}
