package org.leo.web.controller.platform;

import org.leo.core.util.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Build metadata for the running server, without exposing environment details. */
@RestController
@RequestMapping("/platform/application")
public class ApplicationInfoController {
    private final ObjectProvider<BuildProperties> buildProperties;

    public ApplicationInfoController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        BuildProperties build = buildProperties.getIfAvailable();
        var info = new ApplicationInfo(build == null ? null : build.getVersion(),
                build == null || build.getTime() == null ? null : build.getTime().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(info));
    }

    public record ApplicationInfo(String version, String buildTime) { }
}
