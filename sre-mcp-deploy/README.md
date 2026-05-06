# sre-mcp-deploy

华为云 AI For SRE — MCP Server 集群部署指南

## 部署架构

```
华为云 CCE 集群
├── Namespace: sre-mcp
│   ├── sre-mcp-rca        (端口 8001) — 故障根因分析 MCP Server
│   ├── sre-mcp-monitor    (端口 8002) — 智能监控 MCP Server
│   └── sre-mcp-remediation (端口 8003) — 故障自动修复 MCP Server
│
├── Namespace: sre-infra
│   ├── PostgreSQL 15      — 工单/策略持久化
│   ├── Redis 7            — 会话/幂等/熔断状态
│   └── Kafka 3.7          — 事件总线
│
└── ECS（独立节点）
    └── vLLM + Qwen2.5-72B — LLM 推理服务
```

## 目录结构

```
sre-mcp-deploy/
├── helm/
│   ├── Chart.yaml
│   ├── values.yaml           # 默认值
│   ├── values-dev.yaml       # Dev 环境 overlay
│   ├── values-prod.yaml      # Prod 环境 overlay
│   └── templates/
│       ├── _helpers.tpl
│       ├── serviceaccount.yaml
│       ├── configmap.yaml
│       ├── secret.yaml
│       ├── deployment.yaml
│       ├── service.yaml
│       ├── ingress.yaml
│       ├── hpa.yaml
│       ├── pdb.yaml
│       ├── networkpolicy.yaml
│       └── servicemonitor.yaml
└── k8s/                      # 独立 K8s 资源（非 Helm 管理）
```

## 前置条件

| 工具 | 版本 | 用途 |
|------|------|------|
| Helm | ≥ 3.16 | Chart 部署 |
| kubectl | ≥ 1.28 | K8s 操作 |
| Docker | ≥ 24 | 镜像构建 |
| Java + Maven | JDK 21 / Maven 3.9+ | 镜像构建 |

## 本地开发（docker-compose）

```bash
# 启动 Redis / PostgreSQL / Kafka / Neo4j
docker compose -f docker-compose.dev.yml up -d

# 验证服务健康
docker compose -f docker-compose.dev.yml ps

# 启动某个 MCP Server（以 rca 为例）
mvn spring-boot:run -pl sre-mcp-rca -Dspring-boot.run.profiles=dev
```

## 镜像构建（Spring Boot Buildpacks → SWR）

```bash
# 登录华为云 SWR
docker login -u "${REGION}@${AK}" -p "${SWR_LOGIN_KEY}" swr.cn-north-4.myhuaweicloud.com

# 构建并推送 3 个镜像（需要 Docker daemon 运行）
mvn spring-boot:build-image \
  -pl sre-mcp-rca,sre-mcp-monitor,sre-mcp-remediation \
  -DskipTests \
  -Dswr.registry=swr.cn-north-4.myhuaweicloud.com/sre-mcp

# 推送
docker push swr.cn-north-4.myhuaweicloud.com/sre-mcp/sre-mcp-rca:0.1.0-SNAPSHOT
docker push swr.cn-north-4.myhuaweicloud.com/sre-mcp/sre-mcp-monitor:0.1.0-SNAPSHOT
docker push swr.cn-north-4.myhuaweicloud.com/sre-mcp/sre-mcp-remediation:0.1.0-SNAPSHOT
```

## Helm 部署

### Dev 环境

```bash
# 创建命名空间
kubectl create namespace sre-mcp-dev

# 创建 SWR 镜像拉取 Secret
kubectl create secret docker-registry swr-secret \
  --docker-server=swr.cn-north-4.myhuaweicloud.com \
  --docker-username="${REGION}@${AK}" \
  --docker-password="${SWR_LOGIN_KEY}" \
  --namespace sre-mcp-dev

# 部署（首次）
helm install sre-mcp ./helm/ \
  --namespace sre-mcp-dev \
  -f helm/values-dev.yaml \
  --set global.huaweicloud.ak="${HW_AK}" \
  --set global.huaweicloud.sk="${HW_SK}"

# 升级（后续）
helm upgrade sre-mcp ./helm/ \
  --namespace sre-mcp-dev \
  -f helm/values-dev.yaml \
  --set rca.image.tag=<new-tag> \
  --set monitor.image.tag=<new-tag> \
  --set remediation.image.tag=<new-tag>
```

