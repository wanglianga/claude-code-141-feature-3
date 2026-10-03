#!/usr/bin/env python3
"""端到端冒烟验证：菜单->计划->订货->送货->验收->库存/退货/降级->索赔->结算->影响分析->复盘统计"""
import json
import sys
import urllib.request
import urllib.error
import base64
from datetime import date, datetime, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

USERS = {
    "inspector": ("inspector", "inspector123"),
    "buyer": ("buyer", "buyer123"),
    "canteen": ("canteen", "canteen123"),
    "admin": ("admin", "admin123"),
}

PASS, FAIL = 0, 0


def call(user, method, path, body=None, expect=200):
    global PASS, FAIL
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    u, p = USERS[user]
    req.add_header("Authorization", "Basic " + base64.b64encode(f"{u}:{p}".encode()).decode())
    try:
        with urllib.request.urlopen(req) as resp:
            code, payload = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        code, payload = e.code, e.read()
    obj = json.loads(payload) if payload else None
    if code != expect:
        FAIL += 1
        print(f"  [FAIL] {method} {path} -> {code} (expect {expect}): {str(obj)[:300]}")
        return None
    return obj


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name} {detail}")


def find(items, **kw):
    for it in items:
        if all(it.get(k) == v for k, v in kw.items()):
            return it
    return None


