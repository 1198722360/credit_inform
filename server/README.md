# CreditInform 中继与监控服务

这个服务同时完成两件事：

- Android 客户端之间的手动报警中继。
- 在服务端集中轮询业务接口，并将余额越界或连续检测失败广播给客户端。

Android App 只需配置本服务的地址和共享密钥，不需要填写通信组，也不需要保存业务接口的密钥。
普通 HTTP 监控只使用 Python 标准库；GateWay 加密接口需要 `cryptography`，Docker 镜像会自动安装。

## 快速启动

需要 Python 3.11 或更高版本。

```bash
cd server
python3 -m pip install -r requirements.txt
cp config.example.json config.json
# 修改共享密钥和初始监控项后启动
ADMIN_TOKEN='另一个独立的长随机密钥' python3 relay_server.py
```

默认监听 `0.0.0.0:8047`。检查服务：

```bash
curl http://127.0.0.1:8047/health
```

`config.json` 中唯一一项 `groups[].sharedToken` 是普通客户端密钥；`ADMIN_TOKEN` 只用于在
App 中增删改监控配置。两者必须不同。`groups[].id` 和监控项内的 `groupId` 是服务端为兼容
旧数据保留的内部标识，新客户端不需要配置或提交。也可以设置 `RELAY_TOKEN` 作为普通客户端
密钥。

## Docker 部署

```bash
cd server
mkdir -p runtime/config
cp config.example.json runtime/config/config.json
# 编辑完成后，让容器内固定的非 root 用户可读；目录中含共享密钥
sudo chown -R 10001:10001 runtime/config
sudo chmod 700 runtime/config
sudo chmod 600 runtime/config/config.json
mkdir -p relay-data
sudo chown 10001:10001 relay-data
sudo chmod 700 relay-data
docker build -t credit-inform-relay .
docker run -d --name credit-inform-relay --restart unless-stopped \
  -p 8047:8047 \
  -e ADMIN_TOKEN='另一个独立的长随机密钥' \
  -v "$PWD/runtime/config:/app/config:ro" \
  -v "$PWD/relay-data:/app/data" \
  credit-inform-relay
```

也可以在 `server/.env` 中写入 `ADMIN_TOKEN=...`，准备好 `runtime/config/config.json`
后直接执行 `docker compose up -d --build`；缺少 `ADMIN_TOKEN` 时 Compose 会拒绝启动。
Compose 默认只绑定 `127.0.0.1:8047`，适合由同机反向代理提供 HTTPS。在受信任
局域网内直接测试时，可在 `.env` 设置 `BIND_ADDRESS=0.0.0.0`，但不要以明文 HTTP
暴露到公网。Compose 使用项目目录下的 `relay-data` 保存 `/app/data`；启动前请让该目录
归属容器内的 UID 10001，并限制为仅服务用户可读写。

生产环境应在 Caddy、Nginx 或云负载均衡后启用 HTTPS，不要通过公网明文传输共享密钥。
建议用 `--env-file` 或容器密钥管理功能注入 `ADMIN_TOKEN`，避免密钥留在命令历史中。

## 基础配置

完整示例见 `config.example.json`。基础文件包含一个内部兼容组以及仅在第一次启动时导入的监控项：

```json
{
  "groups": [
    {
      "id": "customer-service",
      "sharedToken": "replace-with-a-long-random-token"
    }
  ],
  "monitors": [
    {
      "id": "main-balance",
      "groupId": "customer-service",
      "name": "主账户余额",
      "url": "https://example.com/api/balance",
      "method": "POST",
      "headers": {
        "Authorization": "Bearer replace-with-api-token",
        "Content-Type": "application/json"
      },
      "body": {
        "account": "main"
      },
      "jsonPath": "data.credit",
      "operator": "<",
      "threshold": 10,
      "multiplier": 0.0166666667,
      "offset": 0,
      "pollIntervalSeconds": 60,
      "timeoutSeconds": 10,
      "errorThreshold": 3,
      "repeatAlertSeconds": 300,
      "enabled": false
    }
  ]
}
```

