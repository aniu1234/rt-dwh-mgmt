# RT-DWH Management Platform

面向中小团队的轻量实时数仓管理平台：**Flink 负责实时写入，Paimon 负责湖仓存储，Doris 负责交互式查询，RT-DWH 负责统一管理。**

![Paimon、Flink 与 Doris 轻量实时数仓架构](docs/diagrams/paimon-flink-doris-lightweight-architecture.svg)

## 目录

- [项目定位](#项目定位)
- [核心架构](#核心架构)
- [核心能力](#核心能力)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [本地开发](#本地开发)
- [配置说明](#配置说明)
- [构建与验证](#构建与验证)
- [生产部署注意事项](#生产部署注意事项)
- [项目结构](#项目结构)
- [文档与图示](#文档与图示)
- [常见问题](#常见问题)

## 项目定位

RT-DWH 将实时数据接入、湖仓元数据、交互式查询、数据质量和任务运维收敛到一个管理入口，目标是在没有专职大数据平台团队的情况下，也能搭建和维护一套可用的实时数仓。

平台重点解决五类问题：

- **数据接入**：通过 Flink CDC 将 MySQL 等业务数据持续写入 Paimon。
- **数据管理**：同步 Paimon Catalog、Schema、Snapshot 和 ODS／DWD／DWS／ADS 分层元数据。
- **数据查询**：通过 Doris Paimon External Catalog 执行即席查询，不让交互式查询与 Flink CDC Job 争抢 Slot。
- **稳定运行**：管理 Flink Job 的提交、暂停、恢复、停止、Savepoint 和状态自动校准。
- **质量治理**：执行质量规则、表维护、健康检查和多通道告警。

适合：

- 中小规模实时数仓、数据中台验证和湖仓方案原型；
- 希望用一套页面管理 Flink、Paimon 与 Doris 的工程团队；
- 需要从单机 Compose 起步，再逐步迁移到共享存储和集群部署的项目。

当前边界：

- 默认 Flink 镜像已内置 MySQL CDC、Paimon、MySQL JDBC 和 Hadoop 依赖；PostgreSQL 数据源模型与结构探测已支持，但运行 CDC Job 前需要补充与 Flink 2.2 匹配的 PostgreSQL CDC Connector。
- Doris 对 Paimon 的定位是只读查询；写入、Compact、Snapshot 清理等操作仍由 Flink／Paimon 完成。
- Compose 使用本地共享卷演示 Paimon Warehouse，只适合单机开发；生产环境必须使用所有 Flink、Doris 节点可访问的共享存储。
- Helm Chart 目前是部署骨架，依赖外部 MySQL、Flink、Doris、共享存储和镜像仓库，不是开箱即用的完整集群安装器。

## 核心架构

### 写入链路

```text
MySQL／PostgreSQL
        │ CDC
        ▼
Flink 2.2 + Flink CDC
        │ 流式写入
        ▼
Paimon Catalog + Paimon Warehouse
```

Flink 负责 Schema 探测、CDC SQL 生成、Checkpoint、Savepoint 和持续写入。Paimon 使用 MySQL JDBC Catalog 保存元数据，数据文件写入 Warehouse。

### 查询链路

```text
SQL 工作台／报表／API
        │
        ▼
RT-DWH 查询服务
  ├─ 只读 SQL 校验
  ├─ RBAC、超时、行数限制
  └─ 历史、取消、Trace ID
        │ Doris MySQL 协议
        ▼
Doris FE／BE
        │ Paimon External Catalog
        ▼
Paimon Catalog + Warehouse
```

Doris 负责 Paimon 数据的交互式查询。后端启动时可自动创建 `rtdwh_paimon` Catalog，并通过 Doris 动态读取 Database、Table 和 Column，驱动前端 Catalog 树与 SQL 智能提示。

### 管理控制面

RT-DWH 后端通过 Flink REST API、Flink SQL Gateway、Doris MySQL 协议和 Paimon JDBC Metastore 统一管理依赖组件，同时把运行状态持久化到 MySQL。

| 组件 | 主要职责 |
|---|---|
| RT-DWH Frontend | 数据总览、任务、元数据、查询、质量、告警和设置页面 |
| RT-DWH Backend | API、权限、任务编排、状态监控、查询网关和系统配置 |
| Flink JobManager／TaskManager | CDC、流式计算、Checkpoint 与 Savepoint |
| Flink SQL Gateway | 执行生成的 CDC／Paimon SQL 和维护语句 |
| Paimon | Catalog、Schema、Snapshot 与湖仓数据文件 |
| Doris FE／BE | Paimon Catalog 发现、SQL 解析与分布式查询 |
| MySQL | 管理库、Quartz 表和 Paimon JDBC Catalog |

## 核心能力

### 同步任务

- 数据源连通性与表结构探测；
- CDC SQL 自动生成和提交前预览；
- 表映射、启动模式、并行度与 Checkpoint 配置；
- Flink Job 启动、停止、暂停、恢复、重试和手动 Savepoint；
- Job、Checkpoint、Lag 和吞吐指标采集；
- `NOT_FOUND`、`SUSPENDED`、集群暂时不可达等状态的差异化处理。
- DAG 上下游依赖与环路校验；
- 任务配置版本发布、历史查看和 draft 版本回滚；
- ETL／物化任务按业务日期补数，运行实例领取、完成和依赖释放。

### Paimon 元数据与维护

- JDBC Catalog 元数据同步；
- Database、Table、Column、主键、分区键和表选项管理；
- ODS／DWD／DWS／ADS 分层识别；
- Schema 与 Snapshot 查看；
- 单表或批量 Compact、Expire Snapshots、Orphan Files Cleanup；
- 表维护日志和数据血缘展示。
- 表责任人、业务域、标签、敏感级别和生命周期治理信息。

### Doris 即席查询

- Doris JDBC 连接池与连接健康检查；
- Paimon External Catalog 自动初始化；
- Catalog／Database／Table／Column 浏览；
- Monaco SQL 编辑器和上下文智能补全；
- 只读语句白名单、超时、最大返回行数和结果截断提示；
- 查询取消、历史记录、服务端／浏览器 SQL 收藏；
- CSV 导出、执行引擎、Catalog、Database 和 Trace ID 记录；
- Workload Group 与单查询内存上限配置。
- 用户并发查询配额、成功率、P95 耗时和慢查询统计。

### 数据质量与告警

- 空值率、唯一性、范围、行数和自定义 SQL 规则；
- 质量检查记录与异常处置；
- Doris 定时质量检查、执行批次和规则级运行记录；
- 任务失败和质量异常告警；
- 钉钉、企业微信和邮件通知；
- 告警确认与解决状态管理。

### 系统运维与安全

- MySQL、Flink、Paimon、Doris 和数据源健康检查；
- 持久化依赖健康快照；
- Flink 与 Doris 运行时配置、连接测试；
- JWT 鉴权、接口级 RBAC 与写操作审计；
- `ADMIN`、`DEVELOPER`、`VISITOR` 三类角色；
- 数据源密码加密、查询审计和统一异常处理。

## 技术栈

| 层 | 技术 | 当前项目版本／配置 |
|---|---|---|
| 后端 | Spring Boot、Undertow、JPA、Security、Quartz | Spring Boot 3.3、Java 17 |
| 前端 | Umi Max、Ant Design、ProComponents、Monaco Editor | Umi 4.4、React 18、Ant Design 5 |
| 实时计算 | Apache Flink、Flink CDC | Flink 2.2.1、MySQL CDC 3.6.0-2.2 |
| 湖仓 | Apache Paimon | Paimon 2.0.0、JDBC Catalog |
| 查询 | Apache Doris | Doris 4.1.3、Paimon External Catalog |
| 管理数据库 | MySQL、Druid | MySQL 8.0、Druid 1.2.23 |
| 部署 | Docker Compose、Helm | Compose 为默认开发方式，Helm 为集成骨架 |
| 可观测性 | Spring Boot Actuator、Prometheus、Grafana | Prometheus 2.52、Grafana 11，可选启用 |

> Doris 4.1 的 Paimon JDBC Catalog 仍属于实验能力。升级 Paimon 或 Doris 时，应先验证 Catalog、主键表、Deletion Vector、Schema Evolution 和复杂类型兼容性。参见 [Apache Doris Paimon Catalog 文档](https://doris.apache.org/docs/4.x/lakehouse/catalogs/paimon-catalog/)。

## 快速开始

### 1. 前置条件

- Docker Engine 或 Docker Desktop；
- Docker Compose v2；
- Git；
- 能够访问 Maven Central 和 Docker Hub，用于首次构建 Flink 镜像及拉取依赖；
- 为 Flink、Doris 和 MySQL 预留足够的本机 CPU、内存与磁盘空间。

本地 Java／Node.js 只在源码开发时需要，纯 Compose 启动不要求预先安装。

### 2. 配置环境变量

```bash
git clone <repo-url> rt-dwh-mgmt
cd rt-dwh-mgmt
cp deploy/.env.example deploy/.env
```

至少填写以下变量：

```dotenv
MYSQL_ROOT_PASSWORD=<MySQL root 密码>
MYSQL_PASSWORD=<rtdwh_admin 密码>
DB_PASSWORD=<与 MYSQL_PASSWORD 相同>
PAIMON_JDBC_PASSWORD=<与 MYSQL_PASSWORD 相同>

JWT_SECRET=<至少 32 个字符的随机密钥>
ENCRYPTION_KEY=<数据源密码加密密钥>

INIT_USERS_ENABLED=true
INIT_ADMIN_PASSWORD=<管理员初始密码>
INIT_DEV_PASSWORD=<开发者初始密码>
INIT_GUEST_PASSWORD=<访客初始密码>
```

默认 Compose 让管理库和 Paimon JDBC Catalog 共用 `rtdwh_admin` 用户，因此 `MYSQL_PASSWORD`、`DB_PASSWORD`、`PAIMON_JDBC_PASSWORD` 应保持一致。

### 3. 启动服务

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

查看状态和日志：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs -f rtdwh-backend
```

### 4. 访问地址

| 服务 | 地址 | 说明 |
|---|---|---|
| RT-DWH Web | `http://localhost` | 管理平台入口 |
| RT-DWH API | `http://localhost/api/v1` | Nginx 对外 API 前缀 |
| Backend Actuator | `http://localhost/api/v1/actuator/health` | 后端基础健康检查 |
| Flink Web UI | `http://localhost:8081` | Job 与 TaskManager 状态 |
| Flink SQL Gateway | `http://localhost:9083` | 仅绑定本机 |
| Doris FE Web | `http://localhost:8030` | Doris FE 页面 |
| Doris MySQL Protocol | `localhost:9030` | JDBC／MySQL 客户端连接地址 |
| Doris BE Web | `http://localhost:8040` | Doris BE 页面 |

MySQL、PostgreSQL、Doris 和监控端口在 Compose 中默认绑定 `127.0.0.1`；Flink UI、后端和 Nginx 端口会直接暴露到宿主机，请按部署环境调整防火墙和端口映射。

### 5. 首次登录

首次启动时，设置 `INIT_USERS_ENABLED=true` 才会创建不存在的 `admin`、`dev01` 和 `guest` 用户。确认登录正常后，将其改回：

```dotenv
INIT_USERS_ENABLED=false
```

初始化不会覆盖已经存在的用户密码。

### 6. 启用可选监控

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml --profile monitoring up -d
```

- Prometheus：`http://localhost:9090`
- Grafana：`http://localhost:3001`

## 本地开发

### 开发环境

- Java 17；
- Maven 3.9+；
- Node.js 18+；
- npm；
- 可访问的 MySQL、Flink、Paimon Warehouse 和 Doris。

可以先用 Compose 启动依赖组件：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d \
  mysql postgresql doris-fe doris-be \
  flink-jobmanager flink-taskmanager flink-sql-gateway
```

启动后端：

```bash
cd backend
mvn spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

- 后端默认地址：`http://localhost:8080`
- 前端开发地址：`http://localhost:8000`
- 前端将 `/api/v1` 代理到本地后端，并移除该前缀。

本地直启后端前，请按 `backend/src/main/resources/application.yml` 配置数据库、Paimon、Flink 和 Doris 连接信息。不要在生产环境使用文件中的开发默认密码。

## 配置说明

完整模板见 [`deploy/.env.example`](deploy/.env.example)。下面列出最常用配置。

### 数据库与密钥

| 变量 | 用途 | Compose 默认值 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | 必填 |
| `MYSQL_USER` | 管理库用户 | `rtdwh_admin` |
| `MYSQL_PASSWORD` | 管理库用户密码 | 必填 |
| `DB_USERNAME`／`DB_PASSWORD` | 后端管理库连接 | 用户默认 `rtdwh_admin`，密码必填 |
| `PAIMON_JDBC_USER`／`PAIMON_JDBC_PASSWORD` | Paimon JDBC Catalog 连接 | 用户默认 `rtdwh_admin`，密码必填 |
| `JWT_SECRET` | JWT 签名密钥 | 必填，至少 32 字符 |
| `ENCRYPTION_KEY` | 数据源密码加密 | 必填 |

### Doris 查询

| 变量 | 用途 | 默认值 |
|---|---|---|
| `DORIS_ENABLED` | 启用 Doris 即席查询 | `true` |
| `DORIS_JDBC_URL` | FE MySQL 协议 JDBC 地址 | Compose 固定为 `jdbc:mysql://doris-fe:9030` |
| `DORIS_HTTP_URL` | FE HTTP 地址 | Compose 固定为 `http://doris-fe:8030` |
| `DORIS_USERNAME`／`DORIS_PASSWORD` | Doris 查询账号 | `root`／空，仅适合本地开发 |
| `DORIS_CATALOG` | Paimon External Catalog 名称 | `rtdwh_paimon` |
| `DORIS_DATABASE` | 默认查询 Database | `ods` |
| `DORIS_INITIALIZE_CATALOG` | 后端启动时创建 Catalog | `true` |
| `DORIS_CATALOG_INIT_RETRY_MS` | Doris 未就绪时重试创建 Catalog 的间隔 | `30000` |
| `DORIS_WORKLOAD_GROUP` | 即席查询 Workload Group | `rtdwh_adhoc` |
| `DORIS_EXEC_MEM_LIMIT_BYTES` | 单查询执行内存限制 | `2147483648` |
| `QUERY_MAX_CONCURRENT_PER_USER` | 单用户并发查询上限 | `2` |

### 监控与用户初始化

| 变量 | 用途 | 默认值 |
|---|---|---|
| `HEALTH_MONITOR_ENABLED` | 持久化依赖健康快照 | `true` |
| `HEALTH_MONITOR_INTERVAL_MS` | 健康检查周期 | `60000` |
| `HEALTH_MONITOR_INITIAL_DELAY_MS` | 启动后首次检查延迟 | `5000` |
| `QUALITY_SCHEDULE_ENABLED` | 启用定时质量检查 | `true` |
| `QUALITY_SCHEDULE_INTERVAL_MS` | 全量质量检查周期 | `3600000` |
| `WORKFLOW_SCHEDULER_ENABLED` | 启用 DAG 依赖实例释放 | `true` |
| `WORKFLOW_DEPENDENCY_CHECK_MS` | 待运行实例依赖检查周期 | `10000` |
| `INIT_USERS_ENABLED` | 首次创建演示用户 | `false` |
| `INIT_ADMIN_PASSWORD` | `admin` 初始密码 | 启用初始化时必填 |
| `INIT_DEV_PASSWORD` | `dev01` 初始密码 | 启用初始化时必填 |
| `INIT_GUEST_PASSWORD` | `guest` 初始密码 | 启用初始化时必填 |

通知相关变量包括 `DINGTALK_WEBHOOK`、`WECOM_WEBHOOK`、`MAIL_HOST` 和 `ALERT_EMAIL_RECIPIENTS`。

## API 约定

对外 API 统一使用 `/api/v1` 前缀。后端业务响应格式为：

```json
{
  "code": 0,
  "data": {},
  "message": "success"
}
```

前端在 `src/app.tsx` 中统一解包 `data`：

- `code === 0`：返回业务数据；
- `code !== 0`：抛出错误并进入全局异常处理；
- Spring Data 分页结果使用 `content`、`totalElements`、`number`、`size`。

## 构建与验证

后端：

```bash
cd backend
mvn test
mvn package -DskipTests
```

前端：

```bash
cd frontend
npm install
npx tsc --noEmit
npm run build
```

数据库结构由 Flyway 管理，启动后端时会顺序执行 `backend/src/main/resources/db/migration`。生产升级前应先备份管理库，并禁止修改已经发布过的迁移文件。

Compose 配置检查：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

## 生产部署注意事项

### Paimon Warehouse

Compose 通过 `paimon_data` 卷让 Flink 与单个 Doris BE 访问同一目录。生产多节点环境必须改用 HDFS、S3、MinIO、OSS 等共享存储，并为 Flink 与所有 Doris BE 配置相同的 Warehouse 地址和访问凭证。

### Doris Catalog

后端默认使用 Paimon JDBC Catalog。确认以下条件：

- Doris 版本支持目标 Paimon Catalog 类型；
- FE 可以访问 Paimon JDBC Metastore；
- BE 可以读取 Warehouse；
- `DORIS_PAIMON_JDBC_DRIVER_URL` 对 Doris 节点可下载；
- 升级 Doris／Paimon 后完成主键表、Schema Evolution、Deletion Vector 和复杂类型回归。

### 密钥与账号

- 替换 Doris `root` 空密码，并使用最小权限查询账号；
- 不要提交 `deploy/.env`；
- 使用稳定的 `JWT_SECRET` 和 `ENCRYPTION_KEY`，不要在已有加密数据后随意更换；
- 首次用户初始化完成后关闭 `INIT_USERS_ENABLED`；
- 限制 MySQL、Doris、Flink 和监控端口的外部访问。

### Kubernetes

Helm Chart 位于 `deploy/helm/rtdwh-mgmt`，当前用于接入已有基础设施。部署前至少需要：

1. 构建并推送前后端镜像；
2. 准备外部 MySQL、Flink、Doris 和共享 Paimon Warehouse；
3. 把 `values.yaml` 中的示例密码迁移到 Kubernetes Secret；
4. 补齐前端 Service／Deployment 及所需依赖组件；
5. 根据实际 Ingress Controller 校准 `/api/v1` 路由。

## 项目结构

```text
rt-dwh-mgmt/
├── backend/                         # Spring Boot 后端
│   ├── src/main/java/com/rtdwh/
│   │   ├── config/                  # Security、Quartz、Doris Catalog 初始化
│   │   ├── controller/              # REST API
│   │   ├── dto/                     # 请求／响应模型
│   │   ├── entity/                  # JPA 实体
│   │   ├── job/                     # 定时监控任务
│   │   ├── repository/              # Spring Data Repository
│   │   ├── security/                # JWT 与用户认证
│   │   └── service/                 # Flink、Paimon、Doris 与业务服务
│   ├── src/test/java/               # 后端测试
│   ├── Dockerfile
│   └── pom.xml
├── frontend/                        # Umi Max + Ant Design Pro
│   ├── src/api/                     # API 客户端
│   ├── src/pages/                   # 页面模块
│   ├── src/app.tsx                  # 全局请求与运行时配置
│   ├── .umirc.ts                    # 路由与布局
│   └── package.json
├── deploy/
│   ├── docker-compose.yml           # 本地完整环境
│   ├── .env.example                 # 环境变量模板
│   ├── flink/Dockerfile             # Flink 2.2 + CDC + Paimon 镜像
│   ├── helm/rtdwh-mgmt/             # Kubernetes 集成骨架
│   ├── nginx.conf
│   └── prometheus.yml
├── scripts/
│   ├── init-mysql.sql               # 管理库、Catalog 与初始化数据
│   └── setup-flink-sql-gateway.sh   # Connector 准备脚本
└── docs/
    ├── api/                          # API 设计
    ├── er-diagram/                   # ER 图
    ├── page-prototype/               # 页面原型
    └── diagrams/                     # 架构与工作流图
```

## 文档与图示

| 内容 | 文件 |
|---|---|
| Paimon＋Flink＋Doris 核心架构 | [`docs/diagrams/paimon-flink-doris-lightweight-architecture.svg`](docs/diagrams/paimon-flink-doris-lightweight-architecture.svg) |
| 系统能力总览 | [`docs/diagrams/arch-part1-capability.svg`](docs/diagrams/arch-part1-capability.svg) |
| 技术架构详图 | [`docs/diagrams/arch-part2-technical.svg`](docs/diagrams/arch-part2-technical.svg) |
| 部署拓扑 | [`docs/diagrams/arch-part3-deployment.svg`](docs/diagrams/arch-part3-deployment.svg) |
| 运维一日工作流 | [`docs/diagrams/daily-workflow.svg`](docs/diagrams/daily-workflow.svg) |
| API 设计 | [`docs/api/api-design.html`](docs/api/api-design.html) |
| ER 图 | [`docs/er-diagram/er-diagram.html`](docs/er-diagram/er-diagram.html) |
| 页面原型 | [`docs/page-prototype/page-prototype.html`](docs/page-prototype/page-prototype.html) |
| 平台建设与运行说明 | [`docs/platform-construction.md`](docs/platform-construction.md) |

## 常见问题

### 登录提示“用户不存在：admin”

在 `deploy/.env` 中设置 `INIT_USERS_ENABLED=true` 以及三个初始化密码，重启后端；用户创建成功后再关闭该开关。

### 后端提示 Doris Catalog 初始化延迟

依次确认：

1. Doris FE `9030` 可连接；
2. 至少有一个存活的 Doris BE；
3. Doris 查询账号有 Catalog 权限；
4. Paimon JDBC 用户和密码正确；
5. Doris BE 能读取 `/data/paimon` 或生产共享 Warehouse；
6. JDBC Driver URL 可访问。

后端会保持控制面可用，依赖健康页面会显示具体错误。

### 即席查询看不到 Database 或 Table

确认 `DORIS_CATALOG` 已创建、`DORIS_DATABASE` 存在，并在系统设置中执行 Doris 连接测试。Catalog 树直接来自 Doris，不读取 RT-DWH 管理库中的表副本。

### Flink 提交任务时报 Connector 不存在

确认 `deploy/flink/Dockerfile` 中的 Flink、CDC、Paimon Connector 版本一致。默认镜像只下载 MySQL CDC Connector；使用 PostgreSQL CDC 时需加入对应 Flink 2.2 Connector 并重新构建镜像。

### 查询状态与 Flink UI 不一致

状态监控默认每 30 秒轮询 Flink。`NOT_FOUND` 会校准为已终止，`SUSPENDED` 会校准为已暂停；集群暂时不可达时保留当前状态，避免把网络故障误判为任务结束。

## License

MIT
