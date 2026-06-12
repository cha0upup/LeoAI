package org.leo.web.dto.platform.admin;

public final class TeamDtos {

    private TeamDtos() {
    }

    public record DeleteTeamRequest(String id) {
    }
}