换算规则为：`实际值 = 原始值 * multiplier + offset`。支持 `<`、`<=`、`>`、`>=`、
`=` 和 `==`。`jsonPath` 支持：

- `credit`
- `data.credit`
- `accounts[0].credit`
- `$[0].credit`
- `balance_infos[currency=CNY].total_balance`

轮询间隔最少 5 秒，请求超时范围为 1 到 60 秒。`repeatAlertSeconds` 必须为 `0`
（只在状态变化时提醒）或至少 15 秒。`body` 是对象/数组时会作为 JSON
发送；字符串会按原文发送。

首次启动会把 `config.json` 的 `monitors` 原子导入
`data/monitor-configs.json`。之后 App 的管理界面读写这个独立文件；修改
`config.json` 中的初始监控项不会覆盖 App 已管理的数据。内部组和共享密钥仍会自动热加载，
管理数据文件被外部原子替换时也会热加载。默认每 2 秒检查一次文件变化。
示例监控默认关闭；在 App 中先执行一次“测试”，确认取值和换算正确后再启用。

## GateWay 加密接口

已按《接入文档 · GateWay.pdf》接入 HKDF-SHA256 和 AES-256-GCM。每次调用自动生成新的
nonce、时间戳和 IV，响应解密绑定本次请求 nonce；所有认证和 SK 的读取都在服务端完成。
网页和 App 的新增/编辑监控界面都可选择“GateWay”。

先在服务器上设置环境变量 `GATEWAY_SK=gwsk_...`。使用 Docker Compose 时，在
`server/.env` 中增加这一行，然后执行 `docker compose up -d --build`，使新进程载入 SK。
SK 从 GateWay 控制台的 API 凭证页取得；不要在 App、网页、请求头或请求体里填写 SK。

在网页或 App 新建监控，选择 GateWay 后填写：

| 配置 | 余额查询填写值 |
| --- | --- |
| 接口地址 | `https://op.privapi.cc/open/v1/balance/query` |
| Access Key（AK） | API 凭证页中的 `gwak_` 加 24 位十六进制字符 |
| 服务器 SK 环境变量名 | `GATEWAY_SK`（填写名称，不是密钥值） |
| 请求体 / 业务参数 | 留空或 `{}` |
| JSON 字段路径 | `data.balance` |
| 倍率 / 偏移量 | `1` / `0`，金额单位为美元 |
| 检测间隔 | 默认 60 秒，最少 30 秒 |
| 报警值 | 按所需的美元余额填写，例如低于 `10` |

先保存并测试，确认读数后启用。凭证需要勾选 `balance:read`。服务端也支持文档中的
`/open/v1/nodes/query`、`/open/v1/logs/query`、`/open/v1/bans/query` 加密传输；
监控时仍需选择返回值中的数值字段，例如 `data.items[0].quota.five_hour_utilization`。
这些接口需要各自的权限；分页参数需在业务参数中配置，监控只读取指定页。
推送在文档中尚未开放，本实现使用轮询。

基础配置文件示例见 `config.gateway.example.json`。首次部署可复制该文件，并替换共享密钥和 AK。
已有部署请通过网页或 App 新建，或使用管理 API：

```json
{
  "name": "GateWay 余额（美元）",
  "provider": "gateway",
  "url": "https://op.privapi.cc/open/v1/balance/query",
  "method": "POST",
  "gatewayAccessKey": "gwak_你的24位十六进制AK",
  "gatewaySecretKeyEnv": "GATEWAY_SK",
  "headersJson": "{}",
  "body": {},
  "jsonPath": "data.balance",
  "operator": "<",
  "threshold": 10,
  "multiplier": 1,
  "offset": 0,
  "pollIntervalSeconds": 60,
  "enabled": false
}
```

