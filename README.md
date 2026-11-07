# 图片转 GIF · Android 版

把几张图排好顺序，生成一张 GIF 动图。**纯本地处理、不联网、不上传** —— 一个单文件网页，套上一层极薄的 WebView 原生壳，打成可以直接装到手机上的 APK。

> 网页原稿：`web/index.html`（单文件、零依赖、字节未改动）
> 安装包：Releases 里的 `gif-maker-<版本>.apk`

## 为什么还要套个壳

网页版在手机浏览器里能用，但有两件事体验不好，壳就补这两件事：

| 网页里做不到 / 别扭的 | 原生壳怎么补 |
| --- | --- |
| 选图要靠浏览器的文件选择器，多选不好用 | `WebChromeClient#onShowFileChooser` + `EXTRA_ALLOW_MULTIPLE`，一次选多张、可反复追加 |
| 点「下载 GIF」后，`blob:` 链接在 WebView 里点了没反应 | 注入脚本接管这次点击，把 GIF 字节分块交给原生桥，直接写进系统相册 `Pictures/GifMaker/` |
| 返回键直接退出 | 有网页历史时先回退 |
| 按钮点下去没有反馈 | 点按钮给一次轻触觉反馈 |

其余全部留在网页里：预览、排序、调色板量化、LZW 编码、GIF 封装。壳不实现任何图像算法。

## 下载与安装

1. 到 Releases 下载 `gif-maker-1.0.apk`；
2. 手机上点开安装（需要允许「安装未知来源应用」）；
3. 打开「图片转 GIF」，选图 → 调参数 → 生成 → 点「下载 GIF」，动图落在相册 `Pictures/GifMaker/`。

支持 Android 7.0（API 24）及以上。

## 功能

- 多选图片（JPG / PNG / WebP / BMP / GIF 首帧），可分批添加
- 拖拽 / ↑↓ 排序，第 1 张决定画面比例，其余等比居中不拉伸
- 输出宽度、每帧停留毫秒、循环次数、颜色数（256 / 128 / 64）、背景色、保留透明、每帧独立调色
- 生成后预览 + 体积信息，一键存进相册

## 隐私

- 清单里**没有 `INTERNET` 权限**，从系统层面杜绝联网
- 页面里没有任何外部请求（无 CDN、无字体、无统计），断网可用
- 图片只在设备内存和画布里过一遍，不落盘（除了你主动保存的那张 GIF）

## 构建

无需 Gradle / Android Studio，四步工具链直接出包：

```bash
./build.sh                 # → dist/gif-maker-1.0.apk
VERSION_NAME=1.1 VERSION_CODE=2 ./build.sh
```

依赖（脚本里有默认路径，可用环境变量覆盖）：

| 变量 | 用途 |
| --- | --- |
| `JAVA_HOME` | JDK 17（`javac` / `apksigner` 需要） |
| `ANDROID_SDK` / `BUILD_TOOLS` | SDK 与 build-tools 目录（`d8`、`apksigner`） |
| `AAPT2` | **aarch64 版** aapt2（build-tools 里的默认是 x86_64 主机版，手机上跑不了） |
| `ANDROID_JAR` | 平台 android.jar |
| `KEYSTORE` / `KS_PASS` / `KEY_ALIAS` | 签名密钥，默认用 `~/.android/debug.keystore`（debug 签名） |

构建过程：aapt2 编译资源 → aapt2 链接出壳 APK → javac 编译 → d8 转 dex → 合包 → 校验 `resources.arsc` 未压缩且 4 字节对齐 → apksigner 签名。

图标由 `scripts/make-icons.py` 用标准库现画（本机没有 Pillow），胶片格 + 播放三角，配色取自网页主色。

## 目录结构

```
build.sh                      一键打包
web/index.html                网页原稿（唯一真源，逐字节未改）
android/AndroidManifest.xml   清单：无任何权限声明
android/assets/native-shim.js 注入脚本：接管「下载」点击、补触觉反馈
android/src/.../MainActivity.java  WebView 壳 + 原生桥
android/res/                  图标与字符串
scripts/make-icons.py         纯标准库图标生成
```

## 已知限制

- 签名为 debug 密钥（自用/分发够用，不是 Play 商店签名）
- Android 9 及以下没有分区存储，GIF 存到应用私有目录 `Android/data/com.dsharness.gifmaker/files/Pictures/GifMaker/`，相册里看不到（需要文件管理器打开）
- 帧数很多（>30）或分辨率很高时，编码会慢一些，属于网页算法本身的代价

## 版本记录

- **1.0** 首个版本：网页打包成 APK，原生多选图片、GIF 存进相册、返回键回退、触觉反馈

## 许可

MIT
