# pthkit：手机导入 .pth 的模板包

生成 app/src/main/assets/pthkit.zip（不含任何模型权重）：

    python gen_all.py kit          # 随机权重导出 12 种结构的 fp32 / QDQ 模板 + 配方（需 RVC 源码与 torch）
    python pack.py kit             # 写 .plan 文本配方与 cal.bin（8 段校准特征，取自 calib.npz）
    cd kit && zip -9 ../pthkit.zip *.onnx *.plan cal.bin

手机端实现见 core/.../PthImport.kt（.pth 解析、配方、校准、量化、protobuf 追加拼接）与 app/.../PthConvert.kt。
PC 验证：`cli` 模块 `micmask.cli.PthTestKt <pth> <kit> <v2_40k_L35> <out.onnx>`；phone_sim.py 为同一算法的 Python 参考。
