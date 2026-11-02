# 局域网模型网关连不上：改动说明

面向：负责 Android 层的开发
触发场景：Base URL 指向局域网明文地址（例如 `http://192.168.1.201:3300/v1`，One-API / new-api 网关），
点「测试连接」显示「连不上这个地址，检查 Base URL 和网络」，对话显示 `TRANSPORT / Connection error` 或
`TIMEOUT / Request timed out`。

## 结论

真机上定位到的原因只有一条：**应用没有声明 `ACCESS_LOCAL_NETWORK`，系统把该 UID 的局域网访问判为 `ignore`。**
这条按 UID 生效，所以它同时挡住 Dart 和 proot 里的 node 引擎。

`network_security_config.xml` 的明文白名单只覆盖回环，但对本问题**不起作用**——真正的连接由引擎发起，
引擎是 proot 里的原生进程，不走 Android 的 Java 网络栈，那个配置管不到它。见「改动二（条件性）」。

| # | 原因 | 影响层 | 现有状态 |
|---|---|---|---|
| 1 | 应用没有声明 `ACCESS_LOCAL_NETWORK`，MIUI/HyperOS 把局域网访问判为 ignore | 整个 UID（含 node 引擎） | 未声明，**必修** |
| 2 | `network_security_config.xml` 的明文白名单只有 `127.0.0.1` / `localhost` | 仅 Dart/Java 的 socket 层 | 不影响本问题，**条件性** |

### 前提：`targetSdk = 28`

`app/android/app/build.gradle.kts:44` 写死 `targetSdk = 28`（与 Termux 相同，为避开 Android 10+ 对应用私有目录
`exec` 的限制，见 `docs/architecture.md`）。Android 的权限与安全策略大量按 targetSdk 分档，老 targetSdk 的应用
通常走兼容模式，新引入的 appops **未必按新规则授予**。

因此「加一行权限声明」不一定是充分条件。改完必须重新抓一次 `dumpsys appops` 确认 `mode` 从 `ignore` 变成 `allow`；
若仍是 `ignore`，说明这台 ROM 需要用户手动放行，设置页要给出跳转入口。**详见改动一的验证步骤。**


## 现场证据（Mi MIX 2S，HyperOS 2 / Android 16）

1. 用手机 shell 的 `curl` 访问 `http://192.168.1.201:3300/v1/models`，稳定返回 `401`（未带 Key，属正常鉴权拒绝），
   30 次连续请求 24 成功。**手机到网关的链路本身是通的。**
2. 应用点「测试连接」时，`/proc/net/tcp` 里存在 uid 10300 发往 `192.168.1.201:3300` 的 socket，
   状态停在 `SYN_SENT`（`st=02`）。
3. 同一时刻在手机上 `tcpdump -i any host 192.168.1.201`，**抓到 0 个包**；同期用 root 的 curl
   发同样请求，抓包能看到完整的 SYN / SYN-ACK / 401。即：同一设备、同一目标、同一秒，
   普通进程能出网，应用 UID 的包根本没到网卡。
4. `appops get io.github.ccch1mneyyy.dsh_app ACCESS_LOCAL_NETWORK` 返回
   `ACCESS_LOCAL_NETWORK: allow; rejectTime=...`，rejectTime 随每一次测试连接前移。
5. `dumpsys appops` 中该 uid 块显示 `ACCESS_LOCAL_NETWORK: mode=ignore`。
6. 对照：`ip rule` / `ip route get 192.168.1.201 uid 10300` 返回的正是 `wlan0`，路由正常；
   `dumpsys netpolicy` 里该应用 `effective=NONE`，未被后台流量策略限制。

关键点：请求的发起者是应用内的 **node 引擎**（`dsh web --host 127.0.0.1 --port 3080`，跑在 proot 里），
不是 Dart。Dart 只连 `127.0.0.1:3080`。所以这个权限是整 UID 生效的，也是唯一挡住它的东西。

