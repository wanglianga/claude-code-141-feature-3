# 校园食材供应商订货验收入库与索赔服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的后端服务，覆盖校园食堂食材供应链全流程：

```
周菜单 → 采购计划 → 订货单 → 送货批次 → 验收 → 入库/退货/降级 → 索赔 → 结算 → 影响分析 → 复盘统计
```

## 功能总览

| 模块 | 说明 |
|---|---|
| 周菜单 | 食堂按周维护餐次与食材用量，发布后生成采购计划 |
| 采购计划 | 汇总菜单需求，扣除可用库存（保留安全线）得出采购量，按品类匹配供应商 |
| 订货单 | 按计划按供应商自动拆单，记录规格、数量、合同价、质检要求、是否冷链、要求到货时间 |
| 送货批次 | 登记车牌、司机、到货时间、车厢温度；自动识别**晚送 / 冷链温度超标 / 临时替换品类** |
| 验收 | 验收员逐项核对重量、腐烂比例、检疫票、外观、抽检；合格入库、拒收退货、降级待审批；自动识别**少送（按累计送货量）/ 腐烂超标 / 票证缺失**；**食堂已备餐无法拒收**时自动转附条件接收并生成折价索赔 |
| 库存 | 库存水位 + 流水（入库/出库/退货/调整），安全线预警 |
| 退货 | 拒收与降级驳回自动生成退货单，跟踪退货完成 |
| 索赔 | 手工登记或系统自动生成（无法拒收折价），审批/支付流转 |
| 结算 | 按实际验收重量×合同价，扣减退货、降级折价、索赔、合同扣罚生成账单；确认时录入供应商开票金额，不一致自动登记**结算差异异常** |
| 影响分析 | 验收失败批次 → 受影响餐次、替代食材（替代组→同品类）、家长公告草稿、供应商责任 |
| 连续异常考核 | 同一供应商**三周内多次出现票证缺失与蔬菜腐烂**自动生成考核事件，汇总异常批次/验收照片/退货金额/对菜单影响；采购可**要求整改、暂停供货、降低下月采购比例**；供应商提交整改材料后进入**重点（更严格）验收**，连续通过 3 次方可由采购恢复正常供货 |
| 复盘统计 | 按供应商/品类/验收员统计异常与拒收率；按归因区分**天气质量波动 / 供应商履约不稳 / 验收标准执行偏差** |

## 异常类型与合同扣罚规则

| 异常 | 触发时机 | 扣罚 |
|---|---|---|
| 晚送 LATE_DELIVERY | 送货登记（宽限 30 分钟） | 订单金额 5%，最低 200 |
| 冷链超标 COLD_CHAIN_BREACH | 送货登记（车厢温度超出食材冷链区间） | 冷链货值 5%，最低 300 |
| 替换品类 SUBSTITUTION | 送货登记（实送食材≠订购食材或计划外送货） | 送货金额 10% |
| 少送 SHORT_DELIVERY | 验收（按订货明细累计送货量判定） | 少送金额 10% |
| 腐烂超标 HIGH_ROT_RATIO | 验收（蔬果阈值 10%，其他 5%） | 腐烂金额 5% |
| 票证缺失 MISSING_DOCUMENTS | 验收（肉类无检疫票；蔬菜无质量/农残检测票） | 固定 100 |
| 已备餐无法拒收 CANNOT_REJECT_PREPARED | 验收（拒收食材已被备餐餐次需要） | 不扣罚，自动生成货值 50% 折价索赔 |
| 结算差异 SETTLEMENT_MISMATCH | 结算确认（开票金额≠系统应付） | 不扣罚，仅标记 |

## 供应商连续异常考核

针对同一供应商反复出现质量问题的闭环管理（模块「供应商连续异常考核」）：

1. **自动触发**：观察窗口为三周（21 天）。窗口内同一供应商**票证缺失 ≥1 次且蔬菜腐烂超标 ≥1 次（合计多次）**时，系统在验收事务结束后自动生成一条考核事件；已存在未关闭考核事件时不重复生成，新一轮观察从上一次考核窗口之后起算。
2. **自动汇总**：考核事件汇总涉及的异常批次（`AssessmentBatchEvidence`：批次、命中异常类型、验收照片、该批次退货金额）、退货金额合计、以及对菜单的影响（批次食材在未来 7 天餐次中的去重餐次数）。验收照片在创建验收记录时通过 `photoUrls` 提交。
3. **采购处置**（`buyer`，`POST /api/assessments/{id}/decide`）：
   - `RECTIFY` 要求限期整改 → 状态 `RECTIFYING`；
   - `SUSPEND` 暂停供货 → 供应商 `suspended=true`，登记送货被拒绝（409）、采购计划不再分配该供应商；
   - `REDUCE_RATIO` 降低下月采购比例（传 `adjustedRatio`，0~1）→ 后续生成采购计划时该供应商的计划采购量按比例缩量（如 0.6）。
