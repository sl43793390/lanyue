/**
 * text-diff — 浏览器本地文本对比（Lit Web Component）。
 *
 * 服务端对应 com.sl.ui.component.TextDiff，只是一个挂载壳：两边文本、diff 计算、
 * 行内标记全部发生在浏览器里，文本内容不上传服务器（与 image-compressor 同一思路）。
 *
 * 页面形态（2026-10 改版：去掉了底部「对比结果」栏，差异直接标回输入框）：
 * - 顶部工具行：「开始对比 / 清空 / 示例文本」，右侧对比后显示增减统计；
 * - 状态行：最近一次对比的结论（初始提示 / 已标记 N 处 / 两侧一致 / 内容已变需重比）；
 * - 中部两张卡片：原始文本 / 修改后的文本，各一个 code-editor（CodeMirror 6，带行号），
 *   占满标题以下的全部剩余空间。
 *
 * 差异不再单独列一份结果，而是直接标到两个输入框对应的行上（GitHub 式行内标记）：
 * - 左侧（原始文本）：被删除 / 被修改的行 → 红底 + 行首红竖条；
 * - 右侧（修改后的文本）：被新增 / 被修改的行 → 绿底 + 行首绿竖条。
 * 「修改」一行 = 左边红、右边绿成对出现；纯新增只在右边绿，纯删除只在左边红。
 * 滚动到哪一行都能看到标记，不用再去底部结果栏里找对应行。
 *
 * 两个输入框复用项目的通用编辑器组件 code-editor（CodeMirror 6，见
 * code-editor.ts）：等宽字体、行号、撤销历史都有了，比裸 textarea 更适合对文本。
 * 这里以副作用方式 import 它——只为把自定义元素注册上，并把
 * HTMLElementTagNameMap 的类型声明带进来（代码里不出现 CodeEditorElement 字面量，
 * 普通的 import 会被 tsc 当类型导入擦掉，注册就丢了）。行内标记走它暴露的
 * setLineHighlights(lines, kind) 与 scrollToLine(line) 两个方法。
 *
 * diff 算法在 text-diff-core.ts（Myers 线性空间差分），本文件只做组装与渲染。
 * 已知边界（有意为之）：diff 按整行比较，不做行内字符级高亮。
 */
import { LitElement, html, css } from 'lit';
import { customElement, state, query } from 'lit/decorators.js';
import { splitLines, diffLines, countChanges, DiffOp } from './text-diff-core';
import './code-editor.ts';

/** 「示例文本」灌入的左侧配置：本地库、root 账号、INFO 日志。 */
const SAMPLE_ORIGINAL = `server:
  port: 8080
  servlet:
    context-path: /log
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lanyue?useSSL=false
    username: root
    password: root123
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: none
logging:
  level:
    com.sl: info`;

/** 「示例文本」灌入的右侧配置：改了端口与库地址、换了账号、加了连接池、调了日志级别。 */
const SAMPLE_MODIFIED = `server:
  port: 9095
  servlet:
    context-path: /log
spring:
  datasource:
    url: jdbc:mysql://192.168.80.160:3306/lanyue?useSSL=false
    username: lanyue
    password: lanyue@2026
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
  jpa:
    hibernate:
      ddl-auto: validate
logging:
  level:
    com.sl: debug
    org.springframework.web: warn`;

@customElement('text-diff')
export class TextDiff extends LitElement {

  /** idle=未对比/标记已清除，diff=有差异且已标记，same=两侧完全一致 */
  @state() private phase: 'idle' | 'diff' | 'same' = 'idle';
  @state() private added = 0;
  @state() private deleted = 0;
  /** 状态行文案；初始给一条操作提示 */
  @state() private status = '输入两段文本后点击「开始对比」（或 Ctrl+Enter），差异会直接标在两侧编辑器对应的行上';

  private aLines: string[] = [];
  private bLines: string[] = [];

  @query('#orig')
  private orig!: HTMLElementTagNameMap['code-editor'];
  @query('#mod')
  private mod!: HTMLElementTagNameMap['code-editor'];

  constructor() {
    super();
    /*
     * Ctrl+Enter 快捷对比。监听器挂在 host 上且用捕获阶段 + stopPropagation：
     * 两个输入框是 shadow 里的 code-editor（CodeMirror），Enter 会被它的键位表
     * 处理成插入换行——冒泡阶段拦截已经晚了，光标处会先多出一个换行。
     * 在 host 捕获阶段拦住，事件根本进不到 CodeMirror。
     */
    this.addEventListener('keydown', this.onHostKeydown, true);
    /*
     * 用户改动任一输入框 → 之前按行号打的标记全部作废：内容一变行号就对不上，
     * 颜色会错位到别的行上。这里立即清除并提示重新对比。
     * 程序式灌值（示例文本 / 清空）走 value 属性，不会派发 value-changed，不受影响。
     */
    this.addEventListener('value-changed', this.onUserEdit);
  }

