---
name: recon-internal-network
description: 对目标机器所在内网进行系统性探测，包括网段枚举、存活主机扫描、开放端口探测和 HTTP 服务指纹识别。当任务涉及内网探测、横向移动准备、网段扫描、C段扫描时使用。
enabled: true
---

# 内网探测

当用户希望对目标机器所在的内网进行系统性侦察时，使用这个 skill。执行顺序：网段识别 → 存活主机 → 端口扫描 → HTTP 指纹。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 范围控制 | 默认只扫描 /24，超过 /24 需用户确认 |
| 速率控制 | 内网扫描线程 50-100，避免触发 IDS/流量告警 |
| 不利用 | 只探测不利用，不发起漏洞攻击或爆破 |
| 不写入 | 不在远端主机写入文件 |

**WebShell 环境注意：**
- WebShell 进程可能没有原始套接字权限，ICMP ping 不可用，需要用 TCP connect 探活
- `ScanTools` 的 `scanReachableHost` 已适配 WebShell 环境（TCP connect 方式）
- 容器内网络可能受 NetworkPolicy 限制，部分网段不可达
- 扫描时间较长时，WebShell 可能超时断开，使用 `startScanPort`（异步）而非同步扫描
- `queryScanPortResult` 返回 status=running 时继续轮询，不重复发起扫描
- 部分 WAF/IDS 会检测短时间内大量 TCP SYN，建议分批扫描
- 如果目标在云环境（VPC），安全组可能阻断跨子网访问

---

## 二、目标

- 识别目标机器所有网络接口和所在网段
- 发现内网存活主机
- 探测关键端口的开放情况
- 识别 HTTP/HTTPS 服务指纹
- 生成内网拓扑摘要和高价值目标排序

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：对目标机器所在内网进行系统性探测，产出存活主机、开放端口和高价值目标清单
2. **路径**：提取网络接口 → 存活主机扫描 → 端口扫描 → HTTP 指纹识别
3. **终止条件**：至少完成一轮存活主机扫描和端口扫描，或所有网段均已探测且无存活主机时停止

如果已有侦察摘要，先读取 `networkProfile` 和 `hostProfile`，跳过已确认的网段。

### 第一阶段：识别网络接口和网段

从 `getBasicInfo` 的 `NetworkInfo` 字段提取：

- 所有非回环、非虚拟接口的 IP 地址
- 推断所在网段（通常为 /24）
- 排除 `127.0.0.x`、`169.254.x.x`、`::1`

如果基础信息不足，使用 `collectAll` 补充（自动适配 Linux/Windows）。

### 第二阶段：存活主机扫描

使用 `ScanTools` 的 `scanReachableHost`：

| 参数 | 建议值 | 说明 |
|---|---|---|
| 扫描范围 | /24（最多 254 IP） | 超过 /24 需用户确认 |
| timeout | 500ms（内网）/ 1000ms（跨网段） | 避免误判 |
| 方式 | TCP connect | WebShell 环境无 ICMP 权限 |

**存活主机超过 10 台时**：先询问用户是否全量端口扫描还是只扫重点端口。

### 第三阶段：端口扫描

对存活主机使用 `startScanPort`（异步），按场景分组扫描高价值端口：

| 分组 | 端口 | 说明 |
|---|---|---|
| 数据库 | 3306, 3307, 5432, 1521, 1433, 27017, 6379, 6380, 9200 | MySQL/PG/Oracle/MSSQL/Mongo/Redis/ES |
| Web/中间件 | 80, 443, 8080, 8443, 8000, 8888, 9090, 7001, 4848, 8161, 15672 | HTTP/WebLogic/GlassFish/ActiveMQ/RabbitMQ |
| 远程访问 | 22, 3389, 5900, 23 | SSH/RDP/VNC/Telnet |
| 配置中心 | 8848, 9848, 2181, 8500, 2379 | Nacos/ZK/Consul/etcd |
| 消息队列 | 9092, 5672 | Kafka/RabbitMQ |
| 监控 | 9090, 3000 | Prometheus/Grafana |

**并发参数：**

| 参数 | 建议值 |
|---|---|
| threadsNum | 50-100（内网） |
| scanTimeout | 1000ms |
| 重点主机 | 可提高到 200 线程 |

**大规模扫描注意：** 全端口扫描（1-65535）需要用户明确确认。

### 第四阶段：HTTP 服务识别

对开放 Web 端口的主机执行 HTTP 探测：

```bash
# 快速识别（3 秒超时）
curl -sI --connect-timeout 3 -m 5 http://<host>:<port>/
```

**关注特征：**

