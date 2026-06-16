---
name: detect-container-escape
description: 检测目标机器是否运行在容器（Docker/LXC/Kubernetes Pod）中，以及是否存在容器逃逸向量（特权容器、docker.sock
  挂载、capabilities 滥用、宿主机路径挂载等）。当任务涉及容器逃逸、Docker 安全、K8s Pod 逃逸、特权容器检测时使用。
enabled: false
---

# 容器逃逸检测

当目标机器疑似运行在容器中，或用户需要评估容器隔离安全性时，使用这个 skill。

**原则：只检测，不执行逃逸操作。** 所有步骤为信息收集，发现可利用向量后由用户决定是否进一步操作。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 只读操作 | 不执行逃逸、不挂载宿主机文件系统、不创建容器 |
| 不修改 K8s 资源 | 不创建 Pod、不修改 RBAC、不加载内核模块 |
| 低噪声 | 检测命令均为本地读取，不产生网络流量 |
| 不猜测 | 只报告有证据支持的逃逸向量 |

**WebShell 环境注意：**
- WebShell 进程通常以 www-data/tomcat/nobody 运行，部分检测命令可能权限不足
- 容器内可能没有 `capsh` 工具，需要手动解析 `/proc/self/status` 中的 CapBnd/CapEff
- 精简容器可能没有 `mount` 命令，回退到 `cat /proc/mounts`
- `find /` 在 overlay fs 上性能极差，搜索 docker.sock 时限定 `/var/run` 和 `/run`
- K8s API 调用（curl kubernetes.default.svc）可能被 NetworkPolicy 阻断
- 部分容器运行时（containerd/CRI-O）不使用 `/.dockerenv` 标记，需依赖 cgroup 判断
- 如果 `/proc/1/cgroup` 不可读（权限不足），尝试 `/proc/self/cgroup`

---

## 二、目标

- 确认是否在容器中运行
- 识别容器类型（Docker / LXC / Kubernetes Pod / 其他）
- 检测容器逃逸向量（六类）
- 评估逃逸可行性和优先级

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：检测目标是否在容器中运行，评估容器逃逸向量
2. **路径**：容器确认（cgroup + dockerenv + hostname）→ 容器配置采集 → 六类逃逸向量检测
3. **终止条件**：确认不在容器中则直接退出；如在容器中，至少完成所有六类逃逸向量检测后停止

如果已有侦察摘要，先读取 `hostProfile` 和 `cloudProfile` 初步判断。

### 第一阶段：确认是否在容器中

**检测命令（并发执行）：**

```bash
# cgroup 信息（最可靠）
cat /proc/1/cgroup 2>/dev/null || cat /proc/self/cgroup
# Docker 环境标识
ls /.dockerenv 2>/dev/null && echo "DOCKER_ENV_DETECTED"
# PID 1 进程（容器内通常是应用进程，不是 init/systemd）
cat /proc/1/cmdline 2>/dev/null | tr '\0' ' '
# hostname（容器通常是随机字符串）
hostname
# overlay 文件系统
mount 2>/dev/null | grep -E "overlay|devicemapper|aufs" || cat /proc/mounts | grep -E "overlay|devicemapper|aufs"
```

**判断标准：**

| 证据 | 结论 |
|---|---|
| `/proc/1/cgroup` 含 `docker`、`containerd` | Docker 容器 |
| `/proc/1/cgroup` 含 `kubepods` | Kubernetes Pod |
| `/proc/1/cgroup` 含 `lxc` | LXC 容器 |
| `/.dockerenv` 文件存在 | Docker 容器 |
| 环境变量含 `KUBERNETES_SERVICE_HOST` | Kubernetes Pod |
| PID 1 不是 init/systemd | 高概率容器 |

**如果确认不在容器中：** 告知用户并退出，此 skill 不适用。

### 第二阶段：识别容器配置

```bash
# 挂载点（找宿主机路径挂载）
mount 2>/dev/null | grep -v "cgroup\|tmpfs\|proc\|sysfs\|devpts\|overlay\|shm" || \
  cat /proc/mounts | grep -v "cgroup\|tmpfs\|proc\|sysfs\|devpts\|overlay\|shm"

# K8s Service Account Token
ls -la /var/run/secrets/kubernetes.io/ 2>/dev/null
cat /var/run/secrets/kubernetes.io/serviceaccount/namespace 2>/dev/null
```

使用 `exec` 获取环境变量，过滤 `KUBERNETES_*`、`DOCKER_*`、`CONTAINER_*`。

