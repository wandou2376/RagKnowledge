"""生成检索评测集。

核心做法：每个问题除了问句本身，还带一组「关键短语」。
关键短语用于**从真实分块内容里反查期望答案**，而不是靠人凭印象手写。
好处有两个：
  1. 期望答案一定和语料一致，不会出现「断言的内容其实不在文档里」
  2. 某个关键短语在语料里找不到时会直接报错，能在生成阶段就发现设计错误

PARAPHRASE 类的问题有意设计成问句里不含关键短语（模糊问法），
关键短语只用于定位期望分块——这正是用来区分向量召回和关键词召回的场景。

用法（先启动数据库，且语料已灌入某个知识库）：
    python tools/corpus/build_eval_set.py
"""

import json
import pathlib
import subprocess
import sys

OUTPUT = pathlib.Path(__file__).resolve().parents[2] / "src" / "test" / "resources" / "retrieval-eval-set.json"

# 语料里的文档都以 nebula- 开头，用它圈定范围，这样不依赖具体是哪个知识库
DOCUMENT_FILTER = "nebula-%"


# type 的含义：
#   FACT       单点事实，答案在一个分块里
#   TABLE      表格查值
#   MULTI_HOP  需要跨文档或跨章节综合，期望集合会包含多个文档
#   TERM       错误码 / 配置键 / 接口路径 / 版本号，关键词召回的主场
#   PARAPHRASE 问句里不含原文关键词，向量召回的主场
QUESTIONS = [
    # ---------------------------------------------------------------- FACT
    dict(type="FACT", question="平台当前的稳定版本和长期支持版本分别是多少？",
         keys=["长期支持版本是 3.8"], note="产品概述里一句话给出"),
    dict(type="FACT", question="集群模式最少需要几台机器，为什么？",
         keys=["至少三台机器"], note="部署与配置"),
    dict(type="FACT", question="单个任务的默认内存上限是多少？超了会怎样？",
         keys=["单个任务的内存上限默认为 4GB"], note="架构章节"),
    dict(type="FACT", question="平台支持哪几种数据接入方式？",
         keys=["平台支持四种接入方式"], note="数据接入"),
    dict(type="FACT", question="告警可以通过哪些渠道通知？",
         keys=["告警通知支持邮件、企业微信和内网 Webhook"], note="监控告警"),
    dict(type="FACT", question="平台是否能对接企业已有的统一登录？",
         keys=["平台不提供单点登录对接功能"], note="安全与权限"),
    dict(type="FACT", question="任务失败后默认重试几次，间隔是怎么算的？",
         keys=["每次间隔按指数退避计算"], note="任务调度"),
    dict(type="FACT", question="跨租户引用其他租户的任务，平台允许吗？应该怎么做？",
         keys=["跨租户的依赖是不允许的"], note="任务调度"),
    dict(type="FACT", question="平台的业务数据默认保留多久，由哪个配置项控制？",
         keys=["nebula.storage.retention.days"], note="部署与配置表"),
    dict(type="FACT", question="调度器会不会因为数据量增长自动扩容执行器？",
         keys=["平台不会因为数据量增长自动扩容执行器"], note="故障排查"),

    # ---------------------------------------------------------------- TABLE
    dict(type="TABLE", question="网关转发请求的默认超时时间是多少毫秒？",
         keys=["nebula.gateway.timeout.ms"], note="配置表"),
    dict(type="TABLE", question="调度器扫描就绪任务的默认时间间隔是多少？",
         keys=["nebula.scheduler.interval.ms"], note="配置表"),
    dict(type="TABLE", question="单执行器默认并发几个任务？",
         keys=["nebula.worker.concurrency"], note="配置表"),
    dict(type="TABLE", question="业务数据分区目录的默认日期格式是什么？",
         keys=["nebula.storage.partition.format"], note="配置表"),
    dict(type="TABLE", question="控制台会话的默认超时时间是多少分钟？",
         keys=["nebula.console.session.timeout.minutes"], note="配置表"),
    dict(type="TABLE", question="单租户不可用时，响应时限要求是多少？",
         keys=["单租户不可用"], note="值班手册的升级路径表"),
    dict(type="TABLE", question="全平台不可用要找谁，响应时限是多少？",
         keys=["全平台不可用"], note="值班手册的升级路径表"),
    dict(type="TABLE", question="平台承诺的任务调度成功率是多少？",
         keys=["任务调度成功率"], note="值班手册的 SLA 表"),
    dict(type="TABLE", question="哪个角色的原始问题只能查看、不能修改？",
         keys=["只读用户"], note="术语表；角色取值也在权限章节"),
    dict(type="TABLE", question="手动触发任务的开放 API 路径是什么？",
         keys=["/openapi/v1/jobs/{jobId}/trigger"], note="开放 API 表"),

    # ---------------------------------------------------------------- MULTI_HOP
    dict(type="MULTI_HOP", question="连接池耗尽这个错误码是什么，一级值班能不能自己处理？",
         keys=["E-4012", "连接池耗尽（E-4012）属于一级值班的处理范围"],
         note="错误码表给编号，值班手册说处置权限"),
    dict(type="MULTI_HOP", question="数据校验和不一致时报什么错，值班手册对它的处置要求是什么？",
         keys=["E-6014", "不允许一级值班自行处置"],
         note="错误码表 + 值班手册"),
    dict(type="MULTI_HOP", question="调度器长时间无主超过多久算故障，该升级给谁？",
         keys=["调度器长时间无 10 分钟内响应", "无主超过 5 分钟即视为故障"],
         note="值班手册内部跨表；SLA 与升级路径"),
    dict(type="MULTI_HOP", question="内存超限的任务该怎么处理，为什么单纯扩容执行器没用？",
         keys=["E-4015", "扩容执行器没用"],
         note="错误码表 + FAQ"),
    dict(type="MULTI_HOP", question="任务被跳过算不算失败，在哪几个地方有说明？",
         keys=["E-5004", "频繁出现说明任务执行时间"], note="错误码表 + FAQ"),
    dict(type="MULTI_HOP", question="告警多久只通知一次，如果觉得不够该怎么办？",
         keys=["同一告警在 15 分钟内只会通知一次", "在外部的告警平台做二次收敛"],
         note="监控告警 + FAQ"),
    dict(type="MULTI_HOP", question="从 3.8 升到 4.2 可以一步到位吗，中间要注意什么？",
         keys=["不建议从 3.8 直接升到 4.2", "中间要先升到 4.0"],
         note="迁移与升级同一节"),
    dict(type="MULTI_HOP", question="元数据查询变慢该归档什么，保留天数建议不低于多少？",
         keys=["E-6009", "调度记录的保留建议不低于 90 天"],
         note="错误码表 + 性能与容量"),
    dict(type="MULTI_HOP", question="索引页面提示无权访问租户，可能是什么原因，该找谁？",
         keys=["E-7001", "由租户管理员添加成员"],
         note="错误码表 + 错误码处理建议"),
    dict(type="MULTI_HOP", question="位点超前这个错误，在哪些文档里都有提到？",
         keys=["E-4007"], note="错误码表与 FAQ 都可能涉及"),

    # ---------------------------------------------------------------- TERM
    dict(type="TERM", question="E-7010 是什么错误，怎么解决？",
         keys=["E-7010"], note="错误码直查"),
    dict(type="TERM", question="E-4012 的触发条件是什么？",
         keys=["E-4012"], note="错误码直查"),
    dict(type="TERM", question="E-6002 是什么错误？",
         keys=["E-6002"], note="错误码直查"),
    dict(type="TERM", question="nebula.collector.batch.size 这个配置项是做什么的？",
         keys=["nebula.collector.batch.size"], note="配置键直查"),
    dict(type="TERM", question="nebula.quality.sample.ratio 默认值是多少？",
         keys=["nebula.quality.sample.ratio"], note="配置键直查"),
    # 这一行的接口路径在表格里被换行拆开了（提取出来是 offsets/{datasourceId}/re ... s et），
    # 所以关键短语改用同一行里未被拆开的说明列
    dict(type="TERM", question="/openapi/v1/offsets/{datasourceId}/reset 这个接口有什么特别要求？",
         keys=["重置位点，需要双人复核"], note="接口路径直查；路径在表格里被换行拆开，用同行说明列定位"),
    dict(type="TERM", question="令牌的有效期上限是多久？",
         keys=["有效期上限 90 天"], note="术语表与权限章节"),
    dict(type="TERM", question="E-5014 意味着什么，还要继续重试吗？",
         keys=["E-5014"], note="错误码直查"),
    dict(type="TERM", question="4.2.0 这个版本有哪些主要变化？",
         keys=["4.2.0（2026-06-25）"], note="变更记录直查"),
    dict(type="TERM", question="3.8.6 是最后一次功能更新吗？",
         keys=["3.8.6（2025-09-18）"], note="变更记录直查"),
    dict(type="TERM", question="E-4006 和 E-4007 都是位点相关的问题，区别是什么？",
         keys=["E-4006"], note="错误码相邻条目，考察精确召回"),
    dict(type="TERM", question="数据源连接密码用什么算法加密？",
         keys=["使用国密 SM4 算法加密"], note="安全与权限"),

    # ---------------------------------------------------------------- PARAPHRASE
    # 下面这些问句有意不含原文关键词，关键短语只用于定位期望分块
    dict(type="PARAPHRASE", question="有个任务跑得比平时慢很多，但错误码是 0，我该先怀疑什么？",
         keys=["突然变慢但错误码是 0"], note="故障排查的经验条目"),
    dict(type="PARAPHRASE", question="我的任务明明成功了，下游却说查不到数据，可能是什么情况？",
         keys=["先确认下游读的是不是同一个分区"], note="FAQ"),
    dict(type="PARAPHRASE", question="想给某张表配更严格的一致性检查，要动哪个参数？代价是什么？",
         keys=["对数据正确性要求高的表可以调到 100%"], note="数据质量与校验"),
    dict(type="PARAPHRASE", question="平台能不能算某个字段的空值率这类质量指标？",
         keys=["字段值的空值率、去重率这类质量指标平台不计算"], note="数据质量与校验"),
    dict(type="PARAPHRASE", question="升级一次大概要停机多久？",
         keys=["元数据迁移大约需要 40 分钟"], note="迁移与升级"),
    dict(type="PARAPHRASE", question="升级之后发现不行，能退回去吗？",
         keys=["回滚只有一次机会"], note="迁移与升级"),
    dict(type="PARAPHRASE", question="表里的记录会重复吗？",
         keys=["位点每 30 秒提交一次", "最多可能重复消费 30 秒的数据"], note="数据接入的消费位点说明"),
    dict(type="PARAPHRASE", question="能自己在平台上写一段脚本来处理数据吗？",
         keys=["平台不提供自定义脚本执行能力"], note="FAQ"),
    dict(type="PARAPHRASE", question="一个租户能配很多数据源吗，会有什么隐患？",
         keys=["单租户超过 200 个数据源后"], note="FAQ"),
    dict(type="PARAPHRASE", question="为什么我的任务明明是手动点了触发却没跑起来？",
         keys=["同一任务同一时刻只允许一次手动触发"], note="错误码 E-5009 与 FAQ"),
]


