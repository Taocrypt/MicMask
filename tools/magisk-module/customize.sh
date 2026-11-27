# MicMask 系统权限模块安装脚本（Magisk / KernelSU / APatch 管理器里刷入时执行）
#
# 做的事：把「当前已安装的 MicMask」的 APK 复制成 /product/priv-app/MicMask（系统特权应用），
# 再由 etc/permissions 下的 privapp-permissions 授予 MODIFY_AUDIO_ROUTING、CAPTURE_AUDIO_OUTPUT。
# 模块 zip 里不带 APK：始终复制手机上已装的那一份，保证签名一致（签名不一致开机时系统会清掉 App 数据）。
# 目录结构参照 VirtuMic systemless-overlay-module（MIT，见仓库 NOTICE.md）。

PKG=com.taocrypt.micmask

ui_print "- MicMask 系统权限模块"

if [ "$API" -lt 29 ]; then
  abort "! 需要 Android 10 及以上（当前 API $API）"
fi

APKS=$(pm path $PKG 2>/dev/null | sed 's/^package://')
if [ -z "$APKS" ]; then
  abort "! 没找到已安装的 MicMask：请先正常安装 App，再刷本模块"
fi
VER=$(dumpsys package $PKG 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//')
ui_print "- 已安装 MicMask $VER"

# 有独立 /product 分区（或 /system/product）就放 product，否则放 system
if [ -d /product/priv-app ] || [ -d /system/product/priv-app ]; then
  PART=system/product
else
  PART=system
  mkdir -p $MODPATH/system/etc
  mv $MODPATH/system/product/etc/permissions $MODPATH/system/etc/permissions
  mv $MODPATH/system/product/etc/sysconfig $MODPATH/system/etc/sysconfig
  rm -rf $MODPATH/system/product
fi
DIR=$MODPATH/$PART/priv-app/MicMask
rm -rf $DIR; mkdir -p $DIR

for p in $APKS; do
  n=$(basename $p)
  [ "$n" = "base.apk" ] && n=MicMask.apk
  cp -f "$p" "$DIR/$n" || abort "! 复制 $p 失败"
  ui_print "- 复制 $p → $PART/priv-app/MicMask/$n"
done

# 系统应用不会自动解压原生库：解到 lib/<arch>，仅在「卸载更新」回到这份系统副本时用得上
X=$TMPDIR/micmask_lib; rm -rf $X; mkdir -p $X
for apk in $DIR/*.apk; do unzip -o -q "$apk" 'lib/*' -d $X 2>/dev/null; done
for pair in arm64-v8a:arm64 armeabi-v7a:arm x86_64:x86_64 x86:x86; do
  src=${pair%%:*}; dst=${pair##*:}
  if [ -d "$X/lib/$src" ]; then mkdir -p $DIR/lib/$dst; cp -f $X/lib/$src/*.so $DIR/lib/$dst/; fi
done
rm -rf $X

set_perm_recursive $MODPATH/system 0 0 0755 0644

ui_print "- 完成。请重启手机，然后打开 MicMask →「设置 → 运行模式」查看「系统权限模式」"
ui_print "- 若重启后没有出现，再重启一次（个别系统第二次开机才把权限给到已安装的 App）"
ui_print "- 以后 App 正常覆盖更新即可，权限会保留；卸载 App 前请先移除本模块"