`provider` 省略时仍为普通 HTTP；请求头由 GateWay 适配器自动生成，配置中应留空。
URL 必须使用 HTTPS 和完整接口路径，不带查询串、fragment、用户信息或结尾斜杠。
业务参数必须是 JSON 对象，最终加密信封不得超过 32 KiB。金额使用 `Decimal` 解析、换算
和阈值比较，只有返回给现有客户端的显示读数转为 JSON number；GateWay 的 `=` 是精确十进制比较。

认证错误和加密业务错误会显示安全的错误码、HTTP 状态和合法的 `request_id`，不会回传
上游原始错误正文；连续失败仍按既有规则报警。限流返回 `Retry-After` 时，自动检测会相应延后
（最长等待一天），不会立即自动重试。服务器时间应保持同步，凭证 IP 白名单应包含服务器出口 IP。

多个凭证可分别使用不同的环境变量名。Compose 默认只注入 `GATEWAY_SK`；其他变量需自行在
`compose.yaml` 的 `environment` 中映射。重置 SK 后更新服务器环境变量并重建/重启进程。

## 报警策略

- 数值从正常变为越界时立即生成 `monitor_breach` 事件。
- 越界未恢复时，按 `repeatAlertSeconds` 重复报警；设为 `0` 表示只在状态变化时报警。
- 检测连续失败 `errorThreshold` 次后生成 `monitor_error`，之后也按重复间隔报警。
- 一次成功检测会清除连续错误；数值恢复正常会清除越界状态。
- 手动报警事件类型为 `manual`，不会回发给发送报警的客户端。

事件默认持久化并保留 24 小时，每组最多 1000 条。事件 ID 和下一个自增值都会原子
落盘，因此正常重启后不会从 1 重新开始。部署容器时必须持久挂载 `/app/data`。
当前 JSON 状态存储按单实例设计，不要让多个服务进程或多个容器同时写同一数据卷。
若状态文件损坏或无法解析，服务会拒绝启动并保留原文件，不会静默覆盖现场；应先备份
损坏文件，再从可靠备份恢复，或明确移走该文件以创建全新的服务实例。
监控运行状态不持久化；服务重启后若端点仍处于越界或持续失败状态，可能立即再次报警。
这是偏向不漏报的保守行为。

## 客户端 API

除 `/health` 外都需要 `Authorization: Bearer <sharedToken>`，请求和响应均为 JSON。
时间字段为 Unix epoch 毫秒，事件 ID/游标为递增 JSON number。

服务端恰好配置一个组时，以下所有 API 都可以省略 `groupId`，这是 App 使用的默认方式。
旧客户端仍可显式提交原有 `groupId`。只有多组兼容部署才必须显式提交，否则返回
`group_required`；服务端不会猜测目标组。

### 服务状态

```http
GET /health
GET /api/v1/status
GET /api/v1/monitors
```

`/health` 可用于服务连通性检测。配置、调度线程或事件持久化异常时返回 HTTP 503 和
`status: "degraded"`，Docker 健康检查会据此失败；一次事件状态重新成功落盘后，存储
故障状态会恢复。`/api/v1/monitors` 返回同样的配置/存储健康信息，以及每个监控项的
请求方法、脱敏 URL、最近原始值、换算值、HTTP 状态、错误、越界状态和下次检测时间。
URL 的查询参数值和用户信息会替换为 `__KEEP__`，fragment 会移除，header 和 body 不返回。
监控项按服务端管理文件中的数组顺序返回。

### 注册和心跳

```http
POST /api/v1/clients/register
POST /api/v1/clients/heartbeat
Authorization: Bearer <sharedToken>
Content-Type: application/json

{"clientId":"stable-device-id"}
```

响应中的 `cursor` 是服务端当前已分配事件 ID 的高水位，`cursorToken` 是该游标对应的
随机令牌。客户端仅在第一次注册时保存它们，可以避免安装后重放历史报警；后续重连应
继续使用本地已确认的游标和令牌。响应还包含 `clients:{online,total}`。省略旧版
`clientName` 时，服务端使用 `clientId` 作为显示名。

### 手动报警和长轮询

```http
POST /api/v1/alerts

{"senderId":"device-a","message":"请立即处理"}
```