def main():
    today = date.today()
    print("== 1. 基础数据与权限 ==")
    ings = call("canteen", "GET", "/api/ingredients")
    check("食材列表 12 条", ings and len(ings) == 12)
    ing = {i["code"]: i for i in ings}
    r = call("inspector", "POST", "/api/suppliers", {"code": "X", "name": "X"}, expect=403)
    check("验收员不能新建供应商(403)", r is not None)

    menus = call("canteen", "GET", "/api/menus")
    menu = menus[0]
    check("菜单已发布", menu["status"] == "PUBLISHED")
    detail = call("canteen", "GET", f"/api/menus/{menu['id']}")
    check("菜单含 6 个餐次", len(detail["meals"]) == 6)

    print("== 2. 生成采购计划并下单 ==")
    plan = call("canteen", "POST", f"/api/plans/menus/{menu['id']}/generate", {"note": "冒烟验证"})
    check("计划已生成", plan and plan["status"] == "DRAFT")
    pd = call("canteen", "GET", f"/api/plans/{plan['id']}")
    items = pd["items"]
    check("计划明细 8 行（大米库存充足不采购）", len(items) == 8, f"got {len(items)}")
    cabbage_item = find(items, ingredient={"id": ing["ING007"]["id"], "code": "ING007", "name": "大白菜",
                                           "category": "VEGETABLE", "unit": "kg", "spec": "散装",
                                           "requiresColdChain": False, "shelfLifeDays": 5,
                                           "safetyStock": 80.0, "referencePrice": 3.0,
                                           "substituteGroup": "LEAFY"})
    check("大白菜计划采购 30kg（库存低于安全线不可用）",
          cabbage_item and float(cabbage_item["plannedQty"]) == 30.0)

    expected_arrival = (datetime.combine(today + timedelta(days=1), datetime.min.time())
                        .replace(hour=8).isoformat())
    orders = call("buyer", "POST", f"/api/plans/{plan['id']}/orders",
                  {"expectedArrivalAt": expected_arrival, "remark": "冒烟"})
    check("按供应商拆成 4 张订货单", orders and len(orders) == 4, f"got {orders and len(orders)}")
    sups = call("buyer", "GET", "/api/suppliers")
    sup = {s["code"]: s for s in sups}
    meat_order = find(orders, supplier=sup["SUP002"])
    check("找到肉类供应商订货单", meat_order is not None)
    od = call("buyer", "GET", f"/api/orders/{meat_order['id']}")
    pork_oi = find(od["items"], ingredient=ing["ING004"])
    beef_oi = find(od["items"], ingredient=ing["ING005"])
    check("订货明细含猪肉/牛肉", pork_oi is not None and beef_oi is not None)

    print("== 3. 送货（晚送+冷链超标+替换品类+缺检疫票） ==")
    late_arrival = (datetime.combine(today + timedelta(days=1), datetime.min.time())
                    .replace(hour=10).isoformat())
    batch = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪A12345", "driverName": "司机甲",
        "arrivedAt": late_arrival, "vehicleTemperature": 10,
        "items": [
            {"orderItemId": pork_oi["id"], "ingredientId": ing["ING004"]["id"], "deliveredQty": 30,
             "productionBatchNo": "PC20261001", "productionDate": str(today - timedelta(days=1)),
             "expiryDate": str(today + timedelta(days=2))},
            {"orderItemId": beef_oi["id"], "ingredientId": ing["ING005"]["id"], "deliveredQty": 25,
             "productionBatchNo": "NC20261001", "quarantineCertNo": "JY20261003001",
             "productionDate": str(today - timedelta(days=1)),
             "expiryDate": str(today + timedelta(days=2))},
            {"ingredientId": ing["ING004"]["id"], "deliveredQty": 10,
             "productionBatchNo": "PC20261002", "quarantineCertNo": "JY20261003002",
             "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}
        ]})
    check("批次已登记", batch and batch["batchNo"].startswith("DB"))
    bd = call("buyer", "GET", f"/api/deliveries/{batch['id']}")
    check("送货明细 3 行", len(bd["items"]) == 3)
    pork_di = [i for i in bd["items"] if i.get("orderItem") and i["orderItem"]["id"] == pork_oi["id"]][0]
    beef_di = [i for i in bd["items"] if i.get("orderItem") and i["orderItem"]["id"] == beef_oi["id"]][0]
    sub_di = [i for i in bd["items"] if i.get("orderItem") is None][0]

    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    types = {a["type"] for a in anomalies}
    check("自动识别 晚送/冷链/替换 异常",
          {"LATE_DELIVERY", "COLD_CHAIN_BREACH", "SUBSTITUTION"} <= types, str(types))

    print("== 4. 验收（部分拒收+降级+腐烂超标+缺票证+少送） ==")
    rec = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch["id"], "measuredTemp": 10, "temperatureOk": False,
        "appearanceOk": True, "samplingResult": "农残抽检合格", "notes": "冷链车故障",
        "items": [
            {"deliveryItemId": pork_di["id"], "acceptedQty": 20, "rejectedQty": 5,
             "downgradedQty": 5, "rotRatio": 0.12, "rejectReason": "部分腐烂变色"},
            {"deliveryItemId": beef_di["id"], "acceptedQty": 25, "rotRatio": 0.02},
            {"deliveryItemId": sub_di["id"], "acceptedQty": 10, "rotRatio": 0.0}
        ]})
    check("验收完成(部分合格)", rec and rec["overallResult"] == "PARTIAL_QUALIFIED")
    rd = call("inspector", "GET", f"/api/acceptances/{rec['id']}")
    check("验收明细 3 行", len(rd["items"]) == 3)
    pork_ai = [i for i in rd["items"] if i["deliveryItem"]["id"] == pork_di["id"]][0]
    check("猪肉 5kg 降级待审批", pork_ai and pork_ai["downgradeStatus"] == "PENDING")

    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    types = {a["type"] for a in anomalies}
    check("自动识别 少送/腐烂/缺票证 异常",
          {"SHORT_DELIVERY", "HIGH_ROT_RATIO", "MISSING_DOCUMENTS"} <= types, str(types))

    levels = call("canteen", "GET", "/api/inventory/levels")
    pork_lv = find(levels, ingredient=ing["ING004"])
    check("猪肉库存 = 20(原) + 20(合格) + 10(替换) = 50",
          pork_lv and float(pork_lv["quantity"]) == 50.0, str(pork_lv and pork_lv["quantity"]))
    alerts = call("canteen", "GET", "/api/inventory/safety-alerts")
    alert_names = {a["ingredient"]["name"] for a in alerts}
    check("安全线预警含大白菜/鲜牛奶", {"大白菜", "鲜牛奶"} <= alert_names, str(alert_names))

    print("== 5. 降级审批 / 退货 ==")
    call("buyer", "POST", f"/api/acceptances/items/{pork_ai['id']}/downgrade-approval",
         {"approve": True, "discountRate": 0.25})
    levels = call("canteen", "GET", "/api/inventory/levels")
    pork_lv = find(levels, ingredient=ing["ING004"])
    check("降级批准后猪肉库存 55", pork_lv and float(pork_lv["quantity"]) == 55.0)
    returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP002']['id']}")
    check("生成退货单 5kg 猪肉", returns and float(returns[0]["quantity"]) == 5.0)
    call("buyer", "POST", f"/api/returns/{returns[0]['id']}/complete")
    returns = call("buyer", "GET", f"/api/returns?supplierId={sup['SUP002']['id']}")
    check("退货已完成", returns[0]["status"] == "COMPLETED")

    print("== 6. 影响分析（受影响餐次/替代食材/家长公告/供应商责任） ==")
    impact = call("admin", "GET", f"/api/impact/batch/{batch['id']}")
    check("有影响食材(猪肉)", impact and len(impact["impacts"]) >= 1)
    pork_impact = impact["impacts"][0]
    check("受影响餐次 >= 2（白菜猪肉炖粉条/米饭套餐）",
          len(pork_impact["affectedMeals"]) >= 2, str(pork_impact["affectedMeals"]))
    check("替代食材含牛肉", any(s["name"] == "牛肉" for s in pork_impact["substitutes"]))
    check("自动生成家长公告草稿", len(impact["announcements"]) >= 1)
    check("供应商责任含异常与索赔信息", impact["supplierResponsibility"]["supplierName"] == "惠民肉联食品公司")
    ann = call("admin", "GET", "/api/announcements?status=DRAFT")
    check("公告草稿存在", len(ann) >= 1)
    pub = call("admin", "POST", f"/api/announcements/{ann[0]['id']}/publish")
    check("公告已发布", pub and pub["status"] == "PUBLISHED")

    print("== 7. 已备餐无法拒收 -> 附条件接收+自动索赔 ==")
    meals = call("canteen", "GET", f"/api/menus/{menu['id']}")["meals"]
    rice_meal = [m for m in meals if m["meal"]["dishName"] == "米饭套餐"][0]["meal"]
    call("canteen", "POST", f"/api/menus/meals/{rice_meal['id']}/status", {"status": "PREPARED"})
    batch2 = call("buyer", "POST", "/api/deliveries", {
        "orderId": meat_order["id"], "licensePlate": "沪B67890",
        "arrivedAt": (datetime.combine(today + timedelta(days=2), datetime.min.time())
                      .replace(hour=7).isoformat()),
        "vehicleTemperature": 2,
        "items": [{"orderItemId": pork_oi["id"], "ingredientId": ing["ING004"]["id"],
                   "deliveredQty": 10, "quarantineCertNo": "JY20261004001",
                   "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}]})
    bd2 = call("buyer", "GET", f"/api/deliveries/{batch2['id']}")
    di2 = bd2["items"][0]
    rec2 = call("inspector", "POST", "/api/acceptances", {
        "batchId": batch2["id"], "measuredTemp": 2, "temperatureOk": True,
        "appearanceOk": False, "samplingResult": "感官异常",
        "items": [{"deliveryItemId": di2["id"], "rejectedQty": 10, "rotRatio": 0.3,
                   "rejectReason": "大面积变质"}]})
    check("已备餐时拒收转附条件接收", rec2 is not None)
    rd2 = call("inspector", "GET", f"/api/acceptances/{rec2['id']}")
    ai2 = rd2["items"][0]
    check("验收明细为 CONDITIONAL_ACCEPT 且全部入库",
          ai2["result"] == "CONDITIONAL_ACCEPT" and float(ai2["acceptedQty"]) == 10.0)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 CANNOT_REJECT_PREPARED 异常",
          any(a["type"] == "CANNOT_REJECT_PREPARED" for a in anomalies))
    claims = call("buyer", "GET", f"/api/claims?supplierId={sup['SUP002']['id']}")
    check("自动生成无法拒收折价索赔", any("无法拒收" in (c["claimType"] or "") for c in claims))

    print("== 8. 索赔 ==")
    claim = call("buyer", "POST", "/api/claims", {
        "supplierId": sup["SUP002"]["id"], "batchId": batch["id"],
        "claimType": "冷链断裂质量索赔", "amount": 150, "reason": "冷链车故障导致部分腐烂"})
    check("索赔已登记", claim and claim["status"] == "PENDING")
    call("buyer", "POST", f"/api/claims/{claim['id']}/approve")
    claim = call("buyer", "GET", f"/api/claims/{claim['id']}")
    check("索赔已批准", claim["status"] == "APPROVED")

    print("== 9. 结算（验收重量/退货/折价/索赔/扣罚 + 对账差异） ==")
    st = call("buyer", "POST", "/api/settlements/generate", {
        "supplierId": sup["SUP002"]["id"],
        "periodStart": str(today - timedelta(days=7)),
        "periodEnd": str(today + timedelta(days=7))})
    check("结算单已生成", st and st["status"] == "DRAFT")
    # delivered: 20*30 + 5*30(降级全价) + 25*60 + 10*30(替换) + 10*30(附条件) = 600+150+1500+300+300 = 2850
    check("验收应付 2850", float(st["deliveredAmount"]) == 2850.0, str(st["deliveredAmount"]))
    check("退货扣减 150", float(st["returnDeduction"]) == 150.0, str(st["returnDeduction"]))
    check("降级折价扣减 37.5", float(st["downgradeDeduction"]) == 37.5, str(st["downgradeDeduction"]))
    check("索赔扣减 150", float(st["claimDeduction"]) == 150.0, str(st["claimDeduction"]))
    # 扣罚: 批次1(晚送200+冷链300+替换30+少送15+腐烂5.4+缺票证100) + 批次2(晚送200+腐烂4.5) = 854.9
    check("合同扣罚 854.9", float(st["penaltyDeduction"]) == 854.9, str(st["penaltyDeduction"]))
    check("最终应付 1657.6", float(st["finalAmount"]) == 1657.6, str(st["finalAmount"]))
    sd = call("buyer", "GET", f"/api/settlements/{st['id']}")
    check("结算明细含 6 类行", len({i["itemType"] for i in sd["items"]}) == 5)
    st2 = call("buyer", "POST", f"/api/settlements/{st['id']}/confirm",
               {"supplierInvoiceAmount": 2000})
    check("开票金额不一致 -> mismatchFlag", st2 and st2["mismatchFlag"] is True)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={sup['SUP002']['id']}")
    check("产生 SETTLEMENT_MISMATCH 异常",
          any(a["type"] == "SETTLEMENT_MISMATCH" for a in anomalies))

    print("== 10. 供应商看板 / 异常归因 / 复盘统计 ==")
    dash = call("buyer", "GET", f"/api/anomalies/dashboard/{sup['SUP002']['id']}")
    check("连续异常标记(近30天>=3)", dash["consecutiveAnomaly"] is True)
    check("看板含异常类型分布", len(dash["anomaliesByType"]) >= 6)
    open_anomalies = [a for a in anomalies if a["status"] == "OPEN"]
    a1, a2 = open_anomalies[0], open_anomalies[1]
    call("buyer", "POST", f"/api/anomalies/{a1['id']}/resolve",
         {"causeCategory": "SUPPLIER_PERFORMANCE", "note": "供应商冷链车故障"})
    call("buyer", "POST", f"/api/anomalies/{a2['id']}/resolve",
         {"causeCategory": "WEATHER", "note": "连续高温导致腐烂"})
    stats = call("admin", "GET", "/api/stats/by-supplier")
    meat_stats = find(stats, supplierId=sup["SUP002"]["id"])
    check("按供应商统计含肉类供应商", meat_stats is not None and meat_stats["anomalyCount"] >= 8)
    cat = call("admin", "GET", "/api/stats/by-category")
    check("按品类统计含 MEAT", cat.get("MEAT", 0) >= 3, str(cat))
    insp = call("admin", "GET", "/api/stats/by-inspector")
    check("按验收员统计含王验收", any(i["username"] == "inspector" and i["acceptanceCount"] == 2
                                    for i in insp), str(insp))
    cb = call("admin", "GET", "/api/stats/cause-breakdown")
    check("归因分布含 供应商履约/天气",
          cb["byCause"].get("SUPPLIER_PERFORMANCE", 0) >= 1 and cb["byCause"].get("WEATHER", 0) >= 1,
          str(cb["byCause"]))

    print("== 11. 供应商连续异常考核（票证缺失+蔬菜腐烂 -> 考核 -> 处置 -> 整改 -> 重点验收 -> 恢复） ==")
    veg_order = find(orders, supplier=sup["SUP001"])
    check("蔬菜供应商订货单存在", veg_order is not None)
    vod = call("buyer", "GET", f"/api/orders/{veg_order['id']}")
    cabbage_oi = find(vod["items"], ingredient=ing["ING007"])
    check("蔬菜订货明细含大白菜", cabbage_oi is not None)
    cabbage_id = ing["ING007"]["id"]
    now_iso = datetime.now().isoformat(timespec="seconds")

    def veg_batch_accept(deliver_qty, accepted_qty, rejected_qty, rot, cert, photos=None):
        """登记一次大白菜送货并验收；cert 为 None 表示缺少质量/农残检测票证。"""
        item = {"orderItemId": cabbage_oi["id"], "ingredientId": cabbage_id,
                "deliveredQty": deliver_qty,
                "productionDate": str(today), "expiryDate": str(today + timedelta(days=4))}
        if cert is not None:
            item["qualityCertNo"] = cert
        b = call("buyer", "POST", "/api/deliveries", {
            "orderId": veg_order["id"], "licensePlate": "沪V00001", "driverName": "菜贩乙",
            "arrivedAt": now_iso, "items": [item]})
        bdi = call("buyer", "GET", f"/api/deliveries/{b['id']}")["items"][0]
        return call("inspector", "POST", "/api/acceptances", {
            "batchId": b["id"], "temperatureOk": True, "appearanceOk": rejected_qty == 0,
            "samplingResult": "农残抽检合格" if cert else "无检测票证",
            "notes": "考核流程", "photoUrls": photos,
            "items": [{"deliveryItemId": bdi["id"], "acceptedQty": accepted_qty,
                       "rejectedQty": rejected_qty, "rotRatio": rot,
                       "rejectReason": "腐烂超标" if rejected_qty else None}]})

    assessments0 = call("buyer", "GET", f"/api/assessments?supplierId={sup['SUP001']['id']}")
    check("初始无考核事件", assessments0 == [])

    # B1：大白菜腐烂超标（有票证），部分拒收 -> 蔬菜腐烂异常 + 退货金额
    qty = int(cabbage_oi["quantity"])
    veg_batch_accept(qty, qty - 10, 10, 0.3, "QC-B1", "https://example.com/photo-rot-b1.jpg")
    assessments = call("buyer", "GET", f"/api/assessments?supplierId={sup['SUP001']['id']}")
    check("仅蔬菜腐烂尚不触发考核", assessments == [])
    # B2：大白菜无质量检测票证，合格接收 -> 票证缺失 -> 三周内两类异常 -> 自动考核
    veg_batch_accept(10, 10, 0, 0.0, None)
    assessments = call("buyer", "GET", f"/api/assessments?supplierId={sup['SUP001']['id']}")
    check("两类异常三周内出现 -> 自动生成考核事件", len(assessments) == 1, str(assessments))
    as1 = assessments[0]
    check("考核状态 OPEN", as1["status"] == "OPEN")
    check("票证缺失1次/蔬菜腐烂1次",
          as1["missingDocCount"] == 1 and as1["vegetableRotCount"] == 1, str(as1))
    ad1 = call("buyer", "GET", f"/api/assessments/{as1['id']}")
    check("汇总异常批次 2 个", len(ad1["evidences"]) == 2, str(len(ad1["evidences"])))
    check("证据含验收照片", any("photo-rot-b1" in (e.get("photoUrls") or "") for e in ad1["evidences"]))
    check("汇总退货金额 = 10kg*3 = 30", float(ad1["assessment"]["returnAmount"]) == 30.0,
          str(ad1["assessment"]["returnAmount"]))
    check("汇总对菜单影响(未来7天大白菜餐次>=1)", ad1["assessment"]["impactedMealCount"] >= 1
          and ad1["assessment"]["summary"], str(ad1["assessment"]["impactedMealCount"]))
    check("未通过重点验收前不可恢复", ad1["eligibleForResume"] is False)
    # 权限：验收员不能处置考核
    r = call("inspector", "POST", f"/api/assessments/{as1['id']}/decide",
             {"actionType": "RECTIFY"}, expect=403)
    check("验收员不能处置考核(403)", r is not None)

    # 采购决定：降低下月采购比例到 60%
    call("buyer", "POST", f"/api/assessments/{as1['id']}/decide",
         {"actionType": "REDUCE_RATIO", "adjustedRatio": 0.6, "note": "连续质量问题，下月降量"})
    sup1 = call("buyer", "GET", f"/api/suppliers/{sup['SUP001']['id']}")
    check("供应商采购比例降为 0.6", float(sup1["purchaseRatio"]) == 0.6, str(sup1.get("purchaseRatio")))

    # 降低比例生效：另建下下周菜单（大白菜需求100，库存可用≈0），生成计划应按 60% 缩量到 60
    seeded_monday = date.fromisoformat(menu["weekStart"])
    monday2 = seeded_monday + timedelta(days=14)
    menu2 = call("canteen", "POST", "/api/menus", {"weekStart": str(monday2), "remark": "降比例验证"})
    mealx = call("canteen", "POST", f"/api/menus/{menu2['id']}/meals",
                 {"date": str(monday2), "mealType": "LUNCH", "dishName": "大白菜炒粉条", "servings": 300})
    call("canteen", "POST", f"/api/menus/meals/{mealx['id']}/ingredients",
         {"ingredientId": cabbage_id, "quantity": 100})
    call("canteen", "POST", f"/api/menus/{menu2['id']}/publish")
    plan2 = call("canteen", "POST", f"/api/plans/menus/{menu2['id']}/generate", {"note": "降比例月"})
    p2d = call("canteen", "GET", f"/api/plans/{plan2['id']}")
    cabbage_plan2 = find(p2d["items"], ingredient=ing["ING007"])
    check("下月大白菜计划按0.6缩量=60", cabbage_plan2 and float(cabbage_plan2["plannedQty"]) == 60.0,
          str(cabbage_plan2 and cabbage_plan2["plannedQty"]))
    # 非法比例被拒绝
    r = call("buyer", "POST", f"/api/assessments/{as1['id']}/decide",
             {"actionType": "REDUCE_RATIO", "adjustedRatio": 1.2}, expect=409)
    check("已处置考核不能重复决定(409)", r is not None)

    # 供应商提交整改材料 -> 进入整改期重点验收（比例暂保持 0.6）
    call("buyer", "POST", f"/api/assessments/{as1['id']}/rectification",
         {"material": "已更换冷链配送并加强农残自检，附整改报告",
          "attachments": "https://example.com/rectify-1.pdf"})
    ad1 = call("buyer", "GET", f"/api/assessments/{as1['id']}")
    check("提交整改后状态 UNDER_REVIEW", ad1["assessment"]["status"] == "UNDER_REVIEW")
    dash = call("buyer", "GET", f"/api/suppliers/{sup['SUP001']['id']}/dashboard")
    check("看板带考核状态 UNDER_REVIEW", dash["assessmentStatus"] == "UNDER_REVIEW"
          and dash["assessmentNo"] == as1["assessmentNo"])

    # 重点验收：整改期腐烂阈值收紧到 5%。第一次合格通过，此时不能恢复
    veg_batch_accept(5, 5, 0, 0.0, "QC-R1")
    ad1 = call("buyer", "GET", f"/api/assessments/{as1['id']}")
    check("第1次重点验收通过 1/3", ad1["assessment"]["consecutivePasses"] == 1
          and len(ad1["checks"]) == 1, str(ad1["assessment"]["consecutivePasses"]))
    r = call("buyer", "POST", f"/api/assessments/{as1['id']}/resume", {"note": "提前恢复"}, expect=409)
    check("未连续通过3次不能恢复(409)", r is not None)
    veg_batch_accept(5, 5, 0, 0.02, "QC-R2")
    veg_batch_accept(5, 5, 0, 0.05, "QC-R3")   # 等于阈值5%，不超标 -> 通过
    ad1 = call("buyer", "GET", f"/api/assessments/{as1['id']}")
    check("连续3次重点验收通过 3/3", ad1["assessment"]["consecutivePasses"] == 3
          and ad1["eligibleForResume"] is True, str(ad1["assessment"]["consecutivePasses"]))
    call("buyer", "POST", f"/api/assessments/{as1['id']}/resume", {"note": "整改有效，恢复正常供货"})
    ad1 = call("buyer", "GET", f"/api/assessments/{as1['id']}")
    sup1 = call("buyer", "GET", f"/api/suppliers/{sup['SUP001']['id']}")
    check("考核关闭且采购比例恢复 1.0",
          ad1["assessment"]["status"] == "CLOSED" and float(sup1["purchaseRatio"]) == 1.0)

    # 第二个考核周期：演示暂停供货 + 整改期一次未通过清零计数
    veg_batch_accept(10, 5, 5, 0.3, "QC-B3")    # 再次蔬菜腐烂
    veg_batch_accept(10, 10, 0, 0.0, None)      # 再次缺票证 -> 触发考核#2
    assessments = call("buyer", "GET", f"/api/assessments?supplierId={sup['SUP001']['id']}&status=OPEN")
    check("再次触发考核事件#2", len(assessments) == 1, str(assessments))
    as2 = assessments[0]
    call("buyer", "POST", f"/api/assessments/{as2['id']}/decide",
         {"actionType": "SUSPEND", "note": "整改后再犯，暂停供货"})
    sup1 = call("buyer", "GET", f"/api/suppliers/{sup['SUP001']['id']}")
    check("供应商已暂停供货", sup1["suspended"] is True)
    blocked = call("buyer", "POST", "/api/deliveries", {
        "orderId": veg_order["id"], "licensePlate": "沪V00009", "arrivedAt": now_iso,
        "items": [{"orderItemId": cabbage_oi["id"], "ingredientId": cabbage_id, "deliveredQty": 5,
                   "qualityCertNo": "QC-X", "productionDate": str(today),
                   "expiryDate": str(today + timedelta(days=4))}]}, expect=409)
    check("暂停供货期间拒绝登记送货(409)", blocked is not None)
    call("buyer", "POST", f"/api/assessments/{as2['id']}/rectification",
         {"material": "二次整改：引入第三方检测，更换供货基地"})
    sup1 = call("buyer", "GET", f"/api/suppliers/{sup['SUP001']['id']}")
    check("提交整改材料后解除暂停可再送货", sup1["suspended"] is False)

    # 整改期重点验收：腐烂10% 在严格阈值(5%)下判不合格，连续通过清零
    veg_batch_accept(5, 3, 2, 0.10, "QC-F1")
    ad2 = call("buyer", "GET", f"/api/assessments/{as2['id']}")
    check("整改期10%腐烂判不通过，计数清零", ad2["assessment"]["consecutivePasses"] == 0
          and ad2["checks"][0]["passed"] is False, str(ad2["assessment"]["consecutivePasses"]))
    veg_batch_accept(5, 5, 0, 0.0, "QC-P1")
    veg_batch_accept(5, 5, 0, 0.0, "QC-P2")
    veg_batch_accept(5, 5, 0, 0.0, "QC-P3")
    ad2 = call("buyer", "GET", f"/api/assessments/{as2['id']}")
    check("重新连续3次通过 3/3", ad2["assessment"]["consecutivePasses"] == 3
          and ad2["eligibleForResume"] is True)
    call("buyer", "POST", f"/api/assessments/{as2['id']}/resume", {"note": "恢复"})
    ad2 = call("buyer", "GET", f"/api/assessments/{as2['id']}")
    check("考核#2关闭，供应商未暂停、比例1.0",
          ad2["assessment"]["status"] == "CLOSED" and not ad2["assessment"]["supplier"]["suspended"]
          and float(ad2["assessment"]["supplier"]["purchaseRatio"]) == 1.0)
    closed = call("buyer", "GET", f"/api/assessments?supplierId={sup['SUP001']['id']}&status=CLOSED")
    check("累计两个考核事件均已关闭", len(closed) == 2, str(len(closed)))

    print("== 12. 未认证与 Swagger ==")
    import urllib.request as u
    try:
        u.urlopen(BASE + "/api/ingredients")
        check("未认证访问被拒(401)", False)
    except urllib.error.HTTPError as e:
        check("未认证访问被拒(401)", e.code == 401)
    try:
        code = u.urlopen(BASE + "/v3/api-docs").status
        check("OpenAPI 文档可访问", code == 200)
    except Exception:
        check("OpenAPI 文档可访问", False)

    print(f"\n结果: PASS={PASS} FAIL={FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