### 第三阶段：六类逃逸向量检测

#### 向量一：Docker Socket 挂载（最高优先级）

```bash
ls -la /var/run/docker.sock 2>/dev/null
ls -la /run/docker.sock 2>/dev/null
# 不要用 find / ，限定搜索范围
ls -la /var/run/docker*.sock /run/docker*.sock /host/var/run/docker.sock 2>/dev/null
```

| 状态 | 风险 |
|---|---|
| docker.sock 可读写 | **严重** — 可直接创建特权容器挂载宿主机 |
| docker.sock 只读 | 中 — 可查询容器信息但无法创建 |
| 不存在 | 无此向量 |

#### 向量二：特权容器（--privileged）

```bash
cat /proc/self/status | grep -i "^Cap"
# CapBnd: 000001ffffffffff 或 0000003fffffffff 表示特权容器
```

**Capabilities 解析参考：**

| CapBnd 值 | 含义 |
|---|---|
| `000001ffffffffff` | 全部 capabilities（特权容器，内核 5.x） |
| `0000003fffffffff` | 全部 capabilities（特权容器，内核 4.x） |
| 其他值 | 非特权，需逐项分析 |

#### 向量三：危险 Capabilities

```bash
cat /proc/self/status | grep CapEff
```

| Capability | 风险 | 利用方式 |
|---|---|---|
| CAP_SYS_ADMIN | 高 | 可挂载、修改内核参数、cgroup 逃逸 |
| CAP_SYS_MODULE | 极高 | 可加载内核模块 |
| CAP_SYS_PTRACE | 高 | 可注入宿主机进程 |
| CAP_DAC_READ_SEARCH | 高 | 可读取任意文件（绕过权限） |
| CAP_NET_ADMIN | 中 | 可修改网络配置、嗅探流量 |
| CAP_NET_RAW | 中 | 可发送原始数据包 |

#### 向量四：宿主机路径挂载

```bash
mount 2>/dev/null | grep -v "cgroup\|tmpfs\|proc\|sysfs\|devpts\|overlay\|shm" || \
  cat /proc/mounts | grep -v "cgroup\|tmpfs\|proc\|sysfs\|devpts\|overlay\|shm"
```

| 挂载路径 | 风险 |
|---|---|
| `/` 或 `/host` | **严重** — 整个宿主机文件系统 |
| `/etc`、`/home`、`/root` | 高 — 可读写宿主机重要目录 |
| `/var/log` | 中 — 可读宿主机日志 |
| `/tmp` | 低-中 — 可写宿主机 /tmp |

#### 向量五：Kubernetes RBAC 和 Service Account

```bash
# 读取 SA Token（只读操作）
TOKEN=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token 2>/dev/null)
NAMESPACE=$(cat /var/run/secrets/kubernetes.io/serviceaccount/namespace 2>/dev/null)

# 检查权限（只读查询）
curl -sk --connect-timeout 3 \
  -H "Authorization: Bearer $TOKEN" \
  "https://kubernetes.default.svc/apis/authorization.k8s.io/v1/selfsubjectrulesreviews" \
  -X POST -H "Content-Type: application/json" \
  -d "{\"apiVersion\":\"authorization.k8s.io/v1\",\"kind\":\"SelfSubjectRulesReview\",\"spec\":{\"namespace\":\"$NAMESPACE\"}}" 2>/dev/null | head -100
```

**高风险权限：**

| 权限 | 风险 |
|---|---|
| create pods | 可创建特权 Pod 逃逸 |
| create deployments | 同上 |
| get secrets | 可读取集群密钥 |
| * (wildcard) | 集群管理员权限 |

#### 向量六：内核漏洞

```bash
uname -r
```

| CVE | 受影响版本 | 说明 |
|---|---|---|
| CVE-2022-0847 (DirtyPipe) | 5.8 ≤ kernel < 5.16.11/5.15.25/5.10.102 | 任意文件覆写 |
| CVE-2022-0185 | 5.1 ≤ kernel < 5.16.2 | 堆溢出提权 |
| CVE-2021-22555 | 2.6.19 ≤ kernel < 5.12 | Netfilter 提权 |
| CVE-2020-14386 | 4.6 ≤ kernel < 5.9 | AF_PACKET 提权 |

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | `exec`（K8s 环境变量）、`exec`（所有检测命令） |
| 2 | `DockerContainerTools` | `listContainers`、`inspectContainer`、`dockerInfo`（Docker 环境枚举和容器配置检查） |
| 3 | `FileTools` | 读取 SA Token、cgroup 文件（备选） |
| 4 | `BasicInfoTools` | OS 和内核版本 |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| `/proc/1/cgroup` 不可读 | 尝试 `/proc/self/cgroup` |
| `mount` 命令不存在 | 使用 `cat /proc/mounts` |
| `capsh` 不存在 | 手动解析 `/proc/self/status` 中的 Cap 值 |
| `find` 在 overlay fs 上超时 | 限定 `/var/run` 和 `/run` 搜索 docker.sock |
| K8s API 被 NetworkPolicy 阻断 | 标注"API 不可达"，只报告 Token 存在性 |
| curl 不可用 | 跳过 K8s API 检查，只报告 Token 和 namespace |
| 所有检测命令权限不足 | 标注环境限制，建议提权后重新检测 |

