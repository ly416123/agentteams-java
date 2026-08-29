package io.agentteams.controlplane.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Common response envelope for management API list endpoints. */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, Instant serverTime) {
    public CursorPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be null or non-blank");
        }
        Objects.requireNonNull(serverTime, "serverTime");
    }

    public static <T> CursorPage<T> of(List<T> items, String nextCursor, boolean hasMore, Instant serverTime) {
        return new CursorPage<>(items, nextCursor, hasMore, serverTime);
    }

    public static <T> CursorPage<T> fromRows(List<T> rows, int pageSize,
            Function<T, CursorPageRequest.Position> positionOf, Instant serverTime) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(positionOf, "positionOf");
        boolean hasMore = rows.size() > pageSize;
        List<T> items = rows.subList(0, Math.min(pageSize, rows.size()));
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            CursorPageRequest.Position position = positionOf.apply(items.get(items.size() - 1));
            nextCursor = CursorPageRequest.encode(position.updatedAt(), position.id());
        }
        return new CursorPage<>(items, nextCursor, hasMore, serverTime);
    }

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor, hasMore, serverTime);
    }
}
