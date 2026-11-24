#!/usr/bin/env python3
"""从爱发电拉取「MicMask 赞助」方案的赞助者，生成 sponsors/sponsors.json 给 App 的赞赏墙用。

环境变量：AFDIAN_USER_ID、AFDIAN_TOKEN（爱发电 开发者后台 afdian.com/dashboard/dev）
         AFDIAN_PLAN_ID（只统计这个方案的订单）、SHOW_REMARK（1=展示留言，默认 1）
只用标准库。令牌只放 GitHub Actions Secrets，绝不进仓库。
"""
import hashlib, json, os, re, sys, time, urllib.request

API = "https://afdian.com/api/open/"
UID = os.environ["AFDIAN_USER_ID"]
TOKEN = os.environ["AFDIAN_TOKEN"]
PLAN = os.environ["AFDIAN_PLAN_ID"]
SHOW_REMARK = os.environ.get("SHOW_REMARK", "1") == "1"
OUT = os.environ.get("OUT", "sponsors/sponsors.json")


def call(name, params):
    p = json.dumps(params, separators=(",", ":"), ensure_ascii=False)
    ts = int(time.time())
    sign = hashlib.md5(f"{TOKEN}params{p}ts{ts}user_id{UID}".encode()).hexdigest()
    body = json.dumps({"user_id": UID, "params": p, "ts": ts, "sign": sign}).encode()
    req = urllib.request.Request(API + name, body, {"Content-Type": "application/json", "User-Agent": "MicMask-sponsors"})
    for i in range(3):
        try:
            j = json.load(urllib.request.urlopen(req, timeout=30))
            if j.get("ec") != 200:
                raise SystemExit(f"{name} 失败：{j.get('ec')} {j.get('em')}")
            return j["data"]
        except SystemExit:
            raise
        except Exception as e:
            if i == 2: raise
            time.sleep(3)


def all_pages(name, extra=None):
    page, out = 1, []
    while True:
        d = call(name, {"page": page, "per_page": 100, **(extra or {})})
        out += d.get("list", [])
        if page >= int(d.get("total_page") or 1): return out
        page += 1


def avatar(u):
    u = (u or "").strip()
    if u and not u.startswith("http"): u = "https://" + u.lstrip("/")
    return u


def main():
    agg = {}
    orders = [o for o in all_pages("query-order") if o.get("plan_id") == PLAN and int(o.get("status", 0)) == 2]
    orders.sort(key=lambda o: int(o.get("create_time") or 0))
    for o in orders:
        a = agg.setdefault(o["user_id"], {"amount": 0.0, "first": 1 << 62, "last": 0, "remark": "", "expire": 0})
        a["amount"] += float(o.get("total_amount") or 0)
        t = int(o.get("create_time") or 0)
        # 赞助期限：每单 month 个月（按 30 天算），连续续费顺延；App 用 expire 区分「当前赞助」和「历史赞助」
        a["expire"] = max(a["expire"], t) + max(1, int(o.get("month") or 1)) * 30 * 86400
        a["first"] = min(a["first"], t)
        if t >= a["last"]:
            a["last"] = t
            if (o.get("remark") or "").strip(): a["remark"] = o["remark"].strip()
    ids = list(agg)
    users = {}
    for i in range(0, len(ids), 50):
        for s in call("query-sponsor", {"user_id": ",".join(ids[i:i + 50]), "per_page": 100}).get("list", []):
            u = s.get("user") or {}
            users[u.get("user_id")] = u
    rows = []
    for uid, a in agg.items():
        u = users.get(uid, {})
        r = {"name": (u.get("name") or "爱发电用户").strip()[:24], "avatar": avatar(u.get("avatar")),
             "amount": round(a["amount"], 2), "since": a["first"], "last": a["last"], "expire": a["expire"]}
        if SHOW_REMARK and a["remark"]:
            # 留言里的设备 ID（赞助者用来隐藏推荐位）不公开到赞赏墙
            rm = re.sub(r"(设备\s*ID[:：]?\s*)?[A-Za-z0-9_-]{22,64}", "", a["remark"]).strip(" ,，;；:：\n")
            if rm: r["remark"] = rm[:60]
        rows.append(r)
    rows.sort(key=lambda r: (-r["amount"], r["since"]))
    doc = {"format": 1, "updated": int(time.time()), "count": len(rows),
           "total": round(sum(r["amount"] for r in rows), 2), "sponsors": rows}
    os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
    old = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
    # updated 字段每次都变，比较时忽略，避免名单没变也天天提交
    strip = lambda s: {k: v for k, v in json.loads(s).items() if k != "updated"} if s else None
    if strip(old) == {k: v for k, v in doc.items() if k != "updated"}:
        print("名单无变化"); return
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
    print(f"写入 {OUT}：{len(rows)} 位，合计 ¥{doc['total']}")


if __name__ == "__main__":
    main()