### 第 2 条证据需要再确认一次 uid

第 2 条是整份排查里最强的证据，但它依赖「那条 `SYN_SENT` socket 属于 App uid」。动手改之前请复核：

```bash
# 3300 的十六进制是 0xCE4，找那条 SYN_SENT（st=02）
adb shell cat /proc/net/tcp | grep -i ':0CE4'

# 拿到 inode 后确认它属于哪个进程
adb shell "for p in /proc/[0-9]*; do ls -l \$p/fd 2>/dev/null | grep -q 'socket:\[\<inode\>\]' && echo \$p; done"
adb shell stat -c '%u' /proc/<pid>
```

若结果不是 App uid（例如某个 VPN / 代理的 uid），方向要重查——SYN 没出网卡也可能是被代理接管了。

`st=02` 确实是 `TCP_SYN_SENT`；第 4、5 条里 `appops get` 与 `dumpsys appops` 结论不一致这一点，
以 `dumpsys` 的 `mode=ignore` 为准（`appops get` 显示的 `allow` 是声明状态，不是实际生效模式）。


## 改动一：声明并申请 `ACCESS_LOCAL_NETWORK`（必修）

`AndroidManifest.xml` 的权限区（紧跟 `INTERNET` 之后）加：

```xml
<!-- 局域网模型网关 / 远程直连：Android 16 起为 normal|appop；MIUI/HyperOS 会把它按应用做成
     可拒绝的开关，未声明时局域网流量被判为 ignore。见 docs/local-network-access.md。 -->
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

### 声明之后必须验证（这一步不能省）

因为 `targetSdk = 28`，声明成功不代表 appops 会被授予。装到真机上按顺序确认：

```bash
# 1. 权限是否进入清单
adb shell dumpsys package io.github.ccch1mneyyy.dsh_app | grep -i access_local_network

# 2. appops 的实际模式：期望 allow，若仍是 ignore 则下面的手动放行必须做
adb shell dumpsys appops | grep -i -A2 access_local_network
adb shell appops get io.github.ccch1mneyyy.dsh_app ACCESS_LOCAL_NETWORK

