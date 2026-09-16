# dynamic-bot-onebot

OneBot 网关插件，用于让 dynamic-bot 通过 OneBot 客户端向 QQ 群或好友发送消息，也可以接收 QQ 消息用于命令、链接解析和其他插件（例如翻译插件）。

常见客户端包括 NapCat 和 LLOneBot。

主项目：[dynamic-bot](https://github.com/Colter23/dynamic-bot)

## 功能

- 支持正向 WebSocket（插件主动连接客户端）和反向 WebSocket（客户端连接插件）。
- 支持多个连接和多个 Bot 账号。
- 发送群消息和好友消息，也支持合并转发消息。
- 发送图片、视频和语音，自动选择更合适的图片发送方式。
- 接收入站消息并交给主项目统一处理，命令、自动链接解析和普通插件都由主项目分发。
- 撤回插件发送的消息。
- 正向连接断开后自动重连，重连间隔逐步拉长。

## 安装

构建插件：

```powershell
.\gradlew.bat fatJar --offline
```

把生成的 `*-all.jar` 放入主程序的 `plugins/` 目录，然后启动或重启 dynamic-bot，在后台启用插件。

## 连接方式

### 正向 WebSocket

适合 OneBot 客户端已经开启 WebSocket 服务的场景，插件会主动连接客户端：

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

适合让 OneBot 客户端主动连接 dynamic-bot：

```yaml
mode: REVERSE_WS
host: 127.0.0.1
port: 6701
reverseAccessToken: ""
```

如果监听地址不是本机地址，请务必设置 Token。

## 使用建议

- 本机部署优先使用 `ws://127.0.0.1:端口`。
- 多个 Bot 账号可配置多个正向连接。
- 发送图片较多时，主程序的媒体发送方式建议保持“自动”，插件会协助判断当前客户端更适合哪种方式。
- 反向模式监听外网地址时一定要配置 Token。

## 构建与测试

```powershell
.\gradlew.bat test
.\gradlew.bat fatJar --offline
```
