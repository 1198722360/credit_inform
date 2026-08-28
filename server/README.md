# CreditInform 中继与监控服务

这个服务同时完成两件事：

- Android 客户端之间的手动报警中继。
- 在服务端集中轮询业务接口，并将余额越界或连续检测失败广播给同组客户端。

Android App 只需配置本服务的地址、`groupId` 和共享密钥，不需要保存业务接口的密钥。
服务只使用 Python 标准库，不需要安装第三方包。

## 快速启动

需要 Python 3.11 或更高版本。

```bash
cd server
cp config.example.json config.json
# 修改 group id、共享密钥和初始监控项后启动
ADMIN_TOKEN='另一个独立的长随机密钥' python3 relay_server.py
```

默认监听 `0.0.0.0:8047`。检查服务：

```bash
curl http://127.0.0.1:8047/health
```

`config.json` 中的 `groups[].sharedToken` 是普通客户端密钥；`ADMIN_TOKEN` 只用于在
App 中增删改监控配置。两者必须不同。也可以设置 `RELAY_TOKEN`，让所有组共用一个
普通客户端密钥，适合只有一个通信组的最简部署。

## Docker 部署

```bash
cd server
mkdir -p runtime/config
cp config.example.json runtime/config/config.json
# 编辑完成后，让容器内固定的非 root 用户可读；目录中含共享密钥
sudo chown -R 10001:10001 runtime/config
sudo chmod 700 runtime/config
sudo chmod 600 runtime/config/config.json
docker build -t credit-inform-relay .
docker volume create credit-inform-relay-data
docker run -d --name credit-inform-relay --restart unless-stopped \
  -p 8047:8047 \
  -e ADMIN_TOKEN='另一个独立的长随机密钥' \
  -v "$PWD/runtime/config:/app/config:ro" \
  -v credit-inform-relay-data:/app/data \
  credit-inform-relay
```

也可以在 `server/.env` 中写入 `ADMIN_TOKEN=...`，准备好 `runtime/config/config.json`
后直接执行 `docker compose up -d --build`；缺少 `ADMIN_TOKEN` 时 Compose 会拒绝启动。
Compose 默认只绑定 `127.0.0.1:8047`，适合由同机反向代理提供 HTTPS。在受信任
局域网内直接测试时，可在 `.env` 设置 `BIND_ADDRESS=0.0.0.0`，但不要以明文 HTTP
暴露到公网。Compose 使用 Docker named volume 保存 `/app/data`，首次挂载会继承镜像内
UID 10001 的权限，无需给宿主机目录开放写权限。

生产环境应在 Caddy、Nginx 或云负载均衡后启用 HTTPS，不要通过公网明文传输共享密钥。
建议用 `--env-file` 或容器密钥管理功能注入 `ADMIN_TOKEN`，避免密钥留在命令历史中。

## 基础配置

完整示例见 `config.example.json`。基础文件包含通信组以及仅在第一次启动时导入的监控项：

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
`config.json` 中的初始监控项不会覆盖 App 已管理的数据。通信组和共享密钥仍会自动热加载，
管理数据文件被外部原子替换时也会热加载。默认每 2 秒检查一次文件变化。
示例监控默认关闭；在 App 中先执行一次“测试”，确认取值和换算正确后再启用。

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

除 `/health` 外都需要 `Authorization: Bearer <该组 sharedToken>`，请求和响应均为 JSON。
时间字段为 Unix epoch 毫秒，事件 ID/游标为递增 JSON number。

### 服务状态

```http
GET /health
GET /api/v1/status?groupId=customer-service
GET /api/v1/monitors?groupId=customer-service
```

`/health` 可用于 App 的服务连通性检测。`/api/v1/monitors` 返回每个服务端监控项的
最近原始值、换算值、HTTP 状态、错误、越界状态和下次检测时间，不返回请求密钥。

### 注册和心跳

```http
POST /api/v1/clients/register
POST /api/v1/clients/heartbeat
Authorization: Bearer <sharedToken>
Content-Type: application/json

{"groupId":"customer-service","clientId":"stable-device-id","clientName":"客服手机"}
```

响应中的 `cursor` 是服务端当前已分配事件 ID 的高水位。客户端仅在第一次注册时保存它，可以避免安装后
重放历史报警；后续重连应继续使用本地已确认的游标。响应还包含同组
`clients:{online,total}`。

### 手动报警和长轮询

```http
POST /api/v1/alerts

{"groupId":"customer-service","senderId":"device-a","senderName":"客服","message":"请立即处理"}
```

