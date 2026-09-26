/**
 * code-editor — 基于 CodeMirror 6 的轻量级代码编辑器（Lit Web Component）。
 *
 * 服务端对应 com.sl.ui.component.CodeEditor，两边只同步三个属性：
 * - value：编辑内容（客户端改动派发 value-changed 事件带回，detail.value 是全文）；
 * - mode：语法模式 plain / yaml / shell / nginx / properties；
 * - readOnly：只读开关。
 *
 * 包选型（都在 pom 侧用 @NpmPackage 声明）：
 * - codemirror 元包：basicSetup 自带行号 / 撤销历史 / 括号匹配 / 代码折叠，
 *   对「改配置、写命令」这类场景刚好，不引高级扩展保持轻量；
 * - @codemirror/lang-yaml：compose 文件的完整语法支持；
 * - @codemirror/legacy-modes：nginx / shell / properties 的 StreamLanguage 移植模式。
 */
import { LitElement, html, css, PropertyValues } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { EditorView } from '@codemirror/view';
import { EditorState, Compartment, Extension } from '@codemirror/state';
import { basicSetup } from 'codemirror';
import { yaml } from '@codemirror/lang-yaml';
import { StreamLanguage } from '@codemirror/language';
import { nginx } from '@codemirror/legacy-modes/mode/nginx';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { properties } from '@codemirror/legacy-modes/mode/properties';

@customElement('code-editor')
export class CodeEditorElement extends LitElement {

  /** 编辑内容全文；服务端 setValue 会推进来，客户端输入会派发事件带回去 */
  @property({ type: String }) value = '';

  /** 语法模式：plain / yaml / shell / nginx / properties */
  @property({ type: String }) mode = 'plain';

  /** 只读开关 */
  @property({ type: Boolean }) readOnly = false;

  /** 语言用 Compartment 挂载，切模式只 reconfigure 这一段，不重建编辑器 */
  private langComp = new Compartment();
  private readOnlyComp = new Compartment();
  private view: EditorView | null = null;
  /** 服务端推值引发的 docChanged 不回发事件，否则成环 */
  private applying = false;

  static styles = css`
    :host {
      display: block;
      /* flex 纵向容器里靠 flex-grow 撑高时，允许被压缩到内容以下 */
      min-height: 0;
    }
    .wrap {
      height: 100%;
      min-height: 60px;
      border: 1px solid var(--lumo-contrast-20pct, #d0d0d0);
      border-radius: var(--lumo-border-radius-m, 6px);
      overflow: hidden;
      background: var(--lumo-base-color, #fff);
    }
    .cm-editor {
      height: 100%;
      background: transparent;
    }
    .cm-editor.cm-focused {
      outline: none;
      border-color: var(--lumo-primary-color-50pct);
    }
    .cm-scroller {
      font-family: var(--lumo-font-family-monospace, Consolas, 'Courier New', monospace) !important;
      font-size: var(--code-editor-font-size, 15px) !important;
      line-height: 1.55;
    }
  `;

  protected firstUpdated(): void {
    const wrap = this.renderRoot.querySelector('.wrap') as HTMLElement;
    this.view = new EditorView({
      state: EditorState.create({
        doc: this.value ?? '',
        extensions: [
          basicSetup,
          this.langComp.of(this.langExtension()),
          this.readOnlyComp.of(EditorState.readOnly.of(this.readOnly)),
          EditorView.updateListener.of((u) => {
            if (!u.docChanged || this.applying) {
              return;
            }
            this.value = u.state.doc.toString();
            this.dispatchEvent(
              new CustomEvent('value-changed', {
                detail: { value: this.value },
                bubbles: true,
                composed: true,
              })
            );
          }),
        ],
      }),
      parent: wrap,
    });
  }

  protected updated(changed: PropertyValues<this>): void {
    if (!this.view) {
      return;
    }
    if (changed.has('mode')) {
      this.view.dispatch({ effects: this.langComp.reconfigure(this.langExtension()) });
    }
    if (changed.has('readOnly')) {
      this.view.dispatch({
        effects: this.readOnlyComp.reconfigure(EditorState.readOnly.of(this.readOnly)),
      });
    }
    if (changed.has('value') && this.view.state.doc.toString() !== this.value) {
      this.applying = true;
      try {
        this.view.dispatch({
          changes: { from: 0, to: this.view.state.doc.length, insert: this.value ?? '' },
        });
      } finally {
        this.applying = false;
      }
    }
  }

  protected render() {
    return html`<div class="wrap"></div>`;
  }

  /** 服务端 focus 走这里：CodeMirror 的可编辑面是 contenteditable 的 .cm-content */
  focusEditor(): void {
    if (this.view) {
      this.view.focus();
    }
  }

  private langExtension(): Extension {
    switch (this.mode) {
      case 'yaml':
        return yaml();
      case 'shell':
        return StreamLanguage.define(shell);
      case 'nginx':
        return StreamLanguage.define(nginx);
      case 'properties':
        return StreamLanguage.define(properties);
      default:
        return [];
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'code-editor': CodeEditorElement;
  }
}