---

## 六、输出格式

```markdown
## 容器逃逸检测摘要

**容器环境**：{是 / 否}
**容器类型**：{Docker / Kubernetes Pod / LXC / unknown}
**运行时**：{docker / containerd / CRI-O / unknown}
**最高风险向量**：{vector_name / 无}

---

## 容器环境识别

| 检测项 | 结果 | 证据 |
|--------|------|------|

## 逃逸向量（按可利用性排序）

### 向量 {n}：{name}

- **类型**：{docker_socket / privileged / capability / hostpath / k8s_sa / cve}
- **详情**：{detail}
- **可利用性**：{high / medium / low}
- **利用前提**：{prerequisite}
- **证据**：{evidence}

## Kubernetes 权限（如在 K8s Pod 中）

| 权限 | 风险 |
|------|------|

## 风险评估汇总

| 向量 | 可利用性 | 逃逸难度 |
|------|----------|---------|

## 下一步建议

2~3 条具体建议
```

### 建议示例

| 发现 | 建议 |
|---|---|
| docker.sock 可写 | 最高优先级逃逸路径，可创建特权容器 |
| 特权容器 | 可通过挂载宿主机磁盘逃逸 |
| K8s 高权限 SA | 可创建特权 Pod 或读取 Secrets |
| 内核版本受 CVE 影响 | 使用对应 exploit 提权/逃逸 |
| 无逃逸向量 | 使用 collect-cloud-metadata skill 检查云环境 |

---

## 七、结构化摘要写入

完成后必须：

1. `appendReconSummary` — 容器类型、隔离边界、逃逸向量和最高风险
2. `appendReconSummary` — 机器可读字段（只记录检测证据和风险，不记录逃逸命令链）

结构化 patch 示例：

```json
{
  "containerProfile": {
    "isContainer": true,
    "type": "kubernetes",
    "runtime": "containerd",
    "namespace": "default",
    "podName": "unknown",
    "privileged": false,
    "dockerSocketMounted": false,
    "serviceAccountTokenReadable": true,
    "kernelVersion": "5.10.0",
    "evidence": ["kubepods in /proc/1/cgroup", "KUBERNETES_SERVICE_HOST present"],
    "escapeVectors": [
      {
        "vector": "k8s_sa",
        "detail": "service account token readable, create pods permission",
        "exploitability": "high",
        "prerequisite": "K8s API reachable",
        "evidence": "/var/run/secrets/kubernetes.io/serviceaccount/token"
      }
    ]
  },
  "businessRole": {
    "roles": ["containerized_workload"],
    "evidence": ["container markers detected"]
  },
  "openQuestions": ["collect-cloud-metadata"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 所有检测为只读 | 不挂载、不创建容器、不执行逃逸 |
| docker.sock 可写 | 报告中置为"最高优先级" |
| 确认不在容器中 | 告知用户并退出 |
| K8s SA Token 读取 | 只读操作，不修改任何资源 |
| 多个逃逸向量 | 按可利用性排序，最高优先级在前 |
| 内核版本匹配 CVE | 标注但不执行 exploit |
| 发现逃逸向量后 | 立即写入侦察摘要 |

---

## Skill 元数据

- riskLevel: `medium`
- accessMode: `read_only_security_assessment`
- requiredTools: `CommandTools`
- optionalTools: `FileTools`, `DockerContainerTools`
- produces: `containerProfile`, `containerProfile.escapeVectors`, `businessRole.roles`, `openQuestions`
- structuredPatchPaths: `containerProfile`, `containerProfile.escapeVectors[]`
- recommendedNextSkills: `collect-cloud-metadata`, `hunt-credentials`
- forbiddenByDefault: 执行逃逸、挂载宿主机文件系统、创建容器、修改 K8s 资源、加载内核模块
