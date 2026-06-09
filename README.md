# FRP 穿透 — Android FRP 客户端

[![GitHub](https://img.shields.io/badge/GitHub-w2018/FrpAndroid-blue?logo=github)](https://github.com/w2018/FrpAndroid)

**FRP 穿透** 是一款 Android 平台上的 [frp](https://github.com/fatedier/frp) 内网穿透客户端，集成 frpc v0.68.0 原生二进制，提供图形化配置管理、服务监控、内置 HTTP 文件服务器等功能。

> 本项目基于 [fatedier/frp](https://github.com/fatedier/frp) 构建，仅供测试学习使用，禁止用于非法用途。

---

## 功能概览

### 核心穿透功能
- **TCP / UDP 端口映射** — 将本地 TCP/UDP 服务暴露到公网
- **HTTP / HTTPS 域名路由** — 通过域名访问本地 Web 服务
- **TCPMUX 多路复用** — 多个服务共用一个 frps 端口
- **STCP / SUDP 安全隧道** — 密钥认证，frps 不暴露公网端口
- **XTCP P2P 穿透** — 点对点直连，节省服务器带宽

### 图形管理界面
- **三页 Tab 布局** — 配置 / 日志 / 关于
- **代理列表管理** — 添加、编辑（双击）、删除（自动存档）
- **协议智能表单** — 选择协议类型后自动显示对应配置字段
- **预设服务器** — 一键切换多个公共服务
- **服务器历史** — 自动保存历史配置，长按地址栏快速切换
- **已删除代理恢复** — 删除的代理自动存档，可随时恢复

### 服务监控与稳定性
- **后台 Service 运行** — 前台通知保活，清理前台不影响后台
- **自动重启** — frpc 异常退出后自动重试（间隔15秒）
- **运行状态指示** — 通知栏实时更新
- **后台运行时长** — 通知栏展开显示已运行时间
- **脉冲环动画** — 运行状态以科技感动画展示
- **全局异常捕获** — Application 级兜底，防止闪退

### 内置 HTTP 文件服务器
- 独立的 Service 运行，支持指定监听地址和端口
- 支持 0.0.0.0 / 127.0.0.1 / WiFi IP 三种绑定模式
- 目录列表浏览与文件下载
- 自动生成样例页面

### 流量与统计
- **实时流量统计** — 累计收发字节数，单位自动 B/KB/MB/GB
- **今日统计** — 每日自动重置的前台运行时长 + 流量用量
- **流量数据持久化** — 服务重启不丢失

### 其他特性
- 开机自启动
- 电池优化白名单引导
- 全部文件访问权限引导
- 完整 frpc.toml 配置导出功能

---

## 环境与构建

### 开发环境

| 项目 | 版本 |
|------|------|
| 开发工具 | Reasonix AI Coding Agent |
| 语言 | Java 17 |
| 构建系统 | Gradle 8.11.1 + Groovy DSL |
| Android SDK | compileSdk 34, minSdk 26, targetSdk 34 |
| 构建类型 | Release (assembleRelease) |
| 混淆工具 | R8 (minifyEnabled = true) |

### 构建命令

```powershell
cd FrpAndroid
.\gradlew assembleRelease
```

### 项目结构

```
FrpAndroid/
├── app/
│   ├── build.gradle                      # 应用构建脚本
│   ├── proguard-rules.pro                # R8 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml           # 权限与组件声明
│       ├── jniLibs/arm64-v8a/            # frpc ARM64 原生二进制
│       │   └── libfrp_native.so
│       ├── res/                          # 布局、资源、图标
│       └── java/top/zw/frpc/
│           ├── MainActivity.java         # 主界面 + TabLayout + ViewPager
│           ├── ConfigFragment.java       # 配置页：服务器/代理/启动控制
│           ├── LogFragment.java          # 日志页：实时输出显示
│           ├── AboutFragment.java        # 关于页：版本/流量/HTTP服务器
│           ├── FrpcService.java          # FRP 核心 Service（前台+自动重启）
│           ├── HttpFileService.java      # 内置 HTTP 文件服务器 Service
│           ├── ConfigManager.java        # SharedPreferences 存储管理
│           ├── ProxyItem.java            # 代理数据模型（全协议字段）
│           ├── ProxyAdapter.java         # 代理列表适配器（双击编辑）
│           ├── PulseRingView.java        # 自定义脉冲环动画 View
│           ├── BootReceiver.java         # 开机自启广播接收器
│           └── FrpApplication.java       # Application 全局异常捕获
├── build.gradle                          # 根构建脚本
├── settings.gradle
├── gradlew.bat + gradle/wrapper/         # Gradle Wrapper
└── README.md
```

---

## 使用指南

### 快速开始

1. 打开 APP → **配置** 页
2. 点击标题栏 **预设** 按钮选择公共服务器
3. 填写本地服务的代理规则
4. 点击 **启动**
5. 切换到 **日志** 页查看运行状态

### 支持的协议一览

| 类型 | 说明 | 必填字段 |
|------|------|---------|
| tcp | TCP 端口映射 | name, localPort, remotePort |
| udp | UDP 端口映射 | name, localPort, remotePort |
| http | HTTP 域名路由 | name, localPort, customDomains 或 subdomain |
| https | HTTPS 域名路由 | name, localPort, customDomains 或 subdomain |
| tcpmux | 端口多路复用 | name, localPort, customDomains |
| stcp | 安全 TCP | name, localPort, secretKey |
| sudp | 安全 UDP | name, localPort, secretKey |
| xtcp | P2P 穿透 | name, localPort, secretKey |

---

## 开源许可

本项目基于 [Apache License 2.0](LICENSE) 开源。

内嵌的 frpc 二进制来源于 [fatedier/frp](https://github.com/fatedier/frp)（Apache License 2.0）。

```
FRP 穿透 — Android FRP 客户端
Copyright (c) 2026 w2018

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 参考与致谢

- [fatedier/frp](https://github.com/fatedier/frp) — 快速反向代理，本项目的核心基础
- [Material Components for Android](https://github.com/material-components/material-components-android) — Material Design 组件库
- [AndroidX](https://developer.android.com/jetpack/androidx) — Android 官方支持库

---

**作者：** w2018  
**开发工具：** Reasonix AI Coding Agent  
**开源地址：** [https://github.com/w2018/FrpAndroid](https://github.com/w2018/FrpAndroid)  
**仅供测试学习使用，禁止用于非法用途。**
