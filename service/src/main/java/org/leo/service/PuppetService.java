package org.leo.service;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.dao.mapper.PuppetMapper;
import org.leo.service.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Puppet 管理服务。
 *
 * <p>Puppet 可见性规则（基于调用方角色与 Puppet permission 字段）：
 * <ul>
 *   <li>admin  — 可见所有 Puppet</li>
 *   <li>leader — 可见自己的所有 Puppet + 团队成员的非 private Puppet + 所有人的 public Puppet</li>
 *   <li>normal — 可见自己的所有 Puppet + 所有人的 public Puppet</li>
 * </ul>
 *
 * <p>Puppet permission 值：private / protected / public（默认 private）。
 */
@Service
public class PuppetService {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final PuppetMapper puppetMapper;

    @Autowired
    public PuppetService(PuppetMapper puppetMapper) {
        this.puppetMapper = puppetMapper;
    }

    // ── 基础查询 ─────────────────────────────────────────────────────────────────

    public Puppet findPuppetById(String id) {
        if (id == null || id.isBlank()) return null;
        return puppetMapper.findPuppetById(id.trim());
    }

    public List<Puppet> findPuppetByCreateUserId(String createUserId) {
        if (createUserId == null || createUserId.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByCreateUser(createUserId.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> findPuppetByParentPuppetId(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByParentPuppetId(puppetId.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> findPuppetByPermission(String permission) {
        if (permission == null || permission.isBlank()) return new ArrayList<>();
        List<Puppet> list = puppetMapper.findPuppetByPermission(permission.trim());
        return list != null ? list : new ArrayList<>();
    }

    public List<Puppet> getAllPuppet() {
        List<Puppet> list = puppetMapper.getAllPuppet();
        return list != null ? list : new ArrayList<>();
    }

    // ── 基于角色的可见性查询 ──────────────────────────────────────────────────────

    /**
     * 根据当前用户的角色与团队成员列表，返回该用户可见的所有 Puppet（去重）。
     *
     * @param user          当前登录用户
     * @param teamMemberIds 当前用户所在团队的成员 ID 列表（leader 时传入，其余可为 null）
     * @return 去重后的可见 Puppet 列表
     */
    public List<Puppet> getVisiblePuppets(User user, List<String> teamMemberIds) {
        if (user == null) return new ArrayList<>();

        String role = user.getPrivilege();

        // Admin 可见所有
        if (UserService.PRIVILEGE_ADMIN.equals(role)) {
            return getAllPuppet();
        }

        Set<Puppet> result = new LinkedHashSet<>();

        // 自己创建的所有 Puppet（private/protected/public 均可见）
        result.addAll(findPuppetByCreateUserId(user.getUserId()));

        // 全局 public Puppet
        result.addAll(findPuppetByPermission("public"));

        // Leader 额外可见：团队成员的非 private Puppet
        if (UserService.PRIVILEGE_LEADER.equals(role) && teamMemberIds != null) {
            for (String memberId : teamMemberIds) {
                if (memberId == null || memberId.equals(user.getUserId())) continue;
                for (Puppet p : findPuppetByCreateUserId(memberId)) {
                    if (!"private".equals(p.getPermission())) {
                        result.add(p);
                    }
                }
            }
        }

        return new ArrayList<>(result);
    }

    // ── 写操作 ───────────────────────────────────────────────────────────────────

    public boolean insertPuppet(Puppet puppet) {
        if (puppet == null) throw new IllegalArgumentException("puppet参数不能为空");
        String now = DATE_FORMAT.format(new Date());
        return puppetMapper.insertPuppet(
                puppet.getPuppetId(),
                puppet.getPuppetName(),
                puppet.getParentPuppetId(),
                puppet.getCreateByUserId(),
                puppet.getTeamId(),
                puppet.getConnLink(),
                puppet.getProtocol(),
                puppet.getHeaders(),
                puppet.getReqDisguiseId(),
                puppet.getRespDisguiseId(),
                puppet.getProxyEnabled(),
                puppet.getProxyType(),
                puppet.getProxyHost(),
                puppet.getProxyPort(),
                puppet.getBalanceEnabled(),
                puppet.getMaxReqCount(),
                puppet.getPermission(),
                puppet.getLastHeartbeat(),
                puppet.getHeartbeatInterval(),
                now, now,
                puppet.getRemark(),
                puppet.getUrlStrategy(),
                puppet.getPaddingStrategy(),
                puppet.getHeaderNoiseStrategy(),
                puppet.getTlsFingerprintStrategy(),
                puppet.getType()
        );
    }

    public boolean updatePuppetById(Puppet puppet) {
        if (puppet == null || puppet.getPuppetId() == null || puppet.getPuppetId().isBlank()) {
            throw new IllegalArgumentException("puppetId不能为空");
        }
        String now = DATE_FORMAT.format(new Date());
        return puppetMapper.updatePuppetById(
                puppet.getPuppetId(),
                puppet.getPuppetName(),
                puppet.getParentPuppetId(),
                puppet.getCreateByUserId(),
                puppet.getTeamId(),
                puppet.getConnLink(),
                puppet.getProtocol(),
                puppet.getHeaders(),
                puppet.getReqDisguiseId(),
                puppet.getRespDisguiseId(),
                puppet.getProxyEnabled(),
                puppet.getProxyType(),
                puppet.getProxyHost(),
                puppet.getProxyPort(),
                puppet.getBalanceEnabled(),
                puppet.getMaxReqCount(),
                puppet.getPermission(),
                puppet.getLastHeartbeat(),
                puppet.getHeartbeatInterval(),
                now,
                puppet.getRemark(),
                puppet.getUrlStrategy(),
                puppet.getPaddingStrategy(),
                puppet.getHeaderNoiseStrategy(),
                puppet.getTlsFingerprintStrategy(),
                puppet.getType()
        );
    }

    /**
     * 仅更新 last_heartbeat 字段。
     * 连接测试成功或 Puppet 初始化成功后调用，避免全量更新。
     */
    public boolean updateLastHeartbeat(String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return false;
        String now = DATE_FORMAT.format(new Date());
        return puppetMapper.updateLastHeartbeat(puppetId.trim(), now, now);
    }

    public boolean deletePuppetById(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id参数不能为空");
        return puppetMapper.deletePuppetById(id.trim());
    }
}