4. **供应商整改**（`POST /api/assessments/{id}/rectification`，提交整改材料说明与附件）：状态转 `UNDER_REVIEW`；若此前被暂停则解除暂停，但**后续每次送货都进入重点验收**。
5. **重点（更严格）验收**：整改期腐烂阈值收紧——蔬果 10%→5%、其他 5%→3%；每次送货验收后回写一条重点验收记录（`AssessmentAcceptanceCheck`），整体合格且未再出现票证缺失/腐烂记为通过，连续通过计数 +1，否则清零。
6. **恢复**（`POST /api/assessments/{id}/resume`）：连续通过默认 3 次后，采购负责人才能恢复正常供货比例（`purchaseRatio` 回到 1）、取消暂停并关闭考核（`CLOSED`）；未达标时恢复请求被拒绝（409）。

考核相关接口（均在 Swagger「供应商连续异常考核」分组）：

| 方法 & 路径 | 权限 | 说明 |
|---|---|---|
| GET `/api/assessments` | 登录用户 | 列表，可按 `supplierId`/`status` 过滤 |
| GET `/api/assessments/{id}` | 登录用户 | 详情：考核头 + 批次证据（照片/退货金额）+ 重点验收记录 + 是否可恢复 |
| POST `/api/assessments/{id}/decide` | 采购 | 处置：RECTIFY / SUSPEND / REDUCE_RATIO |
| POST `/api/assessments/{id}/rectification` | 采购* | 提交整改材料，进入重点验收 |
| POST `/api/assessments/{id}/resume` | 采购 | 连续通过后恢复正常供货并关闭 |

\* 当前系统没有独立的供应商账号，供应商提交整改材料由采购代录入；接口仅采购可写。供应商看板 `GET /api/suppliers/{id}/dashboard` 额外返回 `suspended`、`purchaseRatio`、`assessmentNo`、`assessmentStatus`、`assessmentConsecutivePasses`、`assessmentRequiredPasses`。


## 一键启动（Docker Compose）

```bash
docker compose up --build
```

- 应用：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui.html （OpenAPI 3.x 文档：/v3/api-docs）
- PostgreSQL：localhost:5432（food / food123 / fooddb），数据持久化在 `pgdata` volume

首次启动自动初始化演示数据（4 供应商、12 食材、库存、最近一个工作周菜单）。设 `APP_SEED_DEMO_DATA=false` 可关闭（环境变量对应 `app.seed-demo-data`）。

## 本地开发启动

前置：JDK 17、Maven 3.8+、PostgreSQL（创建 `fooddb` 库与 `food/food123` 用户，或用下方环境变量指向已有实例）。

```bash
# 1. 准备数据库（示例：本地 5432，或任意实例）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/fooddb
export SPRING_DATASOURCE_USERNAME=food
export SPRING_DATASOURCE_PASSWORD=food123

# 2. 启动
mvn spring-boot:run
# 或
mvn -DskipTests package && java -jar target/food-procurement-service-1.0.0.jar
```

## 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 主要权限 |
|---|---|---|---|
| inspector | inspector123 | 验收员 | 创建验收记录 |
| buyer | buyer123 | 采购负责人 | 供应商/食材/下单/送货登记/降级审批/退货/索赔/结算/异常处理 |
| canteen | canteen123 | 食堂 | 周菜单/生成采购计划/库存领用 |
| admin | admin123 | 校方 | 公告发布、复盘统计、全部只读 |

## 验证方式

### 1. 单元测试

```bash
mvn test
```

### 2. 端到端冒烟脚本（推荐）

应用启动后执行（脚本需**全新数据库**，重置后重启应用再跑）：

```bash
python3 scripts/smoke.py http://localhost:8080
```

