package io.agentteams.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ApiErrorHandlerTest {
    @Test
    void multipartTooLargeMapsTo413() {
        ApiErrorHandler handler = new ApiErrorHandler();
        var response = handler.multipartTooLarge(
                new MaxUploadSizeExceededException(50L * 1024 * 1024));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }
}
