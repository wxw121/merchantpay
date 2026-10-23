# 渠道扫描与提交阶段工具独立复核

只读审查范围：模拟器schema.sql新增两个索引、ChannelHealthTest真实SQL执行计划与商户语义、LocalDiagnosis的阶段快照/通用事件差值、DiagnosisMeasurementsTest阶段回归。目录无Git，按指定文件及红绿日志复核；未改工作区、数据库或运行配置。

无阻塞发现。原查询和事务语义不变，非唯一CREATE INDEX IF NOT EXISTS符合现有初始化方式。原扫描20004与修正后5项测试/fmt通过证据一致；新测试捕获实际方法SQL，并验证历史已确认行、商户范围、状态过滤、顺序和确认/关闭变化。全局阶段汇总的global_instrumentation门槛正确，不需要为了全局汇总启用current/history消费者。

复核建议P3：显式覆盖禁用阶段为null、未计时阶段次数有效但时间为null。已补回归并再次通过，日志 `.work/channel-index-diagnostic-final.log`。unsigned BIGINT精确差值及重置失效继续覆盖。

覆盖限制：新性能测试使用ANALYZE后的私有内存库，本身不证明历史文件升级成本；停机备份后的真实部署与健康探测另提供证据。索引有构建、存储和写维护开销，实际未处理队列增长仍需工作量。未审查未来查询改写、缓存调参、金融业务变更或本轮一次性测量辅助程序；不为支付容量或阶段因果占比背书。
