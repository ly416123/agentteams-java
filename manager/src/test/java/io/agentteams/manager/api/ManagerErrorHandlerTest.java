package io.agentteams.manager.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ManagerErrorHandlerTest {
    @Test
    void mapsMultipartTooLargeTo413FileTooLarge() {
        // multipart 在 DispatcherServlet.checkMultipart（handler mapping 之前）解析，
        // controller 局部 @ExceptionHandler 不参与，只有全局 advice 能接住超限异常。
        ResponseEntity<ManagerErrorHandler.ErrorResponse> response =
                new ManagerErrorHandler().multipartTooLarge(
                        new MaxUploadSizeExceededException(50L * 1024 * 1024),
                        new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_TOO_LARGE");
    }
}
