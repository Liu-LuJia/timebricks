# 日程读取授权管理

## 页面与兼容性

入口：设置 → 数据管理 → 日程读取授权。页面沿用 `SettingsPage` 的导航、`SettingsGroup`、`BricksHeader`、`BricksDialog` 和 `TimeBricksTheme`，继承明暗主题、主题色、字号、间距和圆角。页面和系统返回键均返回数据管理，旋转后保留当前详情页。

- 当前状态：读取系统实际权限，区分完整授权、部分授权、授权失效、同签名访问、未授权、未检测到安装和无法确认。
- 应用列表：显示 SelfPact，以及历史手动授权记录和系统可见的同签名权限持有者。显示应用名称和真实包名，不用显示名称判断身份。
- 管理：手动授权经过确认后撤销 v1/v2 读取权限并移除记录，取消不会更改权限。返回前台、重新进入页面或点击刷新时重新检查。
- 说明：解释全部日期只读范围、重启失效、同签名例外，以及撤销不会删除对方此前已读取的数据。
- 扩展：日期/分类范围、自定义有效期、访问记录、更多应用配置均为“暂不可用”的纯文字，没有占位开关或虚假点击行为。

保留 `ScheduleAccessActivity` 的导出组件名、基于 `callingPackage` 的请求身份验证、允许/拒绝结果、遮挡防护，以及现有 `ScheduleAccess.grant/revoke` 行为。`ScheduleProvider`、`ScheduleContract`、数据存储和 v1/v2 路径与字段未改动。SelfPact 可以继续先读取 v1，在需要跨日能力时按原逻辑协商 v2。

只新增对 `com.vera.selfpact` 的定向包可见性声明，不申请读取全部已安装应用的权限。其包名与本机现有 SelfPact 工程一致。

## 权限状态的边界

偏好设置中的包名集合是历史记录，不是有效权限证据。管理页通过当前 UID 检查两个版本的 URI 权限，并单独检查同签名读取权限。同签名访问不受手动撤销影响，因此没有手动记录时不展示撤销按钮；同时具有两类权限时，确认框和结果提示都会说明同签名权限仍有效。

Android 的包可见性可能限制列表范围，页面明确注明列表不是所有潜在访问者的完整清单。旧记录对应应用无法查询时显示“无法确认状态”，不错误宣称已授权或已卸载。页面查询失败时显示错误与刷新入口，不把失败显示成零授权。

参考：[Android URI 权限检查](https://developer.android.com/reference/android/content/Context#checkUriPermission(android.net.Uri,%20int,%20int,%20int))、[自动包可见性](https://developer.android.com/training/package-visibility/automatic)。

## 自动检查

Windows 构建与单元测试：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

运行设备测试必须使用隔离包名，避免现有接口测试接触真实日程数据：

```powershell
.\gradlew.bat -PscheduleApiCheck=true :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.vera.timebricks.ScheduleAccessContentTest,com.vera.timebricks.ScheduleAccessSystemTest,com.vera.timebricks.ScheduleAccessNavigationTest
```

新增检查涵盖状态判断、取消/确认撤销、不可操作的扩展说明、错误状态、失效记录、同签名控制、过期确认框、真实 Android v1/v2 授予与撤销、页面导航和 Activity 重建。导航测试在隔离测试应用的外部文件目录保存 `schedule-access.png` 供视觉检查。

## 真机验收步骤

1. 在 GitHub Desktop 中获取并切换到 `feat/schedule-access-management`，用 Android Studio 打开该分支工程，按原来的方式运行 `app`。使用与已安装时间积木一致的签名，不通过卸载解决签名冲突，以免丢失本地数据。
2. 进入设置 → 数据管理 → 日程读取授权，确认是完整详情页。检查状态、SelfPact 名称/包名、授权说明和扩展区；点击页面返回和系统返回，均应回到数据管理。旋转屏幕后仍停留在授权页。
3. 在 SelfPact 的“核对来源”中发起授权。先拒绝，确认没有新增手动授权；再允许，返回管理页应自动更新为“已授权 · 只读”或真实的“同签名访问”。SelfPact 读取普通日程和跨日日程的结果应与更新前一致。
4. 点击“撤销手动授权”，先取消并确认仍能读取；再次点击并确认撤销，管理页应立即刷新。普通 URI 授权的 SelfPact 再次读取应提示重新授权；重新授权后可恢复读取。
5. 如果显示“同签名访问”，撤销手动授权后仍能读取属于现有机制，页面应清楚解释。没有手动记录时不应出现撤销按钮。
6. 对普通 URI 授权重启设备：旧记录应显示“需重新授权”，而非仍显示“已授权”。重新在 SelfPact 授权后恢复。卸载接入应用后的历史记录不应被显示为有效授权。
7. 切换日间/夜间/跟随系统、主题色和较大字号，再检查管理页的文字、卡片、确认框及滚动。所有“暂不可用”区域应没有开关、箭头或点击效果。

独立测试包用于自动检查，SelfPact 真机联调须运行原包名 `com.vera.timebricks`，因为 SelfPact 的现有接口地址指向原包名。