# 3. 端到端：应用内点「测试连接」的同时抓包，应能看到发往网关的 SYN
adb shell tcpdump -i any host <网关IP>
```

**若第 2 步结果是 `ignore`**：不要试图用 `pm grant` 绕过（appops 不等于运行时权限，且用户设备上不可用）。
正确做法是在设置页「模型服务」一栏加一个可点击的检测项，状态读法：

```kotlin
AppOpsManager.checkOpNoThrow("android:access_local_network", uid, packageName)
```

`MODE_ALLOWED` 时显示正常，`MODE_IGNORED` 时显示「局域网访问被系统限制」并提供跳转到
「应用信息 → 网络/联网控制」的入口。

### 其它注意

- 现有代码里没有任何地方声明过这个权限，也没有对应的 appops 检查，属于新增。
- 写 manifest 时留意：仓库已有 `tools:node="remove"` 的用法，别和权限合并规则冲突。
- 这条结论对将来启用 chroot 引擎**同样适用**：`RootLaunch` 让引擎跑 App 自己的 uid（不是 root），
  按 UID 生效的策略不会因为换了启动方式而消失。


## 改动二（条件性）：`network_security_config.xml` 的局域网明文

**先说结论：这一条不修本 bug。** 保留它是因为它描述了一个真实存在的限制，只是不适用于当前症状。

为什么无效：

1. 失败的连接由**引擎**发起（`llm/discoverModels` 是引擎侧的 RPC，真正的 TCP 连接在 proot 内的 node 进程里），
   node 用的是裸 socket，不经过 Android 的 Java 网络栈，`network_security_config` 管不到它。
2. Dart 侧只连 `127.0.0.1:3080`，那条已经在白名单里。

**只有当 App 内存在 Dart/Java 直接向局域网明文地址发请求的路径时，才需要动这个文件。**
目前没有找到这样的路径；如果开发发现有（例如某个功能绕过引擎自己发 HTTP），再按下面的方案处理，并先说明是哪条路径。

当前文件：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Cleartext only for the local DSH server on loopback; everything else stays HTTPS-only. -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

`<domain>` 只接受主机名或 IP 字面量，**不能写 CIDR 网段**，所以无法用一条规则覆盖「任意局域网地址」。
网关地址是用户自己填的、每台机器都不同，因此只有两种现实选择：

### 方案 A：`base-config` 全局允许明文

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 模型网关允许用户填任意局域网地址（http://192.168.x.x:port/v1），无法预先把地址
     写进 domain 列表；因此应用级允许明文，HTTPS 仍由用户自行选择。 -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

代价：应用内任何 HTTP 请求都不再被系统拦截，包括第三方插件。评审时请确认可接受。
保留原 `domain-config` 是表达「回环是明确需要的」这一意图，改动最小。

### 方案 B：只放行已知地址？不可行

`<domain>` 不支持 CIDR / 通配符段，`includeSubdomains` 对 IP 也无意义。不要尝试。


## 改动三（可选）：给「测试连接」更准确的报错

现在 `describeProviderError` 把所有 `could not reach` 都映射成
`stateProviderUnreachable`（「连不上这个地址，检查 Base URL 和网络」），
用户在目标确实可达、只是被系统策略拦掉时会被误导。

建议在 `providers_state.dart` 的错误映射里，对「局域网地址 + 明文 + 系统拦截」这一类
单独给一句提示，例如「局域网访问被系统限制，请在系统设置中允许本应用访问局域网」。
判据可以是 `DshRemoteException` 的底层错误码（`EACCES` / `EPERM` / 连接建立超时且目标为私网地址）。

参考位置（行号基于 `main@c551cb1`，会随上游变动，按符号名搜索更可靠）：

- `app/lib/state/providers_state.dart:366` `describeProviderError`
- `app/lib/l10n/parts/state_zh.arb:281` / `state_en.arb:281` — **改这里**

注意：`app/lib/l10n/app_zh.arb` 和 `app_en.arb` 是 `l10n_merge.py` 的**生成物**，不要手改。
改完 `parts/` 下的输入文件后跑：

```bash
python3 app/tool/l10n_merge.py
```


## 验证步骤

改完必须真机验证，不能只跑单测。第 1 步是**这次修复是否生效的判据**，第 3 步是问题是否真的解决的判据：

1. 安装后确认 appops 模式已从 `ignore` 变成 `allow`（命令见「改动一 → 声明之后必须验证」）。
   **这一步不通过，后面的都不用做**——说明 targetSdk 28 让系统没按新规则授予，需要走手动放行 UI。
2. 手机 shell 先 `curl http://<网关IP>:3300/v1/models` 确认返回 401 之类的 HTTP 响应（链路基线）。
3. 在应用里点「测试连接」，**同时**在手机上 `tcpdump -i any host <网关IP>`，
   确认能看到应用的 SYN 包（这是本次问题的判别方法）。
4. 发一条对话，确认不再出现 `TRANSPORT / Connection error`。
5. 回归：确认回环（本机引擎 `127.0.0.1:3080`）未受影响，`dsh web` 连接正常。

## 附：本次排查中排除掉的可能

- WiFi 稳定性：该机在 02:15–02:20 被系统以 `connected score=5/6` 连续拉黑十余次，
  属于独立问题（信号 -65dBm 左右 + 路由器兼容性），排查时已开关 WiFi 恢复；与本次结论无关。
- 路由 / 网络策略：`ip rule`、`ip route get ... uid 10300`、`dumpsys netpolicy` 均正常。
- DNS：域名场景另行确认，本次是 IP 直连，不涉及 DNS。
- 防火墙：`iptables` 各链（`oem_out` / `fw_OUTPUT` / `st_OUTPUT` / `bw_*`）里没有针对该 UID 的 DROP/REJECT。