### 生产环境

```bash
# 创建命名空间
kubectl create namespace sre-mcp

# 部署（生产敏感信息通过 External Secret Operator 注入，不传 --set）
helm upgrade --install sre-mcp ./helm/ \
  --namespace sre-mcp \
  -f helm/values-prod.yaml \
  --set rca.image.tag=v1.0.0 \
  --set monitor.image.tag=v1.0.0 \
  --set remediation.image.tag=v1.0.0 \
  --set global.huaweicloud.projectId="${HW_PROJECT_ID}" \
  --set global.postgres.host="${PG_HOST}" \
  --set global.kafka.bootstrapServers="${KAFKA_BROKERS}" \
  --set global.vllm.endpoint="${VLLM_ENDPOINT}" \
  --wait --timeout 10m
```

## 验证部署

```bash
# 检查 Pod 状态
kubectl get pods -n sre-mcp

# 检查服务健康
kubectl exec -n sre-mcp deploy/sre-mcp-rca -- \
  curl -s http://localhost:8001/actuator/health | jq .

# 查看日志
kubectl logs -n sre-mcp deploy/sre-mcp-rca -f

# 端口转发（本地调试）
kubectl port-forward -n sre-mcp svc/sre-mcp-rca 8001:8001
kubectl port-forward -n sre-mcp svc/sre-mcp-monitor 8002:8002
kubectl port-forward -n sre-mcp svc/sre-mcp-remediation 8003:8003
```

## CI/CD 流程

```
Push to main  ──→  CI (build + test + helm lint)
                        ↓
                   CD build-images (SWR)
                        ↓
                   deploy-dev (auto)
                        ↓
Tag v*.*.* ───→  deploy-prod (requires GitHub environment approval)
```

### GitHub Secrets 配置

| Secret | 说明 |
|--------|------|
| `SWR_REGION` | SWR 所在区域（如 `cn-north-4`）|
| `SWR_AK` | 用于 SWR 登录的 AK |
| `SWR_LOGIN_KEY` | SWR 登录密码（`docker login -p` 参数）|
| `HW_AK` | 华为云 API AK |
| `HW_SK` | 华为云 API SK |
| `HW_PROJECT_ID` | Dev 项目 ID |
| `HW_PROJECT_ID_PROD` | Prod 项目 ID |
| `KUBECONFIG_DEV` | Dev CCE kubeconfig（base64 编码）|
| `KUBECONFIG_PROD` | Prod CCE kubeconfig（base64 编码）|
| `PG_HOST_PROD` | 生产 PostgreSQL 地址 |
| `KAFKA_BROKERS_PROD` | 生产 Kafka Bootstrap Servers |
| `VLLM_ENDPOINT_PROD` | 生产 vLLM 地址 |

## Helm Chart 说明

### 资源一览

| 资源 | 数量 | 说明 |
|------|------|------|
| Deployment | 3 | 每个 MCP Server 一个 |
| Service | 3 | ClusterIP，供 Ingress 和 ServiceMonitor 使用 |
| Ingress | 3 | 可选，HTTPS 终结 |
| HPA | 3 | 基于 CPU（70%）+ 内存（80%）自动扩缩容 |
| PDB | 3 | `minAvailable: 1`，滚动更新期间保证可用性 |
| NetworkPolicy | 3 | 白名单入出站规则 |
| ServiceMonitor | 3 | Prometheus Operator 自动发现 |
| ServiceAccount | 3 | 最小权限，`automountServiceAccountToken: false` |
| ConfigMap | 3 | 非敏感环境配置 |
| Secret | 3 | 敏感凭证（生产建议用 ESO 替换）|

### 关闭特定模块

```bash
helm upgrade sre-mcp ./helm/ \
  --set rca.enabled=false    # 只关闭 rca
```

### 调整副本数

```bash
helm upgrade sre-mcp ./helm/ \
  --set rca.replicaCount=3
```
