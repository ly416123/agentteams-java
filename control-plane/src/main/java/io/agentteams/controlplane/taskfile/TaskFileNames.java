package io.agentteams.controlplane.taskfile;

/** Mirrors ConversationFileService.sanitizeName: last path segment, control chars stripped, 255 cap. */
final class TaskFileNames {
    private TaskFileNames() { }

    static String sanitize(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isBlank()) {
            return "file";
        }
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }
}