  private onHostKeydown = (e: KeyboardEvent): void => {
    if (e.ctrlKey && e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      this.compare();
    }
  };

  private onUserEdit = (): void => {
    this.clearMarks();
    this.status = '输入内容已变化，之前的标记已清除，请重新点击「开始对比」';
  };

  /**
   * 清除两侧行内标记与统计。
   * @param silent true 时连状态行一起复位成初始提示（「清空」按钮用）；
   *               false 保留状态行，由调用方决定写什么（用户编辑场景）。
   */
  private clearMarks(silent = false): void {
    this.orig?.setLineHighlights([], 'del');
    this.mod?.setLineHighlights([], 'ins');
    this.phase = 'idle';
    this.added = 0;
    this.deleted = 0;
    if (silent) {
      this.status = '输入两段文本后点击「开始对比」（或 Ctrl+Enter），差异会直接标在两侧编辑器对应的行上';
    }
  }

  /**
   * 「开始对比」：读两侧文本 → 本地算 diff → 把差异行标回两个编辑器 → 更新统计。
   * 异步：要等两个输入框把各自 value 同步进 CodeMirror（Lit 的更新周期是异步的），
   * 再按行号打标记——否则 doc 还是旧内容，行号越界会被静默过滤，标记就“神秘消失”了。
   */
  private async compare(): Promise<void> {
    const a = this.orig.value;
    const b = this.mod.value;
    if (!a.trim() && !b.trim()) {
      // 两侧都空：回到初始提示，不制造"全删除/全新增"的噪音结果
      this.clearMarks(true);
      this.status = '两侧均为空，请先输入文本再对比';
      return;
    }
    this.aLines = splitLines(a);
    this.bLines = splitLines(b);
    const ops: DiffOp[] = diffLines(this.aLines, this.bLines);
    const { added, deleted } = countChanges(ops);
    this.added = added;
    this.deleted = deleted;
    // del → 左侧原始文本标红；ins → 右侧修改后文本标绿。
    // 「修改」= 同一处的 del+ins 成对出现，两侧都会标到；纯新增只在右边，纯删除只在左边。
    const aMarks: number[] = [];
    const bMarks: number[] = [];
    for (const op of ops) {
      if (op.type === 'del') {
        for (let k = op.aStart; k < op.aEnd; k++) aMarks.push(k + 1);
      } else if (op.type === 'ins') {
        for (let k = op.bStart; k < op.bEnd; k++) bMarks.push(k + 1);
      }
    }
    this.phase = added === 0 && deleted === 0 ? 'same' : 'diff';
    this.status = this.phase === 'same'
      ? '两段文本完全一致，没有差异'
      : `已标记 ${deleted} 行删除/修改（左，红底）、${added} 行新增/修改（右，绿底）`;
    // 等两侧 doc 同步完成再打标记（示例文本/清空都是先写 value 再对比，有竞态）
    await Promise.all([this.orig.updateComplete, this.mod.updateComplete]);
    this.orig.setLineHighlights(aMarks, 'del');
    this.mod.setLineHighlights(bMarks, 'ins');
    // 两侧各自跳到第一处差异，长文本不用自己翻
    if (aMarks.length) this.orig.scrollToLine(aMarks[0]);
    if (bMarks.length) this.mod.scrollToLine(bMarks[0]);
  }

  /** 「清空」：输入、标记、统计全部复位。 */
  private clearAll(): void {
    this.orig.value = '';
    this.mod.value = '';
    this.clearMarks(true);
    this.orig.focusEditor();
  }

  /** 「示例文本」：灌入一对示例配置，并立即跑一次对比，直接看到效果。 */
  private fillSample(): void {
    this.orig.value = SAMPLE_ORIGINAL;
    this.mod.value = SAMPLE_MODIFIED;
    this.compare();
  }

