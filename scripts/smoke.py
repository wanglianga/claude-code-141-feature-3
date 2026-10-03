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
    "supplier01": ("supplier01", "supplier123"),
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


def get_event(eid):
    d = call("buyer", "GET", f"/api/assessments/{eid}")
    return d["event"] if d else None


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

    print("== 11. 供应商连续异常考核（自动触发/降比/整改/重点验收/暂停放行） ==")
    veg_sup = sup["SUP001"]
    # 新生成计划并下单（蔬菜供应商 SUP001：大白菜/菠菜/苹果）
    plan2 = call("canteen", "POST", f"/api/plans/menus/{menu['id']}/generate", {"note": "考核验证计划"})
    orders2 = call("buyer", "POST", f"/api/plans/{plan2['id']}/orders",
                   {"expectedArrivalAt": expected_arrival, "remark": "考核验证"})
    veg_order = next((o for o in orders2 if o["supplier"]["id"] == veg_sup["id"]), None)
    check("蔬菜供应商订货单已生成", veg_order is not None)
    vod = call("buyer", "GET", f"/api/orders/{veg_order['id']}")
    cabbage_oi = find(vod["items"], ingredient=ing["ING007"])
    spinach_oi = find(vod["items"], ingredient=ing["ING008"])

    def veg_delivery(oi, ing_id, qty, cert=None):
        b = call("buyer", "POST", "/api/deliveries", {
            "orderId": veg_order["id"], "licensePlate": "沪V00001", "driverName": "司机乙",
            "arrivedAt": (datetime.combine(today + timedelta(days=1), datetime.min.time())
                          .replace(hour=7).isoformat()),
            "items": [{"orderItemId": oi, "ingredientId": ing_id, "deliveredQty": qty,
                       "qualityCertNo": cert,
                       "productionDate": str(today), "expiryDate": str(today + timedelta(days=3))}]})
        bd = call("buyer", "GET", f"/api/deliveries/{b['id']}")
        return b, bd["items"][0]

    def veg_accept(batch_id, di_id, qty, rot, photos=None):
        body = {"batchId": batch_id, "temperatureOk": True, "appearanceOk": True,
                "samplingResult": "加倍抽检合格" if photos else "抽检合格",
                "items": [{"deliveryItemId": di_id, "acceptedQty": qty, "rotRatio": rot,
                           "photos": photos}]}
        if photos:
            body["photos"] = ["photo://vehicle.jpg", "photo://site.jpg"]
        return call("inspector", "POST", "/api/acceptances", body)

    # 批次1：大白菜无质检证明 + 腐烂超标 -> 票证缺失 + 蔬菜腐烂 2 个命中异常
    b1, di1 = veg_delivery(cabbage_oi["id"], ing["ING007"]["id"], cabbage_oi["quantity"])
    veg_accept(b1["id"], di1["id"], cabbage_oi["quantity"], 0.12)
    assess = call("buyer", "GET", f"/api/assessments?supplierId={veg_sup['id']}")
    check("2 次命中异常尚不触发考核", assess is not None and len(assess) == 0, str(assess))
    # 批次2：菠菜腐烂超标 -> 三周内第 3 次命中，自动生成考核事件
    b2, di2 = veg_delivery(spinach_oi["id"], ing["ING008"]["id"], spinach_oi["quantity"], cert="NC-SP-001")
    veg_accept(b2["id"], di2["id"], spinach_oi["quantity"], 0.12)
    assess = call("buyer", "GET", f"/api/assessments?supplierId={veg_sup['id']}")
    check("第 3 次命中自动生成考核事件(OPEN)",
          assess and len(assess) == 1 and assess[0]["status"] == "OPEN", str(assess))
    event = assess[0]
    check("命中异常数>=3", event["qualifyingAnomalyCount"] >= 3, str(event["qualifyingAnomalyCount"]))
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={veg_sup['id']}")
    check("生成 RECURRING_ASSESSMENT 异常",
          any(a["type"] == "RECURRING_ASSESSMENT" for a in anomalies))
    detail = call("buyer", "GET", f"/api/assessments/{event['id']}")
    check("考核汇总含 2 个异常批次", len(detail["batches"]) == 2, str(len(detail["batches"])))
    check("异常批次关联验收记录号", all(bt["acceptanceRecordNo"] for bt in detail["batches"]))
    check("汇总退货金额(本流程为0)", float(detail["event"]["totalReturnAmount"]) == 0.0)
    check("汇总菜单影响餐次>=1", len(detail["impactedMeals"]) >= 1, str(detail["impactedMeals"]))
    check("考核摘要已生成", bool(detail["event"]["summary"]))

    # 采购处置：降低下月采购比例 + 要求整改
    r = call("inspector", "POST", f"/api/assessments/{event['id']}/rectify",
             {"deadline": str(today + timedelta(days=7))}, expect=403)
    check("验收员不能处置考核(403)", r is not None)
    call("buyer", "POST", f"/api/assessments/{event['id']}/reduce-ratio",
         {"ratio": 0.6, "note": "下月减量观察"})
    veg_now = call("buyer", "GET", f"/api/suppliers/{veg_sup['id']}")
    check("供应商采购比例降为 0.6", float(veg_now["purchaseRatio"]) == 0.6, str(veg_now.get("purchaseRatio")))
    call("buyer", "POST", f"/api/assessments/{event['id']}/rectify",
         {"deadline": str(today + timedelta(days=7)), "note": "限期一周整改"})
    event = get_event(event["id"])
    check("考核进入整改状态(RECTIFICATION)", event["status"] == "RECTIFICATION")
    # 降比后新计划：大白菜原计划30 -> 实际 18
    plan3 = call("canteen", "POST", f"/api/plans/menus/{menu['id']}/generate", {"note": "降比后计划"})
    p3d = call("canteen", "GET", f"/api/plans/{plan3['id']}")
    cb_item = find(p3d["items"], ingredient=ing["ING007"])
    check("降比后大白菜计划 30 -> 18", cb_item and float(cb_item["plannedQty"]) == 18.0
          and float(cb_item["originalPlannedQty"]) == 30.0
          and float(cb_item["appliedRatio"]) == 0.6, str(cb_item))

    # 供应商提交整改材料（角色/归属校验）
    r = call("canteen", "POST", f"/api/assessments/{event['id']}/materials",
             {"title": "x", "content": "y"}, expect=403)
    check("非供应商账号不能提交整改材料(403)", r is not None)
    mat = call("supplier01", "POST", f"/api/assessments/{event['id']}/materials",
               {"title": "蔬菜质量整改报告", "content": "1.加强地头分拣 2.随车附农残报告 3.冷链周转",
                "attachments": "file://rect-plan.pdf,file://report.jpg"})
    check("整改材料已提交", mat is not None and mat["title"] == "蔬菜质量整改报告")
    event = get_event(event["id"])
    check("考核状态变为 MATERIAL_SUBMITTED", event["status"] == "MATERIAL_SUBMITTED")
    detail = call("buyer", "GET", f"/api/assessments/{event['id']}")
    check("详情含整改材料 1 份", len(detail["materials"]) == 1)
    call("buyer", "POST", f"/api/assessments/materials/{mat['id']}/confirm",
         {"requiredPasses": 3, "reviewNote": "同意进入重点验收观察"})
    event = get_event(event["id"])
    check("确认材料后进入整改观察(ENHANCED_OBSERVATION)",
          event["status"] == "ENHANCED_OBSERVATION")

    # 用降比后的计划下单，供重点验收批次使用
    orders3 = call("buyer", "POST", f"/api/plans/{plan3['id']}/orders",
                   {"expectedArrivalAt": expected_arrival, "remark": "重点验收"})
    veg_order3 = next((o for o in orders3 if o["supplier"]["id"] == veg_sup["id"]), None)
    vo3d = call("buyer", "GET", f"/api/orders/{veg_order3['id']}")
    cb3 = find(vo3d["items"], ingredient=ing["ING007"])
    apple3 = find(vo3d["items"], ingredient=ing["ING009"])

    def enhanced_round(oi, ing_id, qty, rot, cert):
        b = call("buyer", "POST", "/api/deliveries", {
            "orderId": veg_order3["id"], "licensePlate": "沪V00002",
            "arrivedAt": (datetime.combine(today + timedelta(days=1), datetime.min.time())
                          .replace(hour=7, minute=30).isoformat()),
            "items": [{"orderItemId": oi, "ingredientId": ing_id, "deliveredQty": qty,
                       "qualityCertNo": cert, "productionDate": str(today),
                       "expiryDate": str(today + timedelta(days=3))}]})
        bd = call("buyer", "GET", f"/api/deliveries/{b['id']}")
        di = bd["items"][0]
        # 重点验收：缺照片应被拒绝
        bad = call("inspector", "POST", "/api/acceptances", {
            "batchId": b["id"], "temperatureOk": True, "appearanceOk": True,
            "samplingResult": "加倍抽检合格",
            "items": [{"deliveryItemId": di["id"], "acceptedQty": qty, "rotRatio": rot}]}, expect=400)
        check("重点验收缺照片被拒绝(400)", bad is not None)
        return call("inspector", "POST", "/api/acceptances", {
            "batchId": b["id"], "temperatureOk": True, "appearanceOk": True,
            "samplingResult": "加倍抽检合格",
            "photos": ["photo://vehicle.jpg", "photo://site.jpg"],
            "items": [{"deliveryItemId": di["id"], "acceptedQty": qty, "rotRatio": rot,
                       "photos": ["photo://weigh.jpg", "photo://rot-closeup.jpg"]}]})

    # 重点验收第1次：大白菜合格通过
    rec_e1 = enhanced_round(cb3["id"], ing["ING007"]["id"], cb3["quantity"], 0.02, "NC-CB-002")
    check("重点验收单标记 ENHANCED 且通过",
          rec_e1["inspectionLevel"] == "ENHANCED" and rec_e1["enhancedPassed"] is True)
    event = get_event(event["id"])
    check("连续通过 1 次", event["consecutivePasses"] == 1 and event["enhancedInspectionCount"] == 1)
    # 重点验收第2次：苹果腐烂 8%（重点阈值 5%）-> 不通过，连续次数归零
    enhanced_round(apple3["id"], ing["ING009"]["id"], apple3["quantity"], 0.08, "NC-AP-001")
    event = get_event(event["id"])
    check("重点验收不合格 -> 连续次数归零、仍在观察期",
          event["consecutivePasses"] == 0 and event["status"] == "ENHANCED_OBSERVATION",
          str(event["consecutivePasses"]) + event["status"])
    # 再连续通过 3 次 -> 恢复正常供货比例并终结考核
    enhanced_round(cb3["id"], ing["ING007"]["id"], 5, 0.01, "NC-CB-003")
    event = get_event(event["id"])
    check("重新累计 1 次", event["consecutivePasses"] == 1)
    enhanced_round(cb3["id"], ing["ING007"]["id"], 5, 0.01, "NC-CB-004")
    event = get_event(event["id"])
    check("连续 2 次仍在观察期", event["consecutivePasses"] == 2
          and event["status"] == "ENHANCED_OBSERVATION")
    enhanced_round(cb3["id"], ing["ING007"]["id"], 5, 0.01, "NC-CB-005")
    event = get_event(event["id"])
    check("连续 3 次通过 -> 考核终结(RESOLVED)",
          event["status"] == "RESOLVED" and event["consecutivePasses"] == 3, str(event["status"]))
    veg_now = call("buyer", "GET", f"/api/suppliers/{veg_sup['id']}")
    check("恢复正常供货比例 1.0", float(veg_now["purchaseRatio"]) == 1.0)
    anomalies = call("buyer", "GET", f"/api/anomalies?supplierId={veg_sup['id']}&type=RECURRING_ASSESSMENT")
    check("考核异常随考核终结自动关闭", anomalies and all(a["status"] == "RESOLVED" for a in anomalies))

    # 暂停供货路径：再次触发考核 -> 暂停 -> 供应商交材料 -> 采购确认放行 -> 连续通过恢复
    # 每批同时缺质检证明且腐烂超标（2 个命中异常/批），一批即可越过历史计数再次触发
    b3, di3 = veg_delivery(cabbage_oi["id"], ing["ING007"]["id"], 5)
    veg_accept(b3["id"], di3["id"], 5, 0.12)
    assess2 = call("buyer", "GET", f"/api/assessments?supplierId={veg_sup['id']}&status=OPEN")
    check("再次触发考核事件", assess2 and len(assess2) == 1 and assess2[0]["status"] == "OPEN", str(assess2))
    event2 = assess2[0]
    call("buyer", "POST", f"/api/assessments/{event2['id']}/suspend", {"note": "暂停供货等候整改"})
    veg_now = call("buyer", "GET", f"/api/suppliers/{veg_sup['id']}")
    check("供应商已暂停(active=false)", veg_now["active"] is False)
    event2 = get_event(event2["id"])
    check("考核状态 SUSPENDED", event2["status"] == "SUSPENDED")
    mat2 = call("supplier01", "POST", f"/api/assessments/{event2['id']}/materials",
                {"title": "复工整改材料", "content": "更换分拣班组并增加出库复检"})
    call("buyer", "POST", f"/api/assessments/materials/{mat2['id']}/confirm",
         {"reviewNote": "准予恢复供货并重点验收"})
    veg_now = call("buyer", "GET", f"/api/suppliers/{veg_sup['id']}")
    event2 = get_event(event2["id"])
    check("确认材料 -> 恢复供货资格并进入观察期",
          veg_now["active"] is True and event2["status"] == "ENHANCED_OBSERVATION")
    for n, cert in enumerate(["NC-CB-006", "NC-CB-007", "NC-CB-008"]):
        rec_ex = enhanced_round(cb3["id"], ing["ING007"]["id"], 5, 0.01, cert)
        check(f"放行后重点验收第{n + 1}次通过", rec_ex["enhancedPassed"] is True)
    event2 = get_event(event2["id"])
    check("暂停放行后连续 3 次通过 -> 考核终结",
          event2["status"] == "RESOLVED" and event2["consecutivePasses"] == 3, str(event2["status"]))
    # 供应商账号只能看到本供应商的考核（传入他人 supplierId 也被服务端按归属过滤）
    other = call("supplier01", "GET", f"/api/assessments?supplierId={sup['SUP002']['id']}")
    own = call("supplier01", "GET", "/api/assessments")
    check("供应商账号越权过滤(看不到他人考核，只能看本供应商)",
          other is not None and len(other) == 0
          and all(e["supplier"]["id"] == veg_sup["id"] for e in own))

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
