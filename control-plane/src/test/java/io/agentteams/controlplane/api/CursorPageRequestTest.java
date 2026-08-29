package io.agentteams.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorPageRequestTest {
    @Test
    void appliesSafeDefaultsAndDecodesStableCursor() {
        UUID id = UUID.randomUUID();
        String cursor = CursorPageRequest.encode(Instant.parse("2026-08-29T00:00:00.123456789Z"), id);

        CursorPageRequest request = new CursorPageRequest(cursor, null, null, null);

        assertThat(request.pageSize()).isEqualTo(50);
        assertThat(request.sort()).isEqualTo("updatedAt");
        assertThat(request.direction()).isEqualTo(CursorPageRequest.Direction.DESC);
        assertThat(request.position().id()).isEqualTo(id);
        assertThat(request.position().updatedAt()).isEqualTo(Instant.parse("2026-08-29T00:00:00.123456789Z"));
    }

    @Test
    void rejectsSortThatTheStableCursorQueriesDoNotImplement() {
        assertThatThrownBy(() -> new CursorPageRequest(null, 20, "name", "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort");
    }

    @Test
    void rejectsInvalidPageSizeCursorAndSort() {
        assertThatThrownBy(() -> new CursorPageRequest("", 201, "updatedAt", "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize");
        assertThatThrownBy(() -> new CursorPageRequest("x".repeat(513), 20, "updatedAt", "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> new CursorPageRequest(null, 20, "secret", "desc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sort");
        assertThatThrownBy(() -> new CursorPageRequest(null, 20, "updatedAt", "sideways"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direction");
    }
}
