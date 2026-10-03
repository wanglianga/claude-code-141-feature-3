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
| 连续异常考核 | 同一供应商**三周内多次票证缺失/蔬菜腐烂**自动生成考核事件，汇总异常批次/验收照片/退货金额/菜单影响；采购可**要求整改、暂停供货、降低下月采购比例**；供应商提交整改材料后进入**重点验收**（每次送货更严格：逐项拍照/加倍抽检/更严腐烂阈值），连续 3 次通过才恢复正常供货 |
| 复盘统计 | 按供应商/品类/验收员统计异常与拒收率；按归因区分**天气质量波动 / 供应商履约不稳 / 验收标准执行偏差** |

## 异常类型与合同扣罚规则

| 异常 | 触发时机 | 扣罚 |
|---|---|---|
| 晚送 LATE_DELIVERY | 送货登记（宽限 30 分钟） | 订单金额 5%，最低 200 |
| 冷链超标 COLD_CHAIN_BREACH | 送货登记（车厢温度超出食材冷链区间） | 冷链货值 5%，最低 300 |
| 替换品类 SUBSTITUTION | 送货登记（实送食材≠订购食材或计划外送货） | 送货金额 10% |
| 少送 SHORT_DELIVERY | 验收（按订货明细累计送货量判定） | 少送金额 10% |
| 腐烂超标 HIGH_ROT_RATIO | 验收（蔬果阈值 10%，其他 5%） | 腐烂金额 5% |
| 票证缺失 MISSING_DOCUMENTS | 验收（肉类无检疫票） | 固定 100 |
| 已备餐无法拒收 CANNOT_REJECT_PREPARED | 验收（拒收食材已被备餐餐次需要） | 不扣罚，自动生成货值 50% 折价索赔 |
| 结算差异 SETTLEMENT_MISMATCH | 结算确认（开票金额≠系统应付） | 不扣罚，仅标记 |
| 连续异常考核 RECURRING_ASSESSMENT | 验收后自动评估（三周内票证缺失/蔬菜腐烂 ≥3 次） | 不直接扣款，触发考核流程（整改/暂停/降下月采购比例） |

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
| inspector | inspector123 | 验收员 | 创建验收记录（整改观察期执行重点验收） |
| buyer | buyer123 | 采购负责人 | 供应商/食材/下单/送货登记/降级审批/退货/索赔/结算/异常处理/**考核处置（整改/暂停/降比例/确认整改材料）** |
| canteen | canteen123 | 食堂 | 周菜单/生成采购计划/库存领用 |
| admin | admin123 | 校方 | 公告发布、复盘统计、全部只读 |
| supplier01 | supplier123 | 供应商（绿源蔬菜合作社） | 查看本供应商考核事件、**提交整改材料** |

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

脚本以 5 个角色走完整流程并断言 97 项检查：权限拦截 → 菜单/计划/拆单 → 送货（晚送+冷链+替换）→ 验收（少送/腐烂/缺票证/降级）→ 库存与安全线 → 降级审批/退货 → 影响分析与家长公告 → 已备餐无法拒收（附条件接收+自动索赔）→ 索赔 → 结算（金额逐项断言+对账差异）→ 供应商看板/异常归因/复盘统计 → **连续异常考核（自动触发→降低下月采购比例+要求整改→计划按比例缩减→供应商提交整改材料→采购确认进入重点验收→不合格连续次数清零→连续 3 次通过恢复供货→再次触发暂停供货→提交材料放行→连续通过恢复→供应商账号越权隔离）** → 未认证拦截与 OpenAPI 文档。

### 3. 手工体验（curl 示例）

```bash
# 生成采购计划（菜单 id 见 GET /api/menus）
curl -u canteen:canteen123 -X POST http://localhost:8080/api/plans/menus/1/generate -H 'Content-Type: application/json' -d '{}'
# 下单（计划 id 见上一步）
curl -u buyer:buyer123 -X POST http://localhost:8080/api/plans/1/orders -H 'Content-Type: application/json' \
  -d '{"expectedArrivalAt":"2026-10-06T08:00:00"}'
# 供应商异常看板
curl -u buyer:buyer123 http://localhost:8080/api/anomalies/dashboard/2
# 连续异常考核：处置/材料/重点验收
curl -u buyer:buyer123 "http://localhost:8080/api/assessments?status=OPEN"
curl -u buyer:buyer123 -X POST http://localhost:8080/api/assessments/1/rectify \
  -H 'Content-Type: application/json' -d '{"deadline":"2026-10-15","note":"限期整改"}'
curl -u buyer:buyer123 -X POST http://localhost:8080/api/assessments/1/reduce-ratio \
  -H 'Content-Type: application/json' -d '{"ratio":0.6,"note":"下月采购比例降至60%"}'
curl -u supplier01:supplier123 -X POST http://localhost:8080/api/assessments/1/materials \
  -H 'Content-Type: application/json' -d '{"title":"整改报告","content":"加强分拣与随车质检证明"}'
# 复盘统计
curl -u admin:admin123 http://localhost:8080/api/stats/by-supplier
```

全部接口见 Swagger UI。

## 实际验证结论（本环境已执行）

- `mvn test`：11/11 通过（PenaltyRulesTest 扣罚规则 5 项 + AssessmentRulesTest 考核规则/降比 6 项）
- `mvn package`：构建成功
- 本地 PostgreSQL 15（独立实例 5433 端口、全新 fooddb）启动应用：正常，演示数据自动初始化（含供应商账号 supplier01）
- `python3 scripts/smoke.py`：**97/97 全部通过**（脚本需全新数据库，重置后重启应用再跑）
- Swagger UI `/swagger-ui/index.html` 返回 200，`/v3/api-docs` 返回 OpenAPI 3.0.1 文档（63 个路径，含 8 个考核接口）
- 注：本环境无 Docker，`docker compose up --build` 未实际执行；Compose 配置与本地验证使用相同的镜像构建路径（Dockerfile 多阶段构建）与连接参数

## 项目结构

```
src/main/java/com/campus/food/
├── config/        # 安全、OpenAPI、全局异常、演示数据初始化
├── domain/        # 24 个 JPA 实体 + enums/ 22 个枚举
├── repo/          # 24 个 Spring Data JPA 仓库
├── service/       # 12 个业务服务（菜单/计划/送货/验收/库存/退货/索赔/结算/异常/考核/影响/统计）+ 规则与异常工具类
└── web/           # 16 个 REST 控制器
scripts/smoke.py   # 端到端冒烟验证脚本
Dockerfile         # 多阶段构建（maven 构建 → jre 运行）
docker-compose.yml # app + postgres 一键启动
```

## 连续异常考核流程

```
常规验收后自动评估
   │  同一供应商 21 天窗口内：票证缺失(任意品类) + 蔬菜腐烂超标 ≥ 3 次
   ▼
[OPEN] 自动生成考核事件 + RECURRING_ASSESSMENT 异常
       （快照：命中异常、异常批次、验收照片、退货金额、未来7天受影响餐次）
   ▼ 采购负责人处置（可叠加）
   ├── 降低下月采购比例 → suppliers.purchase_ratio 下调，新采购计划按比例缩减（保留原始量/比例可追溯）
   ├── 暂停供货 → 供应商停用
   └── 要求整改 RECTIFICATION
          ▲                          │
          │（暂停后也可补交材料放行）  ▼
          │              供应商提交整改材料 MATERIAL_SUBMITTED
          │                          │ 采购确认
          ▼                          ▼
          └──────────► ENHANCED_OBSERVATION 整改观察期（恢复供货资格）
                              每次送货强制重点验收：
                              · 验收单级+逐项照片、加倍抽检结果
                              · 温度/外观必须合格
                              · 腐烂阈值更严（蔬果 5%、其他 3%，常规为 10%/5%）
                              · 蔬果必查农残/质检证明、肉类必查检疫票
                 通过 → consecutivePasses+1；不合格 → 归零（继续观察）
                              连续 3 次通过 → RESOLVED：采购比例恢复 1.0、考核异常自动关闭
```

- 同一供应商存在未终结考核时不重复生成新事件（避免事件泛滥）；考核终结后再次连续异常可重新触发
- 供应商账号（ROLE_SUPPLIER，档案关联供应商）只能查看本供应商考核并提交材料；处置与确认仅限采购负责人

## 关键设计

- **一致性**：验收在一个事务内联动库存流水、退货单、异常与索赔；结算金额完全由验收明细、退货、折价、索赔、扣罚推导，可追溯到行级明细
- **少送判定**：按订货明细的累计送货量（跨批次）判定，避免分批送货误报
- **无法拒收**：拒收数量若被"已备餐"餐次需要，自动转附条件接收（照常入库）+ 异常 + 货值 50% 折价索赔
- **降级使用**：验收登记降级数量 → 采购负责人审批 → 批准按折价率入库并参与折价结算，驳回自动转退货
- **连续异常考核**：规则阈值集中在 `AssessmentRules`（21 天窗口、≥3 次、重点验收更严阈值、连续 3 次）；考核证据以快照形式落事件（异常 id/批次 id/退货金额/菜单影响数），考核详情再联表展开验收照片
- **重点验收与考核联动**：验收单标记 `inspectionLevel=ENHANCED`，结果在同一验收事务后回写考核事件（连续计数/恢复），不通过自动清零
- **复盘归因**：异常关闭时必须归因（天气/供应商履约/验收执行偏差），统计接口按供应商、品类、验收员三个维度输出
