# dynamic-bot-onebot

OneBot 网关插件，用于让 dynamic-bot 通过 OneBot 客户端向 QQ 群或好友发送消息，也可以接收 QQ 消息用于命令、链接解析和普通插件功能。

常见客户端包括 NapCat 和 LLOneBot。

## 功能

- 正向 WebSocket：插件主动连接 OneBot 客户端。
- 反向 WebSocket：OneBot 客户端连接插件。
- 支持多个连接和多个 Bot 账号。
- 发送群消息和好友消息。
- 发送合并转发消息。
- 接收入站消息，并以统一入口提交给主项目。
- 主项目统一识别命令、自动链接解析和普通插件消费。
- 支持撤回插件发送的消息。
- 自动选择更合适的图片发送方式。
- 正向连接断开后自动重连，并逐步拉长重连间隔。

## 安装

构建插件：

```powershell
.\gradlew.bat fatJar --offline
```

把生成的 `*-all.jar` 放入主程序的：

```text
plugins/
```

然后启动或重启 dynamic-bot，在后台启用插件。

## 连接方式

### 正向 WebSocket

适合 OneBot 客户端已经开启 WebSocket 服务的场景。插件会主动连接客户端。

示例：

```yaml
mode: FORWARD_WS
connections:
  - name: 本机 NapCat
    url: ws://127.0.0.1:6700
    accessToken: ""
    enabled: true
reconnect: true
```

### 反向 WebSocket

适合让 OneBot 客户端主动连接 dynamic-bot。

示例：

```yaml
mode: REVERSE_WS
host: 127.0.0.1
port: 6701
reverseAccessToken: ""
```

如果监听地址不是本机地址，请务必设置 Token。

## 自动重连

正向连接失败时，插件会自动尝试重连。重连间隔会逐步增加，最长 1 小时，避免连接不上时一直刷日志。

反向连接由 OneBot 客户端自己负责重连。

## 媒体发送

主程序默认使用自动媒体发送方式。OneBot 插件会协助判断当前客户端更适合哪种方式：

1. 本地文件。
2. 临时访问 URL。
3. Base64 图片兜底。

如果 dynamic-bot 和 OneBot 客户端在同一台电脑，本地文件通常是最省资源的方式。

## 消息接收

QQ 群聊和私聊消息会用于：

- 命令。
- 自动链接解析。
- 其他普通插件，例如翻译插件。

OneBot 插件只把平台事件映射为标准入站消息，不在插件侧额外发布命令事件，也不自行决定消息属于命令还是普通文本。主项目会按当前命令前缀、链接解析规则和插件过滤器统一分发。

入站消息会带上平台原始消息 ID，主项目会把它作为 `replyToMessageId` 传给命令回复链路，使回复能对准原消息；`traceId` 只用于日志和追踪。

## 使用建议

- 本机部署优先使用 `ws://127.0.0.1:端口`。
- 多个 Bot 账号可配置多个正向连接。
- 如果发送图片很多，主程序媒体发送方式建议保持“自动”。
- 反向模式监听外网地址时一定要配置 Token。

## 构建与测试

```powershell
.\gradlew.bat test
.\gradlew.bat fatJar --offline
```
