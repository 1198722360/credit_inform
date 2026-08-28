# 值守报警

一个面向两台 Android 手机的值守报警工具。客服端和接收端连接同一个自部署服务，任意一端点击手动报警后，另一端会全屏提示、循环响铃并持续震动，直到用户确认。

业务接口监控也集中运行在服务端。手机只连接中转服务，不直接保存或访问业务接口密钥。

## 功能

- 两个或多个客户端使用同一通信组互发人工报警。
- App 首页显示服务端在线状态、同组在线客户端数量和最近检测时间。
- Android 前台服务使用长轮询接收，支持开机恢复和 watchdog 重启。
- 报警会唤醒屏幕、循环播放闹钟铃声、强震动，并尝试临时退出静音/勿扰模式。
- 接收游标和未确认报警持久化；断网或 App 进程重启后继续处理。
- 服务端持久化事件，并可集中检测 HTTP 业务端点，将越界或连续错误广播给客户端。
- 填写管理员密钥后，可直接在 App 中新增、编辑、测试、停用或删除服务端监控项。
- 请求头、请求体和 URL 查询参数值只写不回显，管理界面只显示固定脱敏占位符。
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
2. 通信组：两端都填 `customer-service`。
3. 共享密钥：两端都填 `config.json` 中的 `sharedToken`。
4. 客户端名称：分别填写“客服手机”和“我的手机”。
5. 管理员密钥：仅需要管理服务端监控项的手机填写；客服端可以留空。
6. 打开“启用通信”，保存并检测。

两端首页都显示“服务端在线”后，从客服手机点击“向其他客户端报警”做一次真实测试。发送端不会接收自己发出的报警。

## 构建 Android App

需要 JDK 17 和 Android SDK 35：

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
