# 服务端上游余额监控（3.0.0）

Android App 从 sub2api 的只读余额 API 拉取渠道、余额、检测时间和报警规则，上游 key、请求头及 GateWay AK/SK 仅保存在 sub2api。手机只保存 sub2api 地址、专用余额查询令牌及脱敏余额缓存，使用 Android Keystore 加密，并继续承担本机响铃、振动和后台告警。

1. 在 sub2api「上游余额」配置渠道和阈值，启用定时查询。
2. 开启余额查询 API，按需配置 IP/CIDR 白名单，生成专用查询令牌。
3. App「监控设置」填写 sub2api 的 HTTPS 基础地址和查询令牌，点击「保存并连接」。无需导入或配置上游凭据。

接口为 `GET /api/v1/upstream-balance/status`，使用 `Authorization: Bearer <查询令牌>`。App 不使用 sub2api 管理员 token，不允许重定向转发凭据。仅 loopback 支持 HTTP，供开发测试或 SSH 隧道使用。白名单校验的是手机实际公网出口；若通过 `104.238.222.175` 出口联网，应将该地址加入白名单。

升级后会删除旧本机监控配置中的上游凭据，保留声音、振动和音量设置；需重新填写服务端连接。App 不会把旧凭据自动上传。新增、编辑和删除渠道统一在 sub2api 管理页面完成。后台按服务端间隔轮询，过期余额会报告异常；重复快照不会重复累计查询失败。服务端/API 连接故障使用本机紧急提醒，余额阈值告警保留未确认队列。

构建验证：`JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest assembleDebug lintDebug`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

以下为旧版迁移参考，3.0.0 不再提供本地上游配置入口。

---

# 值守报警

> **当前 Android 版本：2.1.0，本机独立监控版。** 下方折叠的旧版部署文档仅作历史参考，当前 App 不连接中转服务器，也不需要部署 `server/`。

当前版本由手机直接查询用户配置的业务接口。监控配置、AK／SK、请求凭据、最近检测状态和未确认报警都存储在手机中；规则判断、定时检测和报警在本机完成。整个监控数据文件使用 Android Keystore AES-GCM 加密，且禁用系统备份。

- 支持普通 HTTP GET／POST 接口和 GateWay 加密接口。
- 支持 JSON 字段与数组路径、数值换算、阈值比较、连续失败报警和重复报警间隔。
- 可在本机新增、编辑、复制、删除和拖动排序监控项，无需管理员密钥。
- 手动报警仅在当前手机响铃，不再向其他手机发送消息。
- 本机报警测试、查看已有数据和编辑配置可离线使用；查询实时余额等业务数据仍需要手机联网直连该业务接口。

首次使用：安装 APK → 点击 `+` 添加业务接口及报警规则 → 打开「监控设置」并启用本机监控 → 按应用提示授予通知、全屏提醒、电池优化等必要权限。GateWay 配置中直接填写完整 AK 和 SK，不再填写服务器环境变量名。单项「测试」更新检测结果但不触发报警；启用监控后的定时检测及全部刷新会按规则报警。

从旧版升级时保留铃声、振动、音量和启用设置，旧服务器连接信息会被移除。旧版完整监控配置只保存在服务器上，App 没有可迁移的完整副本，因此需要在手机中重新添加监控项；升级过程不会访问旧服务器。

构建需要 JDK 17、Android SDK 35，在 `local.properties` 设置 `sdk.dir` 即可，无需任何中转服务密钥：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

导入配置：进入「监控设置」→「导入配置并覆盖」，从手机文件选择器中选择 UTF-8 JSON 文件。示例见 [config.local.example.json](config.local.example.json)。支持包含 `monitors` 数组的对象（兼容旧服务器配置）或直接提供监控数组；支持 `headers` 对象或 `headersJson`，以及字符串／对象／数组请求体。GateWay 必须提供 `gatewayAccessKey` 和 `gatewaySecretKey` 的实际值，服务器环境变量名不能替代 SK。

文件最多 1 MiB、500 个监控项。全部校验成功后，按文件顺序覆盖原监控列表，清空旧检测状态及未确认报警；铃声、音量和监控总开关保持不变。空的 `monitors` 数组会清空监控列表。文件无效或取消选择不会覆盖原配置。导入会等待当前检测批次结束；如总开关已启用，随后自动按新配置继续检测。文件读取、校验和加密保存均在本机完成。

Android 前台服务负责后台检测，并保留开机恢复和 watchdog。手机关机、强行停止应用、系统限制后台运行或无网络时，无法继续获取实时业务数据；应用运行期间的网络失败会计入连续错误报警。

<details>
<summary>旧版双机中转模式与服务器部署文档（仅历史参考，当前 App 不使用）</summary>

