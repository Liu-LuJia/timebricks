# TimeBricks · 时间积木

一款本地运行的 Android 时间规划应用。支持按天安排日程、分类与重叠事件、重复计划、模板、提醒、桌面组件、统计、导出和本地备份。还提供可授权的只读本地日程接口，供其他应用读取计划。

## 构建

用 Android Studio 打开仓库根目录，安装工程要求的 Android SDK 后运行 `app`；也可以执行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`）。项目使用 Kotlin、Jetpack Compose 和 Gradle Wrapper，最低 Android 版本为 8.0（API 26）。

## 数据与权限

日程保存在设备本地。备份和导出由用户在应用内操作；授权其他应用读取日程前，请确认对方应用可信。公开仓库不包含个人日程、备份、签名密钥或构建产物。

## 开发说明

本项目由个人根据自己的使用需求设计、测试并迭代，开发过程中使用了 AI 辅助编程。欢迎提交问题和改进建议。

## 许可

代码以 MIT License 发布，详见 [LICENSE](LICENSE)。
