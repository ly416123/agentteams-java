package io.agentteams.controlplane.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Validated request parameters for stable, opaque cursor pagination. */
public final class CursorPageRequest {
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_CURSOR_LENGTH = 512;

    private final String cursor;
    private final int pageSize;
    private final String sort;
    private final Direction direction;

    public CursorPageRequest(String cursor, Integer pageSize, String sort, String direction) {
        this.cursor = blankToNull(cursor);
        this.pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (this.pageSize < 1 || this.pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        this.sort = blankToDefault(sort, "updatedAt");
        if (!this.sort.equals("updatedAt") && !this.sort.equals("createdAt")
                && !this.sort.equals("name") && !this.sort.equals("id")) {
            throw new IllegalArgumentException("sort is not a stable supported field");
        }
        this.direction = Direction.parse(direction);
        if (this.cursor != null) {
            if (this.cursor.length() > MAX_CURSOR_LENGTH) {
                throw new IllegalArgumentException("cursor must be at most " + MAX_CURSOR_LENGTH + " characters");
            }
            decode(this.cursor);
        }
    }

    public String cursor() {
        return cursor;
    }

    public int pageSize() {
        return pageSize;
    }

    public String sort() {
        return sort;
    }

    public Direction direction() {
        return direction;
    }

    public Position position() {
        return cursor == null ? null : decode(cursor);
    }

    public static String encode(Instant updatedAt, UUID id) {
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(id, "id");
        String raw = updatedAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Position(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cursor is invalid", error);
        }
    }

    public record Position(Instant updatedAt, UUID id) {
        public Position {
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public enum Direction {
        ASC, DESC;

        static Direction parse(String value) {
            if (value == null || value.isBlank()) return DESC;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("direction must be asc or desc", error);
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }
}