一个面向两台 Android 手机的值守报警工具。客服端和接收端连接同一个自部署服务，任意一端点击手动报警后，另一端会全屏提示、循环响铃并持续震动，直到用户确认。

业务接口监控也集中运行在服务端。手机只连接中转服务，不直接保存或访问业务接口密钥。

## 功能

- 两个或多个客户端连接同一服务端后互发人工报警。
- App 首页显示服务端在线状态、在线客户端数量和最近检测时间。
- Android 前台服务使用长轮询接收，支持开机恢复和 watchdog 重启。
- 报警会唤醒屏幕、循环播放闹钟铃声、强震动，并尝试临时退出静音/勿扰模式。
- 接收游标和未确认报警持久化；断网或 App 进程重启后继续处理。
- 客户端与服务端会校验游标令牌；即使服务端从旧备份恢复并复用事件编号，也会重放而不是跳过新报警。
- 服务端持久化事件，并可集中检测 HTTP 业务端点，将越界或连续错误广播给客户端。
- 支持 GateWay 开放接口加密余额监控；网页可直接保存 AK/SK，SK 仅保存在服务端且不回显，也兼容服务器环境变量。网页和 App 均可配置报警规则。
- 填写管理员密钥后，可直接在 App 中新增、编辑、测试、停用或删除服务端监控项。
- 管理员可查看和编辑完整 URL、请求头与请求体；普通客户端仍只看到脱敏地址。
- 上游请求异常也不会把原始 URL、请求头或请求体内容回传到手机。
- 共享密钥与管理员密钥在 Android 端使用 Keystore AES-GCM 加密保存。

## 最快部署

需要一台可从两部手机访问的服务器，并已安装 Docker Compose。

生成两个不同的随机密钥：

```bash
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

启动服务：

```bash
cd server
mkdir -p runtime/config
cp config.example.json runtime/config/config.json
mkdir -p relay-data
sudo chown 10001:10001 relay-data
sudo chmod 700 relay-data
```

编辑 `runtime/config/config.json`，至少修改：

```json
{
  "groups": [
    {
      "id": "customer-service",
      "sharedToken": "第一个随机密钥"
    }
  ],
  "monitors": []
}
```

创建 `server/.env`：

```dotenv
ADMIN_TOKEN=第二个随机密钥
```

Compose 默认只监听服务器本机的 `127.0.0.1:8047`，适合由同机的 Caddy 或 Nginx
提供 HTTPS。仅在受信任局域网内直接测试时，可在 `.env` 另加
`BIND_ADDRESS=0.0.0.0`；不要把明文 HTTP 端口直接暴露到公网。

然后启动并检查：

```bash
docker compose up -d --build
curl -fsS http://127.0.0.1:8047/health
```

服务默认发布在本机 `8047` 端口。公网部署必须在 Caddy、Nginx 或云负载均衡后配置 HTTPS，不能通过明文 HTTP 传输密钥。服务端的完整配置、监控规则和 API 见 [server/README.md](server/README.md)。

## 两台手机配置

在两台手机安装同一个 APK，然后分别打开“连接配置”：

1. 服务端地址：填写相同的 HTTPS 基础地址，例如 `https://alarm.example.com`，不要附加 `/api`。
2. 共享密钥：两端都填 `config.json` 中的 `sharedToken`。
3. 管理员密钥：仅需要管理服务端监控项的手机填写；客服端可以留空。
4. 打开“启用通信”，保存并检测。

当前发布包已预置部署地址和共享密钥，新安装只需打开连接配置并启用通信；客户端 ID
会在首次启动时随机生成。覆盖升级会保留原有地址、密钥、客户端 ID 和报警设置。

两端首页都显示“服务端在线”后，从客服手机点击“向其他客户端报警”做一次真实测试。发送端不会接收自己发出的报警。

## 构建 Android App

需要 JDK 17 和 Android SDK 35：

先在未纳入版本控制的 `local.properties` 中配置发布包的默认共享密钥：

```properties
relay.defaultSharedToken=服务端的共享密钥
```

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK 生成在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 手机权限

为了在夜间和锁屏状态下可靠报警，请按首页提示开启：

1. 通知权限。
2. 勿扰模式控制权限。
3. 全屏紧急提醒权限（Android 14 及以上）。
4. 忽略电池优化。
5. “闹钟和提醒”权限。
6. 国产系统额外提供的自启动、后台运行和后台任务锁定权限。

普通 Android App 无法绕过“强行停止”、关机、无网络或企业设备策略。当前实现使用前台服务、`START_STICKY`、开机恢复、唤醒锁、Alarm 音频用途和 watchdog，属于 Android 常规权限内可实现的可靠方案。

## 目录

- `app/`：Android 客户端。
- `server/`：当前 App 使用的 Python 中转和集中监控服务。

新部署只使用 `server/`。

</details>
