package org.leo.web.config;

import org.leo.service.transfer.DownloadEngineService;
import org.leo.service.transfer.UploadEngineService;
import org.leo.service.sql.SqlExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上传/下载引擎已终结任务清理器。
 *
 * <p>定期扫描上传、下载和 SQL 导出服务中的
 * 已完成、失败或取消超过 30 分钟的任务，将其从内存中移除；下载任务的
 * 断点元数据和残留临时文件也会同步清理，已提交的下载文件继续保留。
 */
@Component
public class TransferEngineCleanup {

    private static final Logger log = LoggerFactory.getLogger(TransferEngineCleanup.class);

    private final UploadEngineService uploadEngineService;
    private final DownloadEngineService downloadEngineService;
    private final SqlExportService sqlExportService;

    public TransferEngineCleanup(UploadEngineService uploadEngineService,
                                 DownloadEngineService downloadEngineService,
                                 SqlExportService sqlExportService) {
        this.uploadEngineService = uploadEngineService;
        this.downloadEngineService = downloadEngineService;
        this.sqlExportService = sqlExportService;
    }

    /**
     * 每 10 分钟扫描一次，清理已终结超过保留时长的上传、下载和 SQL 导出任务。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 10 * 60 * 1000L)
    public void evictFinishedTasks() {
        int uploadEvicted = uploadEngineService.evictFinished();
        int downloadEvicted = downloadEngineService.evictFinished();
        int sqlExportEvicted = sqlExportService.evictFinished();
        if (uploadEvicted > 0 || downloadEvicted > 0 || sqlExportEvicted > 0) {
            log.info("[TransferEngine] 清理已终结任务: upload={}, download={}, sqlExport={}",
                    uploadEvicted, downloadEvicted, sqlExportEvicted);
        }
    }
}
