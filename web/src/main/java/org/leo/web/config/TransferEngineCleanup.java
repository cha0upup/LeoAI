package org.leo.web.config;

import org.leo.service.DownloadEngineService;
import org.leo.service.UploadEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上传/下载引擎已终结任务清理器。
 *
 * <p>定期扫描 {@link UploadEngineService} 和 {@link DownloadEngineService} 中
 * 已完成、失败或取消超过 30 分钟的任务，将其从内存中移除，防止长时间运行后内存泄漏。
 */
@Component
public class TransferEngineCleanup {

    private static final Logger log = LoggerFactory.getLogger(TransferEngineCleanup.class);

    private final UploadEngineService uploadEngineService;
    private final DownloadEngineService downloadEngineService;

    public TransferEngineCleanup(UploadEngineService uploadEngineService,
                                 DownloadEngineService downloadEngineService) {
        this.uploadEngineService = uploadEngineService;
        this.downloadEngineService = downloadEngineService;
    }

    /**
     * 每 10 分钟扫描一次，清理已终结超过保留时长的上传/下载任务。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 10 * 60 * 1000L)
    public void evictFinishedTasks() {
        int uploadEvicted = uploadEngineService.evictFinished();
        int downloadEvicted = downloadEngineService.evictFinished();
        if (uploadEvicted > 0 || downloadEvicted > 0) {
            log.info("[TransferEngine] 清理已终结任务: upload={}, download={}", uploadEvicted, downloadEvicted);
        }
    }
}