脚本以 4 个角色走完整流程并断言 83 项检查：权限拦截 → 菜单/计划/拆单 → 送货（晚送+冷链+替换）→ 验收（少送/腐烂/缺票证/降级）→ 库存与安全线 → 降级审批/退货 → 影响分析与家长公告 → 已备餐无法拒收（附条件接收+自动索赔）→ 索赔 → 结算（金额逐项断言+对账差异）→ 供应商看板/异常归因/复盘统计 → **连续异常考核全生命周期（自动触发/汇总证据/降比例生效/整改/重点验收严格阈值/连续通过恢复，以及暂停供货+二次整改清零再恢复）** → 未认证拦截与 OpenAPI 文档。

### 3. 手工体验（curl 示例）

```bash
# 生成采购计划（菜单 id 见 GET /api/menus）
curl -u canteen:canteen123 -X POST http://localhost:8080/api/plans/menus/1/generate -H 'Content-Type: application/json' -d '{}'
# 下单（计划 id 见上一步）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/plans/1/orders -H 'Content-Type: application/json' \
  -d '{"expectedArrivalAt":"2026-10-06T08:00:00"}'
# 供应商异常看板
curl -u buyer:buyer123 http://localhost:8080/api/anomalies/dashboard/2
# 连续异常考核事件列表与处置
curl -u buyer:buyer123 http://localhost:8080/api/assessments?status=OPEN
curl -u buyer:buyer123 -X POST http://localhost:8080/api/assessments/1/decide \
  -H 'Content-Type: application/json' -d '{"actionType":"REDUCE_RATIO","adjustedRatio":0.6,"note":"下月降量"}'
# 复盘统计
curl -u admin:admin123 http://localhost:8080/api/stats/by-supplier
```

全部接口见 Swagger UI。

## 实际验证结论（本环境已执行）

- `mvn test`：9/9 通过（PenaltyRulesTest 扣罚规则 5 项 + AssessmentRulesTest 连续考核触发/严格阈值/观察口径 4 项）
- `mvn package`：构建成功
- 本地 PostgreSQL 15 启动应用：正常，演示数据自动初始化
- `python3 scripts/smoke.py`：**83/83 全部通过**（含连续异常考核自动触发→汇总→降比例→整改→3 次重点验收→恢复，以及暂停供货→二次整改未通过清零→再恢复的完整闭环）
- Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3.0.1 文档（60 个路径，新增 5 个 `/api/assessments/**`）
- 注：本环境无 Docker，`docker compose up --build` 未实际执行；Compose 配置与本地验证使用相同的镜像构建路径（Dockerfile 多阶段构建）与连接参数

> 验证用的数据库为本机临时 PostgreSQL（端口 5433，库名/用户/口令 fooddb/food/food123），与 Compose 默认仅端口不同；应用通过 `SPRING_DATASOURCE_URL` 指向即可。

## 项目结构

```
src/main/java/com/campus/food/
├── config/        # 安全、OpenAPI、全局异常、演示数据初始化
├── domain/        # JPA 实体（含 SupplierAssessment/考核证据/重点验收记录）+ enums/ 枚举
├── repo/          # Spring Data JPA 仓库
├── service/       # 业务服务（含 AssessmentRules/AssessmentService 连续异常考核）
└── web/           # REST 控制器（含 AssessmentController）
scripts/smoke.py   # 端到端冒烟验证脚本
Dockerfile         # 多阶段构建（maven 构建 → jre 运行）
docker-compose.yml # app + postgres 一键启动
```

## 关键设计

- **一致性**：验收在一个事务内联动库存流水、退货单、异常与索赔；结算金额完全由验收明细、退货、折价、索赔、扣罚推导，可追溯到行级明细
- **少送判定**：按订货明细的累计送货量（跨批次）判定，避免分批送货误报
- **无法拒收**：拒收数量若被"已备餐"餐次需要，自动转附条件接收（照常入库）+ 异常 + 货值 50% 折价索赔
- **降级使用**：验收登记降级数量 → 采购负责人审批 → 批准按折价率入库并参与折价结算，驳回自动转退货
- **复盘归因**：异常关闭时必须归因（天气/供应商履约/验收执行偏差），统计接口按供应商、品类、验收员三个维度输出
- **连续异常考核**：考核在验收事务收尾（退货已落库）时评估，确保退货金额汇总准确；观察窗口与上一轮考核衔接避免旧异常重复触发；整改严格阈值复用 `AssessmentRules`，重点验收结果驱动连续通过计数，恢复操作必须满足次数才放行