```http
GET /api/v1/alerts?clientId=device-b&after=123&afterToken=62bb73f844af48cfb2c640a8e9cb6371&wait=25
```

`wait` 最大 30 秒。响应格式：

```json
{
  "serverInstanceId": "f0d7441e73ae4dd7b621328f18b03bfd",
  "alerts": [
    {
      "id": 124,
      "eventUid": "62bb73f844af48cfb2c640a8e9cb6371",
      "groupId": "customer-service",
      "type": "manual",
      "senderId": "device-a",
      "senderName": "客服",
      "message": "请立即处理",
      "createdAt": 1787910000000,
      "monitorId": null,
      "monitorName": null
    }
  ],
  "cursor": 124,
  "cursorToken": "62bb73f844af48cfb2c640a8e9cb6371",
  "cursorValid": true,
  "headCursor": 124,
  "serverTime": 1787910000001
}
```

客户端应在成功触发本批所有报警后再持久化 `cursor`。`headCursor` 是服务端已分配事件 ID
的单调递增高水位，不会因 24 小时事件清理而倒退；一次最多返回 100 条时，`cursor` 可能暂时小于
`headCursor`。`serverInstanceId` 会和事件状态一起持久化；数据卷被重建时它会改变，
客户端据此重置旧游标，避免新服务的事件编号从较小值开始后被永久跳过。每个事件另有
持久化的随机 `eventUid`；Android 用它标识本地未确认报警，因此服务实例变化或从旧备份
回滚后复用数字 ID 时，新旧报警也不会互相覆盖或误确认。

客户端每次轮询都应把本地 `cursorToken` 作为 `afterToken` 一并提交。服务端若发现该数字
游标已因旧备份恢复而指向另一事件，会返回 `cursorValid:false`、`cursor:0`、
`cursorToken:null` 和空 `alerts`；客户端必须清空游标，再从 0 重拉。正常响应为
`cursorValid:true`，并返回新 `cursor` 对应的 `cursorToken`。未提供 `afterToken` 的旧客户端
仍可使用接口，但无法检测“备份回滚后事件编号恰好追平”这一情况。

## 网页管理

浏览器打开服务端根地址（例如 `http://服务器:8047/`），用 `ADMIN_TOKEN` 登录，即可
新建、编辑、测试、复制、删除和排序监控，并查看每个监控的最新读数。网页调用的就是下面的
监控配置 API，和 App 里的修改实时互通；同一监控在两处同时修改时，后保存的一方会收到冲突
提示并载入最新配置。

页面由 `admin_web.html` 提供，只允许运行它自带的脚本，不加载任何外部资源。管理员密钥
默认只保存在当前浏览器标签页，勾选“记住密钥”才会长期保存在这台电脑上。明文 HTTP
部署下，密钥会以明文经过网络，请只在可信网络里使用，或在前面加 HTTPS 反向代理。

`GET /api/v1/admin/monitors` 的响应额外包含 `statuses`（与 `/api/v1/monitors`
相同的脱敏实时状态），供网页显示读数。

## App 监控配置 API

这些端点只接受 `Authorization: Bearer <ADMIN_TOKEN>`。普通 `sharedToken` 或
`RELAY_TOKEN` 不能读写配置。

```http
GET    /api/v1/admin/monitors
POST   /api/v1/admin/monitors
PUT    /api/v1/admin/monitors/{id}
DELETE /api/v1/admin/monitors/{id}
POST   /api/v1/admin/monitors/{id}/test
POST   /api/v1/admin/monitors/{id}/copy
POST   /api/v1/admin/monitors/reorder
POST   /api/v1/admin/monitors/refresh
```

新建请求是平铺的监控对象；`id` 可省略，由服务端生成。管理 API 使用
`headersJson`（JSON 对象字符串），其他字段与上面的基础配置一致。测试请求体仅需
`{}`，成功时返回 HTTP 状态、原始值、换算值和是否越界，
同时更新共享监控列表中的最近检测时间、HTTP 状态和最新值。手动测试不会生成报警，也不会
推迟下一次自动检测；测试失败只在本次响应中返回，不累计自动检测错误。

