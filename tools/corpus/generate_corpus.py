"""生成星云数据平台的虚构文档集，作为 RAG 系统的检索评测语料。

用 Python 3.11 + fpdf2 + Windows 自带黑体，全部已在本机，无需安装任何东西。

用法（在项目根目录执行）：
    python tools/corpus/generate_corpus.py

输出到 src/test/resources/corpus/。生成的 PDF 要提交进仓库：
评测集的期望答案绑定在具体内容上，每次重新生成会让内容漂移、评测失效。
"""

import pathlib
import sys
import unicodedata

from fpdf import FPDF

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import nebula_content as content  # noqa: E402

FONT_PATH = r"C:\Windows\Fonts\simhei.ttf"
FONT_FAMILY = "SimHei"
OUTPUT_DIR = pathlib.Path(__file__).resolve().parents[2] / "src" / "test" / "resources" / "corpus"

PAGE_WIDTH = 210
MARGIN = 20
USABLE_WIDTH = PAGE_WIDTH - MARGIN * 2  # 170mm

BODY_SIZE = 11


def units_limit(size, width_mm=USABLE_WIDTH - 4):
    """按字号算出这一行最多能放多少个半角单位。

    实测 fpdf2 里一个全角字的宽度约等于 字号 × 0.3528mm（11pt 汉字 3.881mm），
    半角是一半。留 4mm 余量避免边界上的舍入把最后一行挤出去。
    """
    wide_mm = size * 0.3528
    return max(8, int(width_mm / wide_mm) * 2)


def char_units(ch):
    """全角字符按 2 个单位算，半角按 1 个。"""
    return 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1


def tokenize(text):
    """把文本切成「可断行」的单元：中文按单字，英文数字按连续串。"""
    tokens, buffer = [], ""
    for ch in text:
        if char_units(ch) == 2:
            if buffer:
                tokens.append(buffer)
                buffer = ""
            tokens.append(ch)
        else:
            buffer += ch
    if buffer:
        tokens.append(buffer)
    return tokens


def wrap(text, max_units):
    """按显示宽度断行，英文单词尽量不从中间断开。"""
    lines, current, used = [], "", 0
    for token in tokenize(text):
        width = sum(char_units(ch) for ch in token)
        if used + width > max_units and current:
            lines.append(current.rstrip())
            current, used = "", 0
        # 单个 token 本身就超宽（超长的英文标识符），只能硬切
        while width > max_units:
            head = ""
            head_used = 0
            for ch in token:
                if head_used + char_units(ch) > max_units:
                    break
                head += ch
                head_used += char_units(ch)
            lines.append(head)
            token = token[len(head):]
            width = sum(char_units(ch) for ch in token)
        current += token
        used += width
    if current.strip():
        lines.append(current.rstrip())
    return lines


class Document(FPDF):
    """带页眉页脚的多页文档。

    页眉和页脚是刻意加的：解析阶段有一段「剔除重复页眉页脚」的逻辑，
    旧语料没有页眉页脚，那段逻辑一直没被真实数据检验过。
    """

    def __init__(self, title):
        super().__init__(orientation="P", unit="mm", format="A4")
        self.doc_title = title
        # 黑体是单 face 的 TTF，直接注册即可；雅黑和宋体是 TTC，需要额外指定 face 序号
        self.add_font(FONT_FAMILY, "", FONT_PATH)
        self.set_margins(MARGIN, MARGIN, MARGIN)
        self.set_auto_page_break(auto=True, margin=25)

    def header(self):
        self.set_font(FONT_FAMILY, size=9)
        self.set_text_color(120, 120, 120)
        self.cell(0, 6, f"{self.doc_title} · {content.PRODUCT_EN} Technical Documentation",
                  align="C")
        self.ln(10)
        self.set_text_color(0, 0, 0)

    def footer(self):
        self.set_y(-18)
        self.set_font(FONT_FAMILY, size=9)
        self.set_text_color(120, 120, 120)
        self.cell(0, 6, f"第 {self.page_no()} 页", align="C")
        self.set_text_color(0, 0, 0)

    # ---------------------------------------------------------------- 内容元素

    def write_lines(self, text, size, line_height, align="L"):
        """按字号断行并逐行写出。

        必须显式写 new_x/new_y：multi_cell 的默认行为是把光标留在右边缘，
        后续再调 multi_cell(0, ...) 时可用宽度就变成 0，会直接抛
        「Not enough horizontal space to render a single character」。
        """
        self.set_font(FONT_FAMILY, size=size)
        for line in wrap(text, units_limit(size)):
            self.multi_cell(0, line_height, line, align=align,
                            new_x="LMARGIN", new_y="NEXT")

    def title_block(self, title, subtitle):
        self.add_page()
        self.ln(20)
        self.write_lines(title, size=20, line_height=12, align="C")
        self.ln(4)
        self.set_text_color(90, 90, 90)
        self.write_lines(subtitle, size=12, line_height=8, align="C")
        self.set_text_color(0, 0, 0)

    def heading(self, text, size=14):
        self.ln(4)
        self.write_lines(text, size=size, line_height=9)

    def paragraph(self, text):
        self.write_lines(text, size=BODY_SIZE, line_height=6.5)
        self.ln(2)

    def bullet_list(self, items):
        for item in items:
            self.write_lines(f"· {item}", size=BODY_SIZE, line_height=6.5)
        self.ln(1)

    def table_block(self, headers, rows, col_widths=None):
        self.ln(1)
        table_size = 9
        if col_widths is None:
            col_widths = [USABLE_WIDTH / len(headers)] * len(headers)

        self.set_font(FONT_FAMILY, size=table_size)
        self.set_fill_color(238, 238, 238)
        for width, header in zip(col_widths, headers):
            lines = wrap(header, units_limit(table_size, width))
            self.multi_cell(width, 5, "\n".join(lines), border=1, fill=True,
                            new_x="RIGHT", new_y="TOP")
        self.ln()

        for row in rows:
            # 先算这一行最高的格子需要几行，保证边框对齐
            cell_lines = [
                wrap(str(cell), units_limit(table_size, width))
                for width, cell in zip(col_widths, row)
            ]
            height = max(len(lines) for lines in cell_lines) * 5 + 2
            if self.get_y() + height > self.page_break_trigger:
                self.add_page()
                self.set_font(FONT_FAMILY, size=table_size)
            x0, y0 = self.get_x(), self.get_y()
            for index, (width, lines) in enumerate(zip(col_widths, cell_lines)):
                self.set_xy(x0 + sum(col_widths[:index]), y0)
                self.multi_cell(width, 5, "\n".join(lines), border=1,
                                new_x="RIGHT", new_y="TOP")
            self.set_xy(x0, y0 + height)
        self.ln(3)