  static styles = css`
    :host {
      display: block;
      height: 100%;
      min-height: 0;
    }
    .page {
      height: 100%;
      min-height: 0;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 14px 16px;
      background: #f5f6f8;
      border-radius: var(--lumo-border-radius-l, 8px);
      font-size: var(--lumo-font-size-s, 13px);
      color: var(--lumo-body-text-color, #1f2937);
    }
    /* ---------- 顶部工具行 ---------- */
    .toolbar {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }
    .toolbar-spacer {
      flex: 1 1 auto;
    }
    .btn {
      border-radius: 8px;
      font: inherit;
      font-size: 13px;
      padding: 7px 16px;
      cursor: pointer;
      transition: box-shadow 0.15s ease, border-color 0.15s ease, background-color 0.15s ease;
    }
    .btn-primary {
      border: none;
      background: linear-gradient(135deg, #ff9a3c, #ff5e62);
      color: #fff;
      font-weight: 600;
      box-shadow: 0 1px 3px rgba(255, 94, 98, 0.35);
    }
    .btn-primary:hover {
      box-shadow: 0 2px 6px rgba(255, 94, 98, 0.45);
    }
    .btn-plain {
      background: var(--lumo-base-color, #fff);
      border: 1px solid #d8dce2;
      color: #374151;
    }
    .btn-plain:hover {
      border-color: #b9c0c9;
      background: #fafbfc;
    }
    /* ---------- 增减统计（对比后显示在工具行右侧） ---------- */
    .stats {
      display: flex;
      gap: 14px;
      font-size: 13px;
      font-weight: 600;
    }
    .stat-add {
      color: #16a34a;
    }
    .stat-del {
      color: #dc2626;
    }
    /* ---------- 状态行：最近一次对比的结论 ---------- */
    .status {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12.5px;
      color: #6b7280;
    }
    .status::before {
      content: '';
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #9aa3af;
      flex: 0 0 auto;
    }
    .status.same {
      color: #116329;
    }
    .status.same::before {
      background: #1a7f37;
    }
    /* ---------- 输入卡片 ---------- */
    .inputs {
      flex: 1 1 0;
      min-height: 150px;
      display: flex;
      gap: 14px;
    }
    .card {
      background: var(--lumo-base-color, #fff);
      border: 1px solid #e4e7eb;
      border-radius: 8px;
      box-shadow: 0 1px 2px rgba(31, 41, 55, 0.04);
    }
    .input-card {
      flex: 1 1 0;
      min-width: 0;
      display: flex;
      flex-direction: column;
      padding: 10px 12px 12px;
    }
    .card-title {
      flex: 0 0 auto;
      font-size: 14px;
      font-weight: 600;
      color: var(--lumo-header-text-color, #1f2937);
      margin-bottom: 8px;
    }
    /* 卡片标题右侧的图例：两种颜色各是什么含义 */
    .legend {
      margin-left: 8px;
      padding: 1px 8px;
      border-radius: 999px;
      font-size: 11.5px;
      font-weight: 500;
      vertical-align: 1px;
    }
    .legend-del {
      color: #82071e;
      background: #ffebe9;
    }
    .legend-ins {
      color: #116329;
      background: #e6ffec;
    }
    /* 输入框：复用 code-editor（CodeMirror 6，带行号）。
       宿主元素的 flex 属性由外层（这里）说了算——shadow 里的 :host 规则
       优先级低于外层树样式；内部 .wrap 是 height:100%，撑满 host 即可。 */
    #orig, #mod {
      flex: 1 1 auto;
      min-height: 0;
    }
  `;

  protected render() {
    return html`
      <div class="page">
        <div class="toolbar">
          <button type="button" class="btn btn-primary" @click=${this.compare}>开始对比</button>
          <button type="button" class="btn btn-plain" @click=${this.clearAll}>清空</button>
          <button type="button" class="btn btn-plain" @click=${this.fillSample}>示例文本</button>
          <span class="toolbar-spacer"></span>
          ${this.phase === 'idle'
            ? ''
            : html`<span class="stats">
                <span class="stat-del">-${this.deleted} 行删除/修改</span>
                <span class="stat-add">+${this.added} 行新增/修改</span>
              </span>`}
        </div>
        <div class="status ${this.phase}">${this.status}</div>
        <div class="inputs">
          <div class="card input-card">
            <div class="card-title">原始文本<span class="legend legend-del">删除/修改的行</span></div>
            <code-editor id="orig" mode="plain" placeholder="输入原始文本..."></code-editor>
          </div>
          <div class="card input-card">
            <div class="card-title">修改后的文本<span class="legend legend-ins">新增/修改的行</span></div>
            <code-editor id="mod" mode="plain" placeholder="输入修改后的文本..."></code-editor>
          </div>
        </div>
      </div>
    `;
  }
}
