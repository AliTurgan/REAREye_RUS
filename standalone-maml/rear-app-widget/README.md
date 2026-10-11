# 独立背屏应用小组件主题

本目录是独立 MAML 交付，不修改应用代码。需要支持 `rearWidgetPicker="true"`、应用分页 Binder、动态 widget
key/provider、逐卡片 config 持久化的新后端；旧后端不能直接使用。

## 打包与导入

在仓库根目录运行：

```bash
python standalone-maml/rear-app-widget/validate_theme.py
python -c "from pathlib import Path; from zipfile import ZipFile,ZIP_DEFLATED; p=Path('standalone-maml/rear-app-widget'); z=ZipFile(p.with_suffix('.zip'),'w',ZIP_DEFLATED); z.write(p/'manifest.xml','manifest.xml'); z.close()"
```

导入独立 `rear-app-widget.zip` 为 MAML 主题资源，再添加背屏卡片。压缩包根目录必须直接包含
`manifest.xml`，不包含上级文件夹。若宿主入口要求专用扩展名，请按该入口约定导入 ZIP 容器；不要打入
APK。此主题无外部图片资源，也不提供初始化选择的 `config.xml`；宿主负责创建/加载该卡片的运行期 config。

## 使用

- 一行一个应用，显示上一项、当前项、下一项；当前行高亮，上下行使用真实小组件预览但调暗。
- 上下滑动每次切一个应用；左右滑动切当前应用的小组件。两轴都跟手平移、松手吸附，并显示相邻预览；列表到边界回弹，不做多页惯性滚动。
- 双击中心小组件预览确认，第二次有效抬手才提交；拖动、取消、越界及加载中不确认。需要时完成宿主弹出的绑定授权和提供者配置。
- 确认后没有更换入口。删除卡片并重新添加可重新选择。
- 多个卡片由宿主分配永久 `rw_instance` token，主题不写固定 key。各实例独立保存 `rw_provider`、
  `rw_package`、`rw_selected`；最后保存 selected 作为提交标记。

## 宿主 ABI 与验收边界

- `rw_ready` 只能在该卡片 config 恢复完成后置 1；恢复前不展示选择器或真实小组件。需要启用原生
  `saveVar`/persist capability，并在进程重启后恢复三个选择变量。不要复用全局 config/SP；删除卡片必须清理其
  config 和 widget 绑定。
- `RearDisplayBinder.safeLeft/safeTop/safeRight/safeBottom` 约定为**物理像素 inset 距离**
  ，不是矩形坐标。中心内容、主要文字及真实 widget 使用扣除四侧 inset 与 6
  物理像素内边距后的安全矩形；选择器背景覆盖全屏，横向相邻预览允许延伸到摄像头区域，只在物理屏幕边界裁剪。显示信息加载/失败时不展示
  widget。宿主必须保证 inset 覆盖摄像头区域。
- 标准原生 `BinderCommand name="..." command="refresh"` 必须触发自定义 Binder
  查询。五组预览首次查询关闭，获得有效包名/索引后才请求；拖动过程中不发查询。宿主必须取消旧查询结果，按新请求完成后原子发布
  count/loading/error、packageNames、offset、providers 和 Bitmap 数组；刷新时保留旧快照，预览可复用有界缓存。
- 已从当前 JADX 原生代码核实 `BitmapVar`、`VariableCommand persist`、`IfCommand`、`FunctionCommand`
  、Button down/move/up/cancel 与 `VariableAnimation`。预览通过 `BitmapVar` 数组源获取，使用动态空源避免静态数组源初始化问题。
- Python 验证覆盖 XML、45 个应用的分页/边界、空数组与旧包/旧页结果守卫、保存顺序及手势状态机；不等同原生
  MAML 执行。尚未做真机渲染、重启持久化、多实例隔离、授权配置、摄像头避让及删除生命周期验收。
