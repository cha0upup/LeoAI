package org.leo.service.user;

import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.dao.mapper.SystemInitializationMapper;
import org.leo.service.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** One-time web setup. Claim, team, administrator and completion marker commit together. */
@Service
public class SystemInitializationService {
    private final SystemInitializationMapper mapper;
    private final UserService users;
    private final TeamService teams;
    private final PasswordPolicy passwords;

    public SystemInitializationService(SystemInitializationMapper mapper, UserService users,
                                       TeamService teams, PasswordPolicy passwords) {
        this.mapper = mapper;
        this.users = users;
        this.teams = teams;
        this.passwords = passwords;
    }

    public boolean isInitializationRequired() {
        return mapper.isInitializationRequired();
    }

    @Transactional
    public void initializeAdmin(String password, String confirmPassword) {
        if (mapper.claimEmptyInstallation() != 1) throw new AlreadyInitializedException();
        passwords.validate(password);
        if (!Objects.equals(password, confirmPassword)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        createAdminTeam();
        User admin = new User();
        admin.setUserId("admin");
        admin.setUserName("admin");
        admin.setPassword(PasswordUtil.hash(password));
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        admin.setTeamId(TeamService.ADMIN_TEAM_ID);
        admin.setStatus(1);
        admin.setLoginCount(0);
        admin.setPasswordChangeRequired(0);
        if (!users.addUser(admin)) throw new IllegalStateException("管理员初始化写入失败");
    }

    private void createAdminTeam() {
        Team team = new Team();
        team.setTeamId(TeamService.ADMIN_TEAM_ID);
        team.setTeamName(TeamService.ADMIN_TEAM_NAME);
        team.setLeaderId("admin");
        team.setDescription("系统内置管理员团队");
        team.setStatus(1);
        if (!teams.addTeam(team)) throw new IllegalStateException("管理员团队初始化写入失败");
    }

    public static final class AlreadyInitializedException extends RuntimeException {
        public AlreadyInitializedException() {
            super("系统已完成初始化，请使用已有账号登录");
        }
    }
}