def write_handbook():
    doc = Document("星云数据平台技术手册")
    doc.title_block(
        f"{content.PRODUCT}技术手册",
        f"{content.PRODUCT_EN} Handbook · 版本 4.2 · 适用 4.x 系列",
    )
    for section in content.HANDBOOK_SECTIONS:
        doc.heading(section["title"])
        for paragraph in section["paragraphs"]:
            doc.paragraph(paragraph)
        if "table" in section:
            doc.table_block(section["table"]["headers"], section["table"]["rows"],
                            col_widths=[62, 26, 82])
    return doc


def write_error_codes():
    doc = Document("星云数据平台错误码速查")
    doc.title_block(
        f"{content.PRODUCT}错误码速查表",
        "按错误码前缀定位组件：E-4xxx 接入层 / E-5xxx 调度层 / E-6xxx 存储层 / E-7xxx 权限层",
    )
    doc.heading("错误码总表")
    doc.paragraph(
        "下表按错误码升序列出平台全部已定义的错误码。"
        "任务详情页展示的错误码可以在这里查到含义、触发条件和标准处置动作。"
        "如果某个错误码不在表里，说明它是依赖组件抛出的原始错误，"
        "需要按组件名去对应团队的文档里查。"
    )
    doc.table_block(
        ["错误码", "含义", "触发条件", "处理建议"],
        content.ERROR_CODES,
        col_widths=[20, 30, 52, 68],
    )
    return doc


def write_faq():
    doc = Document("星云数据平台运维 FAQ")
    doc.title_block(
        f"{content.PRODUCT}运维常见问题",
        "收录值班与日常运维中反复出现的问题，按提问顺序排列",
    )
    for index, (question, answer) in enumerate(content.FAQ, start=1):
        doc.heading(f"Q{index}. {question}", size=12)
        doc.paragraph(answer)
    return doc


def write_oncall():
    doc = Document("星云数据平台值班手册")
    doc.title_block(
        f"{content.PRODUCT}值班手册",
        "值班职责、升级路径、标准处置动作与服务等级约定",
    )
    for section in content.ONCALL_SECTIONS:
        doc.heading(section["title"])
        for paragraph in section["paragraphs"]:
            doc.paragraph(paragraph)
        if "table" in section:
            rows = section["table"]["rows"]
            widths = [34, 30, 56, 50] if len(section["table"]["headers"]) == 4 else None
            doc.table_block(section["table"]["headers"], rows,
                            col_widths=[26, 44, 50, 50] if len(section["table"]["headers"]) == 4 else widths)
    return doc


def write_glossary():
    doc = Document("星云数据平台术语表")
    doc.title_block(
        f"{content.PRODUCT}术语表",
        "中英文对照，中文按条目名称排序",
    )
    doc.paragraph(
        "术语表用于统一平台内外的表述。写工单、查文档、和上下游沟通时优先使用这里的标准译名，"
        "避免同义词混用导致检索和检索都找不到。英文名用于对接国际团队和阅读上游开源文档。"
    )
    doc.table_block(
        ["中文", "English", "说明"],
        [(zh, en, desc) for zh, en, desc in content.GLOSSARY],
        col_widths=[26, 40, 104],
    )
    return doc


def write_changelog():
    doc = Document("星云数据平台变更记录")
    doc.title_block(
        f"{content.PRODUCT}变更记录",
        "按版本倒序，包含新能力、行为变更与已修复缺陷",
    )
    for version, date, items in content.CHANGELOG:
        doc.heading(f"{version}（{date}）", size=12)
        doc.bullet_list(items)
    return doc


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    documents = {
        "nebula-handbook.pdf": write_handbook,
        "nebula-error-codes.pdf": write_error_codes,
        "nebula-ops-faq.pdf": write_faq,
        "nebula-oncall-guide.pdf": write_oncall,
        "nebula-glossary.pdf": write_glossary,
        "nebula-changelog.pdf": write_changelog,
    }

    for name, build in documents.items():
        doc = build()
        path = OUTPUT_DIR / name
        doc.output(str(path))
        print(f"  {name:28s} {doc.pages_count:3d} 页  {path.stat().st_size / 1024:6.1f} KB")

    print(f"\n输出目录：{OUTPUT_DIR}")


if __name__ == "__main__":
    main()
