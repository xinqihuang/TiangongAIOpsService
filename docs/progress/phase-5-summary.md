# Phase 5 Summary — 部署与 CI/CD

**完成日期：** 2026-05-03

## 完成项清单

- [x] 创建 `sre-mcp-deploy/` 目录结构（`helm/`、`k8s/`）
- [x] 编写 Helm Chart（`Chart.yaml`、`_helpers.tpl`）
- [x] 编写 10 种 K8s 资源 Helm 模板：
  - `serviceaccount.yaml` — 最小权限 SA，`automountServiceAccountToken: false`
  - `configmap.yaml` — 非敏感环境配置（region/redis/pg/kafka/vllm 地址等）
  - `secret.yaml` — 敏感凭证（生产建议替换为 ESO + KMS）
  - `deployment.yaml` — 滚动更新策略、liveness/readiness probe、只读根文件系统
  - `service.yaml` — ClusterIP Service
  - `ingress.yaml` — NGINX Ingress，HTTPS TLS
  - `hpa.yaml` — HPA v2（CPU 70% + 内存 80% 双指标）
  - `pdb.yaml` — PodDisruptionBudget（minAvailable=1）
  - `networkpolicy.yaml` — 入站白名单（Ingress/Prometheus/同命名空间）+ 出站白名单
  - `servicemonitor.yaml` — Prometheus Operator 自动发现（`/actuator/prometheus`）
- [x] 编写 `values.yaml`（默认值，SWR 镜像地址）
- [x] 编写 `values-dev.yaml`（单副本、本地镜像、无 Ingress、无 HPA）
- [x] 编写 `values-prod.yaml`（3 副本、SWR 固定 tag、ESO、podAntiAffinity、高资源配额）
- [x] 配置 Spring Boot Buildpacks（`spring-boot-maven-plugin`）：
  - 3 个子模块均配置 `<image.name>` 指向 SWR
  - 设置 `BP_JVM_VERSION=21`
  - 支持 `-Dswr.registry=` 参数覆盖仓库地址
- [x] 编写 GitHub Actions CI Pipeline（`.github/workflows/ci.yml`）：
  - `build-and-test`：JDK 21、Maven 构建、单测 + 集成测试、JaCoCo 覆盖率
  - `helm-lint`：Helm Lint（default/dev/prod）+ dry-run template
- [x] 编写 GitHub Actions CD Pipeline（`.github/workflows/cd.yml`）：
  - `build-images`：Buildpacks 构建 + 推送至 SWR（rca/monitor/remediation）
  - `deploy-dev`：main 分支自动部署到 dev 命名空间（`helm upgrade --install --wait`）
  - `deploy-prod`：tag `v*.*.*` 触发，需 GitHub Environment 审批保护
- [x] 编写 `sre-mcp-deploy/README.md`（部署架构、前置条件、部署步骤、Secrets 配置）

## 架构决策记录

| 决策 | 方案 | 原因 |
|------|------|------|
| 镜像构建 | Spring Boot Buildpacks | 项目约定：不写 Dockerfile；Buildpacks 产出精简分层镜像 |
| 凭证管理 | dev=Secret，prod=ESO | 生产不允许 AK/SK 明文存 Helm values；ESO 从 KMS 动态拉取 |
| HPA 指标 | CPU + Memory 双指标 | MCP Server 同时受 CPU（LLM 推理）和 Memory（Java Heap）限制 |
| Pod 反亲和 | requiredDuringScheduling（prod）| 保证 3 副本分布在不同节点，避免单点故障 |
| 网络策略 | 白名单出站（仅 DNS/Redis/PG/Kafka/HTTPS）| 最小权限原则，防止潜在数据渗漏 |

## 遗留问题

1. **Helm Lint 未在 CI 本地验证**：由于本地无 Helm 安装，Lint 仅通过 GitHub Actions 验证
2. **ESO（External Secret Operator）集成**：`values-prod.yaml` 设置了 `useExternalSecrets: true`，但 ESO 的 `ExternalSecret` CR 资源未包含在 Chart 中，需要运维人员参考文档单独配置
3. **Neo4j 持久化**：`docker-compose.dev.yml` 包含 Neo4j，但 Helm Chart 不含 Neo4j（由 CCE 图数据库服务或独立部署管理）
4. **mTLS**：NetworkPolicy 提供了 L4 隔离，但未配置 Istio/Linkerd mTLS

## 项目总体完成状态

| Phase | 状态 | 说明 |
|-------|------|------|
| Phase 1：脚手架 + 共享库 | ✅ 完成 | 102 个测试通过 |
| Phase 2：sre-mcp-rca | ✅ 完成 | 33 个测试通过 |
| Phase 3：sre-mcp-monitor | ✅ 完成 | 34 个测试通过 |
| Phase 4：sre-mcp-remediation | ✅ 完成 | 46 个测试通过 |
| Phase 5：部署与 CI/CD | ✅ 完成 | Helm Chart + GitHub Actions |

**总计：215 个单元测试，全部通过。**
