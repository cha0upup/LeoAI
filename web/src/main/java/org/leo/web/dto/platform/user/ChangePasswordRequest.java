package org.leo.web.dto.platform.user;

public record ChangePasswordRequest(String oldPassword, String newPassword) {
}