def load_chunk_index():
    """从数据库读出全部语料分块的关键短语命中情况，返回 {(question_id, key): [(doc, idx)]}。"""
    values = ",\n".join(
        f"    ({index}, '{key}')"
        for index, question in enumerate(QUESTIONS, start=1)
        for key in question["keys"]
    )
    sql = f"""
with probe(qid, key) as (values
{values}
)
select probe.qid, probe.key, d.original_file_name, c.chunk_index
from probe
join rag.document_chunk c
  /* 忽略空白再比对：PDF 提取出来的文本保留了排版时的换行，
     关键短语跨行就匹配不上，去掉空白后两边口径一致 */
  on replace(replace(c.content, E'\\n', ''), ' ', '')
     ilike '%' || replace(probe.key, ' ', '') || '%'
join rag.document d on d.id = c.document_id
where d.original_file_name like '{DOCUMENT_FILTER}'
order by probe.qid, probe.key, d.original_file_name, c.chunk_index;
"""
    result = subprocess.run(
        ["docker", "exec", "-i", "rag-pgvector", "psql", "-U", "rag_app", "-d", "ragdb",
         "-t", "-A", "-F", "|", "-c", sql],
        capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode != 0:
        print("查询失败：", result.stderr, file=sys.stderr)
        sys.exit(1)

    hits = {}
    for line in result.stdout.splitlines():
        parts = line.strip().split("|")
        if len(parts) != 4:
            continue
        qid, key, doc, chunk_index = int(parts[0]), parts[1], parts[2], int(parts[3])
        hits.setdefault((qid, key), []).append((doc, chunk_index))
    return hits


def main():
    hits = load_chunk_index()
    eval_set, problems = [], []

    for index, question in enumerate(QUESTIONS, start=1):
        expected = set()
        for key in question["keys"]:
            matched = hits.get((index, key), [])
            if not matched:
                problems.append(f"[{question['type']}] 关键短语在语料里找不到: {key!r}"
                                f" <- {question['question']}")
            expected.update(matched)

        if not expected:
            continue

        eval_set.append({
            "type": question["type"],
            "question": question["question"],
            "expected": [{"document": doc, "chunkIndex": idx}
                         for doc, idx in sorted(expected)],
            "note": question["note"],
        })

    if problems:
        print("发现设计错误，评测集未生成：", file=sys.stderr)
        for problem in problems:
            print("  " + problem, file=sys.stderr)
        sys.exit(1)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(eval_set, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    by_type = {}
    for case in eval_set:
        by_type.setdefault(case["type"], []).append(case)
    multi_doc = sum(1 for case in eval_set
                    if len({e["document"] for e in case["expected"]}) > 1)

    print(f"评测集已写入 {OUTPUT}")
    print(f"  问题总数 {len(eval_set)}，其中期望跨多个文档的 {multi_doc} 题")
    for kind in ("FACT", "TABLE", "MULTI_HOP", "TERM", "PARAPHRASE"):
        cases = by_type.get(kind, [])
        sizes = [len(c["expected"]) for c in cases]
        print(f"  {kind:11s} {len(cases):2d} 题，期望分块数 "
              f"最少 {min(sizes) if sizes else 0} 最大 {max(sizes) if sizes else 0}")


if __name__ == "__main__":
    main()