```http
GET /api/v1/alerts?groupId=customer-service&clientId=device-b&after=123&wait=25
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

## App 监控配置 API

这些端点只接受 `Authorization: Bearer <ADMIN_TOKEN>`。普通 `sharedToken` 或
`RELAY_TOKEN` 不能读写配置。

```http
GET    /api/v1/admin/monitors?groupId=customer-service
POST   /api/v1/admin/monitors
PUT    /api/v1/admin/monitors/{id}
DELETE /api/v1/admin/monitors/{id}?groupId=customer-service
POST   /api/v1/admin/monitors/{id}/test
```

新建请求是平铺的监控对象并包含 `groupId`；`id` 可省略，由服务端生成。管理 API 使用
`headersJson`（JSON 对象字符串），其他字段与上面的基础配置一致。测试请求体仅需
`{"groupId":"customer-service"}`，成功时返回 HTTP 状态、原始值、换算值和是否越界，
但不会改变正式监控状态或生成报警。

GET/POST/PUT 返回 `keepPlaceholder: "__KEEP__"`。安全规则如下：

- 所有 header 值固定返回 `__KEEP__`，不会暴露内容或长度。
- body 整体只写；已配置时只返回 `__KEEP__`。
- URL 中所有查询参数值固定返回 `__KEEP__`，URL fragment 不返回；不要把密钥放在路径中。
- 上游请求异常只返回脱敏后的错误，不回传原始 URL、header 或 body 内容。
- PUT 省略敏感字段，或原样提交 `__KEEP__`，表示保留原值。
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
| `RELAY_CONFIG_FILE` | `config.json` | 基础配置文件 |
| `RELAY_DATA_FILE` | `data/relay-state.json` | 持久事件文件；空字符串禁用持久化 |
| `MONITOR_STORE_FILE` | `data/monitor-configs.json` | App 管理的监控配置文件 |
| `HEARTBEAT_TIMEOUT_SECONDS` | `90` | 客户端在线判定时间 |
| `EVENT_RETENTION_SECONDS` | `86400` | 事件保留时间 |
| `MAX_EVENTS_PER_GROUP` | `1000` | 每组最多保留事件数 |
| `MAX_POLL_SECONDS` | `30` | 单次长轮询最长等待时间 |

兼容旧部署时也识别 `RELAY_ADMIN_TOKEN`，新部署应使用 `ADMIN_TOKEN`。
`BIND_ADDRESS` 是 `compose.yaml` 的宿主机端口绑定参数，默认 `127.0.0.1`；它不是容器内
Python 进程的监听变量。

## 双客户端冒烟

服务启动后，可用下面的命令先验证完整协议。把共享密钥改成 `config.json` 中该组的
`sharedToken`；接收方应得到一条 `manual` 报警，发送方的 `alerts` 应为空。

```bash
export RELAY_URL='http://127.0.0.1:8047'
export RELAY_GROUP='customer-service'
export RELAY_SHARED_TOKEN='替换为该组共享密钥'

RECEIVER_CURSOR="$(curl -fsS -X POST "$RELAY_URL/api/v1/clients/register" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"groupId\":\"$RELAY_GROUP\",\"clientId\":\"smoke-receiver\",\"clientName\":\"接收测试端\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["cursor"])')"

SENDER_CURSOR="$(curl -fsS -X POST "$RELAY_URL/api/v1/clients/register" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"groupId\":\"$RELAY_GROUP\",\"clientId\":\"smoke-sender\",\"clientName\":\"发送测试端\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["cursor"])')"

curl -fsS -X POST "$RELAY_URL/api/v1/alerts" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"groupId\":\"$RELAY_GROUP\",\"senderId\":\"smoke-sender\",\"senderName\":\"发送测试端\",\"message\":\"值守报警冒烟测试\"}"

curl -fsS "$RELAY_URL/api/v1/alerts?groupId=$RELAY_GROUP&clientId=smoke-receiver&after=$RECEIVER_CURSOR&wait=0" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN"

curl -fsS "$RELAY_URL/api/v1/alerts?groupId=$RELAY_GROUP&clientId=smoke-sender&after=$SENDER_CURSOR&wait=0" \
  -H "Authorization: Bearer $RELAY_SHARED_TOKEN"
```

## 测试

```bash
python3 -m unittest discover -s server/tests -v
```

测试覆盖鉴权、长轮询唤醒、发送方排除、首次注册游标、重启后事件 ID、监控检测与自动
报警、配置安全范围和首次导入，以及管理 API 的密钥不回显和掩码保留行为。
