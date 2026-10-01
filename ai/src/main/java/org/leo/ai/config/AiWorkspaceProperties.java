package org.leo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 任务工作空间的容量与单次读写边界。 */
@Component
@ConfigurationProperties(prefix = "leo.ai.workspace")
public class AiWorkspaceProperties {

    private long quotaBytes = 256L * 1024 * 1024;
    private int maxFiles = 2_000;
    private long maxFileBytes = 64L * 1024 * 1024;
    private int maxWriteChars = 256 * 1024;
    private int maxReadChars = 20_000;
    private int maxSearchResults = 200;

    public long getQuotaBytes() { return quotaBytes; }
    public int getMaxFiles() { return maxFiles; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public int getMaxWriteChars() { return maxWriteChars; }
    public int getMaxReadChars() { return maxReadChars; }
    public int getMaxSearchResults() { return maxSearchResults; }

    public void setQuotaBytes(long value) { quotaBytes = Math.max(1024 * 1024L, value); }
    public void setMaxFiles(int value) { maxFiles = Math.max(10, value); }
    public void setMaxFileBytes(long value) { maxFileBytes = Math.max(1024L, value); }
    public void setMaxWriteChars(int value) { maxWriteChars = Math.max(1024, value); }
    public void setMaxReadChars(int value) { maxReadChars = Math.max(1000, value); }
    public void setMaxSearchResults(int value) { maxSearchResults = Math.max(10, value); }
}