批量刷新请求体也是 `{}`。服务端会并发测试当前所有已启用监控，成功项立即更新共享监控
快照，停用项不会执行。某一项失败不会阻断其他项，批处理本身仍返回 HTTP 200，并通过
`ok:false` 和逐项结果报告失败；鉴权或组解析失败才返回非 2xx：

```json
{
  "ok": false,
  "serverTime": 1787910000001,
  "total": 2,
  "succeeded": 1,
  "failed": 1,
  "results": [
    {
      "monitorId": "balance-a",
      "monitorName": "主账户余额",
      "ok": true,
      "result": {"httpCode": 200, "rawValue": 60, "value": 1, "breached": true}
    },
    {
      "monitorId": "balance-b",
      "monitorName": "备用账户余额",
      "ok": false,
      "error": {"code": "monitor_test_failed", "message": "HTTP 503", "httpCode": 503}
    }
  ]
}
```

批量结果不会包含 URL、header 或 body；手动刷新同样不会生成报警、累计自动检测错误或
推迟下一次自动检测。

复制请求体仅需 `{}`。服务端直接复制原始配置，因此 URL、
header 和 body 中的密钥会完整保留。副本使用新的唯一 ID 和名称、
`revision: 1`，默认停用并插入源监控项之后；名称依次使用 `原名（副本）`、
`原名（副本2）`、`原名（副本3）` 并避开已有名称。

重排请求体必须同时提交客户端读取到的旧顺序和目标顺序：

```json
{
  "expectedMonitorIds": ["balance-a", "balance-b"],
  "monitorIds": ["balance-b", "balance-a"]
}
```

两个数组都必须包含该组全部且唯一的监控 ID。服务端在锁内确认
`expectedMonitorIds` 仍与当前顺序完全一致，然后原子写入新顺序；其他组的存储槽位不会
变化。配置已被另一客户端新增、删除或重排时返回 HTTP 409 和
`order_conflict`，客户端应重新加载后再操作。成功响应的 `monitors` 是完整配置列表。
管理配置和共享状态接口都按这个持久化顺序返回。

管理员需要直接查看和编辑密钥，因此管理列表、创建、更新、复制和重排响应会返回完整原始
URL、`headersJson` 和 body。只持有普通共享密钥的 `/api/v1/monitors` 仍返回脱敏 URL，
并且不返回 header 或 body。安全规则如下：

- 管理员响应包含业务接口密钥，必须使用 HTTPS，并且不要把 `ADMIN_TOKEN` 配置到普通客服端。
- 上游请求异常只返回脱敏后的错误，不回传原始 URL、header 或 body 内容。
- 为兼容旧客户端，响应仍包含 `keepPlaceholder: "__KEEP__"`；PUT 省略敏感字段或提交
  `__KEEP__` 仍表示保留原值。
- PUT 提交 `body: null` 会明确清除已有请求体。
- 每项返回 `revision` 和 `updatedAt`。PUT 可携带 `revision`；版本过期返回 HTTP 409，
  防止两个客户端互相覆盖配置。

管理存储包含业务接口密钥，文件通过原子替换写入且新文件权限默认为仅服务用户可读写。
仍应限制 `runtime/data` 和 `runtime/config` 的系统权限，并妥善备份。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `8047` | 监听端口 |
| `ADMIN_TOKEN` | 空 | App 管理监控项的独立密钥 |
| `RELAY_TOKEN` | 空 | 可选的所有组通用客户端密钥 |
| `GATEWAY_SK` | 空 | GateWay 服务器 SK；可由监控项的 `gatewaySecretKeyEnv` 指定其他环境变量 |
| `RELAY_CONFIG_FILE` | `config.json` | 基础配置文件 |
| `RELAY_DATA_FILE` | `data/relay-state.json` | 持久事件文件；空字符串会禁用持久化并使健康检查降级 |
| `MONITOR_STORE_FILE` | `data/monitor-configs.json` | App 管理的监控配置文件 |
| `HEARTBEAT_TIMEOUT_SECONDS` | `90` | 客户端在线判定时间 |
| `EVENT_RETENTION_SECONDS` | `86400` | 事件保留时间 |
| `MAX_EVENTS_PER_GROUP` | `1000` | 每组最多保留事件数 |
| `MAX_POLL_SECONDS` | `30` | 单次长轮询最长等待时间 |

