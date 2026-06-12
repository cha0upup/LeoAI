package org.leo.web.init;

import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动后执行内置数据初始化。
 *
 * <p>初始化内容：
 * <ol>
 *   <li>内置 admin 用户（用户名: admin，默认密码: 54ikun，以 MD5 存储）。</li>
 *   <li>内置 adminteam 团队，admin 为队长。</li>
 * </ol>
 *
 * <p>若上述数据已存在，则跳过，不会重复创建或覆盖。
 * 初始化顺序：先创建 admin 用户（不设 teamId），再创建 adminteam，再回写 admin 的 teamId，
 * 避免循环依赖（用户引用团队，团队引用用户）。
 */
@Component
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ADMIN_USER_ID   = "admin";
    private static final String ADMIN_USER_NAME = "admin";
    private static final String ADMIN_PASSWORD  = "54ikun";   // 明文，存储时自动 MD5
    private static final String ADMIN_TEAM_ID   = "adminteam";
    private static final String ADMIN_TEAM_NAME = TeamService.ADMIN_TEAM_NAME; // "adminteam"

    private final UserService userService;
    private final TeamService teamService;

    public DataInitializer(UserService userService, TeamService teamService) {
        this.userService = userService;
        this.teamService = teamService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        boolean adminCreated = initAdminUser();
        boolean teamCreated  = initAdminTeam();

        // 若 admin 用户刚刚创建或 teamId 尚未设置，回写 teamId
        if (adminCreated || teamCreated) {
            User admin = userService.getUserByName(ADMIN_USER_NAME);
            if (admin != null && (admin.getTeamId() == null || admin.getTeamId().isBlank())) {
                admin.setTeamId(ADMIN_TEAM_ID);
                userService.updateUser(admin);
                logger.info("admin 用户 teamId 回写完成");
            }
        }
    }

    // ── 私有初始化逻辑 ────────────────────────────────────────────────────────────

    /** @return true 表示本次新建了 admin 用户 */
    private boolean initAdminUser() {
        if (userService.getUserByName(ADMIN_USER_NAME) != null) {
            logger.debug("内置 admin 用户已存在，跳过初始化");
            return false;
        }
        User admin = new User();
        admin.setUserId(ADMIN_USER_ID);
        admin.setUserName(ADMIN_USER_NAME);
        admin.setPassword(PasswordUtil.md5(ADMIN_PASSWORD));
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        // teamId 先不设，待 team 创建后回写
        admin.setStatus(1);
        admin.setLoginCount(0);
        userService.addUser(admin);
        logger.info("内置 admin 用户初始化完成");
        return true;
    }

    /** @return true 表示本次新建了 adminteam */
    private boolean initAdminTeam() {
        if (teamService.getTeamByName(ADMIN_TEAM_NAME) != null) {
            logger.debug("内置 adminteam 已存在，跳过初始化");
            return false;
        }
        Team team = new Team();
        team.setTeamId(ADMIN_TEAM_ID);
        team.setTeamName(ADMIN_TEAM_NAME);
        team.setLeaderId(ADMIN_USER_ID);
        team.setDescription("系统内置管理员团队");
        team.setStatus(1);
        teamService.addTeam(team);
        logger.info("内置 adminteam 团队初始化完成");
        return true;
    }
}