| 响应特征 | 含义 |
|---|---|
| `Server:` 头 | 中间件类型和版本 |
| `X-Powered-By:` 头 | 编程语言和框架 |
| `Set-Cookie: rememberMe=deleteMe` | Shiro 框架 |
| `/actuator/health` 返回 200 | Spring Boot Actuator |
| 登录页面标题含 admin/管理 | 管理后台 |
| `X-Application-Context` | Spring Boot 应用 |

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `BasicInfoTools` | `getBasicInfo` / `collectAll` 提取网络接口 |
| 2 | `ScanTools` | `scanReachableHost`、`startScanPort`、`queryScanPortResult` |
| 3 | `HttpRequestTools` | `httpRequest` HTTP 服务指纹识别（替代 exec curl 模式），支持超时控制和自定义 Header |
| 4 | `CommandTools` | `exec` 网络补充查询、回退 HTTP 探测 |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| ICMP 不可用 | 使用 TCP connect 探活（ScanTools 默认行为） |
| 存活主机为 0 | 检查网段是否正确，调整 timeout 或手动指定范围 |
| 端口扫描超时 | 减少并发线程数，分批扫描 |
| queryScanPortResult 返回 running | 继续轮询，不重复发起 |
| WebShell 连接超时 | 使用异步扫描（startScanPort），分多次轮询结果 |
| 安全组阻断跨子网 | 标注不可达网段，只扫描本子网 |
| curl 不可用 | 跳过 HTTP 指纹阶段，只输出端口信息 |
| 多网卡多网段 | 每个网段独立扫描，标注来源接口 |

---

## 六、输出格式

```markdown
## 内网探测摘要

**网络接口数**：{n}
**识别网段数**：{n}
**存活主机数**：{n}
**高价值目标数**：{n}

---

## 网络接口与网段

| 接口 | IP 地址 | 网段 | 说明 |
|------|---------|------|------|

## 存活主机列表

| IP | 开放端口 | 推断服务 | 优先级 |
|----|---------|---------|--------|

## 高价值目标

### {IP}:{port}

- **服务**：{service}
- **HTTP Banner**：{banner}
- **优先级**：{high / medium / low}
- **原因**：{reason}
- **建议**：{next_skill}

## 内网拓扑摘要

{网段关系和关键节点}

## 下一步建议

2~3 条具体建议
```

### 建议示例

| 发现 | 建议 |
|---|---|
| 发现 Redis 端口开放 | 使用 collect-redis-credentials skill 提取凭据 |
| 发现 Web 管理后台 | 使用 discover-web-apps skill 进一步识别 |
| 发现 Nacos 端口 | 使用 collect-nacos-config skill 拉取配置 |
| 发现 Spring Boot Actuator | 使用 exploit-spring-actuator skill 利用 |
| 发现 Shiro 特征 | 使用 collect-shiro-key skill 提取密钥 |
| 发现数据库端口 | 使用 collect-jdbc-connection-info skill 获取连接信息 |

---

## 七、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 网段、存活主机、开放端口和重点目标
2. `manage_recon_summary(action="append")` — 机器可读字段

结构化 patch 示例：

```json
{
  "networkProfile": {
    "internalCidrs": [
      {"cidr": "10.0.0.0/24", "sourceInterface": "eth0", "evidence": "getBasicInfo.NetworkInfo"}
    ],
    "liveHosts": [
      {"ip": "10.0.0.12", "hostname": "unknown", "method": "scanReachableHost", "confidence": "high"}
    ]
  },
  "serviceProfile": {
    "openPorts": [
      {"host": "10.0.0.12", "port": 8080, "protocol": "tcp", "service": "http", "evidence": "scanPortResult"}
    ]
  },
  "targetPriorities": [
    {
      "target": "10.0.0.12:8080",
      "priority": "high",
      "reason": "HTTP service with admin-like title",
      "recommendedNextSkill": "discover-web-apps"
    }
  ],
  "openQuestions": ["discover-web-apps"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 未确认存活 | 不直接发起端口扫描 |
| 存活主机超过 10 台 | 先询问用户是否全量扫描 |
| 扫描任务未完成 | 轮询 queryScanPortResult 直到 completed |
| 存活主机为 0 | 检查网段和 timeout，可能需要调整 |
| 超过 /24 范围 | 必须用户确认 |
| 全端口扫描 | 必须用户确认 |
| 多网段 | 分别扫描，标注优先级 |
| 发现高价值目标后 | 立即写入侦察摘要 |

---

## Skill 元数据

- riskLevel: `medium`
- accessMode: `active_scan`
- requiredTools: `BasicInfoTools`, `ScanTools`
- optionalTools: `HttpRequestTools`, `CommandTools`, `NetworkInfoTools`
- produces: `networkProfile.internalCidrs`, `networkProfile.liveHosts`, `serviceProfile.openPorts`, `targetPriorities`, `openQuestions`
- structuredPatchPaths: `networkProfile.internalCidrs[]`, `networkProfile.liveHosts[]`, `serviceProfile.openPorts[]`, `targetPriorities[]`
- recommendedNextSkills: `discover-web-apps`, `collect-redis-credentials`, `collect-jdbc-connection-info`, `lateral-move-ssh`
- forbiddenByDefault: 超过 /24 的未确认扫描、全端口扫描、漏洞利用、爆破、写入远端文件
