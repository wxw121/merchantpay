# I/O诊断工具独立复核

范围：`scripts/load/LocalDiagnosis.java`新增I/O快照/差值与唯一文件名、`DiagnosisMeasurementsTest.java`、本地回归入口及CI新增调用。只读独立审查，无Git目录，按指定文件评估；不审查既有业务、历史SQL摘要算法或本轮缓存实验的因果结论。

发现并修复一项P2：MySQL累计计时为unsigned BIGINT，原Long.parseLong在超过Long.MAX_VALUE时抛异常。增加该边界后实际红灯NumberFormatException（`.work/io-unsigned-red.log`），改为BigInteger精确差值；同时保留大于2^53的精度回归。复核者独立运行更新后的DiagnosisMeasurementsTest通过，修正后无剩余可操作发现。

检查覆盖配置/监控快照变化、Uptime或可见计数回退、文件事件集合变化、缺失与未计时非零、整数精度、输出不含原始SQL/文件路径/凭据、两个回归入口。文件全局汇总依赖global_instrumentation；不能因thread_instrumentation关闭而错误判定全局文件摘要无效，依据[MySQL 8.4消费者配置](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-consumer-configurations.html)。

边界：端点快照不能证明区间内从未短暂改变配置，也不能证明没有监控丢样。没有审查/认证金融正确性、R1性能、缓存实验或原有逐秒采样与SQL摘要逻辑。没有修改业务代码、数据库或运行配置。
