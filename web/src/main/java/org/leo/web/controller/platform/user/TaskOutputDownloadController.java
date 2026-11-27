package org.leo.web.controller.platform.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.leo.web.util.ControllerUtil;
import org.leo.web.util.FileDownloadResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

@RestController
public class TaskOutputDownloadController {
    @GetMapping("/platform/user/file/download")
    public void download(HttpServletRequest request,
                         @RequestParam("path") String relativePath,
                         HttpServletResponse response) {
        try {
            User user = ControllerUtil.getCurrentUser(request);
            if (user == null) {
                response.setStatus(401);
                return;
            }
            Path target = findTaskOutput(user.getUserId(), relativePath);
            if (target == null) {
                response.setStatus(404);
                return;
            }
            FileDownloadResponse.write(target, target.getFileName().toString(), response);
        } catch (NoSuchFileException e) {
            response.setStatus(404);
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    private static Path findTaskOutput(String userId, String path) {
        Path base = AgentNodeSessionWorkDirUtil.getUserWorkspaceDir(userId).toPath().toAbsolutePath().normalize();
        String relative = path.trim().replace('\\', '/');
        if (relative.startsWith("/")) relative = relative.substring(1);
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("非法任务文件路径");

        Path parts = base.relativize(target);
        if (parts.getNameCount() == 0 || !"ai-tasks".equals(parts.getName(0).toString())) return null;
        if (parts.getNameCount() < 4 || !"files".equals(parts.getName(2).toString())) {
            throw new IllegalArgumentException("只允许下载任务输出文件");
        }

        Path current = base;
        if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("任务文件路径含符号链接");
        for (Path part : parts) {
            current = current.resolve(part);
            if (part.toString().startsWith(".") || Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("无效任务文件路径");
            }
        }
        return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? target : null;
    }
}