兼容旧部署时也识别 `RELAY_ADMIN_TOKEN`，新部署应使用 `ADMIN_TOKEN`。
`BIND_ADDRESS` 是 `compose.yaml` 的宿主机端口绑定参数，默认 `127.0.0.1`；它不是容器内
Python 进程的监听变量。

## 双客户端冒烟

服务启动后，可用下面的命令先验证完整协议。把共享密钥改成 `config.json` 中的
`sharedToken`；接收方应得到一条 `manual` 报警，发送方的 `alerts` 应为空。
自动测试会先建立长轮询再发送，并对事件 UID、游标令牌和发送方排除做断言。测试会产生真实
报警，不要在仍有值守客户端在线的生产服务上执行。

```bash
python3 smoke_test.py \
  --url http://127.0.0.1:8047 \
  --token '替换为共享密钥'
```

多组旧部署仍可额外传入 `--group customer-service`。

需要逐步检查原始响应时，也可以使用以下 `curl` 命令：

```bash
export RELAY_URL='http://127.0.0.1:8047'
export RELAY_SHARED_TOKEN='替换为共享密钥'

RECEIVER_REGISTRATION="$(curl -fsS -X POST "$RELAY_URL/api/v1/clients/register" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"smoke-receiver"}')"
RECEIVER_CURSOR="$(printf '%s' "$RECEIVER_REGISTRATION" | python3 -c 'import json,sys; print(json.load(sys.stdin)["cursor"])')"
RECEIVER_TOKEN="$(printf '%s' "$RECEIVER_REGISTRATION" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("cursorToken") or "")')"
RECEIVER_TOKEN_QUERY="${RECEIVER_TOKEN:+&afterToken=$RECEIVER_TOKEN}"

SENDER_REGISTRATION="$(curl -fsS -X POST "$RELAY_URL/api/v1/clients/register" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"smoke-sender"}')"
SENDER_CURSOR="$(printf '%s' "$SENDER_REGISTRATION" | python3 -c 'import json,sys; print(json.load(sys.stdin)["cursor"])')"
SENDER_TOKEN="$(printf '%s' "$SENDER_REGISTRATION" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("cursorToken") or "")')"
SENDER_TOKEN_QUERY="${SENDER_TOKEN:+&afterToken=$SENDER_TOKEN}"

curl -fsS -X POST "$RELAY_URL/api/v1/alerts" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"senderId":"smoke-sender","message":"值守报警冒烟测试"}'

curl -fsS "$RELAY_URL/api/v1/alerts?clientId=smoke-receiver&after=$RECEIVER_CURSOR$RECEIVER_TOKEN_QUERY&wait=0" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN"

curl -fsS "$RELAY_URL/api/v1/alerts?clientId=smoke-sender&after=$SENDER_CURSOR$SENDER_TOKEN_QUERY&wait=0" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN"
```

## 测试

```bash
python3 -m unittest discover -s server/tests -v
```

测试覆盖鉴权、长轮询唤醒、发送方排除、游标令牌与旧备份回滚、重启后事件 ID、存储
健康探测、配置热加载、监控检测与自动报警、配置安全范围和首次导入，以及管理 API 的
管理员密钥回显隔离、服务端安全复制、单项及批量测试状态同步、部分失败隔离、冲突保护重排
和顺序持久化行为。

GateWay 测试使用官方公开的合成凭证和固定向量，核对密钥派生、请求密文和响应明文；
还覆盖错误密钥/AAD、响应重放、明文及加密错误、请求大小、限流等待、十进制边界、
配置持久化和自动余额报警。测试不调用真实业务接口。
