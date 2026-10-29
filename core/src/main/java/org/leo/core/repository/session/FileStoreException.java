package org.leo.core.repository.session;

import java.nio.file.Path;

/** A present file failed to load; absence is represented separately by null. */
public final class FileStoreException extends IllegalStateException {
    public enum Reason { READ_FAILED, INVALID_CONTENT }

    private final Reason reason;
    private final Path path;

    public FileStoreException(Reason reason, Path path, Throwable cause) {
        super((reason == Reason.INVALID_CONTENT ? "文件内容损坏: " : "读取文件失败: ") + path.getFileName(), cause);
        this.reason = reason;
        this.path = path;
    }

    public Reason getReason() { return reason; }
    public Path getPath() { return path; }
}
