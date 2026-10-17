/**
 * image-converter — 浏览器本地图片格式转换（Lit Web Component）。
 *
 * 对标 imagecompressor.com 的 Image Converter：HEIC/JPG/PNG/WebP/GIF/BMP/
 * TIFF/AVIF/ICO → JPG/PNG/WebP/AVIF，一次最多 20 个文件，选输出格式后
 * 点「开始转换」，逐文件展示结果并单张下载。所有解码/编码都在浏览器里
 * 完成（WebAssembly + Canvas），图片字节不上传服务器。
 *
 * 编解码实现见共享模块 image-codecs.ts：
 * - JPG 出：MozJPEG WASM（与 imagecompressor 同款）
 * - PNG 出：Canvas 无损重编码
 * - WebP 出：Canvas 编码器
 * - AVIF 出：libavif WASM（@jsquash/avif），quality 直接映射滑块；
 *   大图自动提速（speed 6→8），避免超大照片编码过久
 * - HEIC/HEIF 入：heic-to（libheif WASM）；TIFF 入：utif；
 *   AVIF/ICO/BMP 等入：浏览器原生 createImageBitmap
 *
 * 已知边界：动图（GIF/多页 TIFF/动态 WebP/AVIF 序列）取第一帧转静态图。
 */
import { LitElement, html, css, TemplateResult } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { encode as avifEncode } from '@jsquash/avif';
import {
  decodeToCanvas, encodeJpeg, encodePng, encodeWebp, fmtBytes, stripExt,
} from './image-codecs';

const MAX_FILES = 20;

type OutputFormat = 'image/jpeg' | 'image/png' | 'image/webp' | 'image/avif';

interface ConvertResult {
  file: File;
  status: 'pending' | 'working' | 'done' | 'error';
  blob?: Blob;
  outName?: string;
  outUrl?: string;
  origSize: number;
  outSize: number;
  width?: number;
  height?: number;
  encoder?: string;
  error?: string;
}

@customElement('image-converter')
export class ImageConverter extends LitElement {

  @property({ type: String }) outputFormat: OutputFormat = 'image/jpeg';
  @property({ type: Number }) quality = 80;

  @state() private items: ConvertResult[] = [];
  @state() private dragging = false;
  @state() private busy = false;

  @query('#file-input')
  private fileInput!: HTMLInputElement;

  static styles = css`
    :host {
      display: block;
      height: 100%;
      min-height: 0;
    }
    .wrap {
      height: 100%;
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-height: 0;
    }
    .card {
      background: var(--lumo-base-color, #fff);
      border: 1px solid var(--lumo-contrast-20pct, #d8d8d8);
      border-radius: var(--lumo-border-radius-m, 6px);
      padding: 12px 16px;
    }
    .settings {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 16px 24px;
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .field label {
      font-size: 12px;
      color: var(--lumo-secondary-text-color, #6f7787);
    }
    select {
      font: inherit;
      padding: 6px 8px;
      border: 1px solid var(--lumo-contrast-30pct, #c0c0c0);
      border-radius: var(--lumo-border-radius-s, 4px);
      background: var(--lumo-base-color, #fff);
      color: var(--lumo-body-text-color, #333);
      min-width: 150px;
    }
    input[type="range"] {
      width: 180px;
      accent-color: var(--lumo-primary-color, #1676f3);
    }
    .val {
      font-weight: 600;
      margin-left: 6px;
      min-width: 34px;
      display: inline-block;
    }
    .btn {
      font: inherit;
      border: none;
      border-radius: var(--lumo-border-radius-m, 6px);
      padding: 8px 18px;
      cursor: pointer;
      color: #fff;
      background: var(--lumo-primary-color, #1676f3);
    }
    .btn:disabled {
      opacity: 0.5;
      cursor: default;
    }
    .btn.secondary {
      background: var(--lumo-contrast-10pct, #e8e8e8);
      color: var(--lumo-body-text-color, #333);
    }
    .drop {
      border: 2px dashed var(--lumo-contrast-30pct, #bfbfbf);
      border-radius: var(--lumo-border-radius-m, 6px);
      padding: 26px 16px;
      text-align: center;
      cursor: pointer;
      color: var(--lumo-secondary-text-color, #6f7787);
      background: var(--lumo-contrast-5pct, #fafafa);
      transition: border-color .15s, background .15s;
    }
    .drop:hover, .drop.over {
      border-color: var(--lumo-primary-color, #1676f3);
      background: var(--lumo-primary-color-10pct, #eaf2ff);
      color: var(--lumo-primary-text-color, #1676f3);
    }
    .drop .hint {
      font-size: 12px;
      margin-top: 6px;
    }
    .list {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding-right: 4px;
    }
    .item {
      display: flex;
      flex-direction: column;
      align-items: stretch;
      gap: 8px;
    }
    /* 对比行放最上方：转换前 → 转换后，效果一眼可见 */
    .compare {
      display: flex;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 4px 16px;
      font-size: 15px;
      font-weight: 700;
      color: var(--lumo-body-text-color, #333);
    }
    .compare .fmt {
      color: var(--lumo-primary-text-color, #1676f3);
    }
    .row {
      display: flex;
      align-items: center;
      gap: 14px;
    }
    .thumb {
      width: 72px;
      height: 72px;
      border-radius: 4px;
      border: 1px solid var(--lumo-contrast-20pct, #d8d8d8);
      object-fit: contain;
      background: #fff;
      flex: none;
    }
    .meta {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .name {
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .sub {
      font-size: 12px;
      color: var(--lumo-secondary-text-color, #6f7787);
    }
    .dl {
      flex: none;
      font: inherit;
      font-size: 14px;
      border: 1px solid var(--lumo-primary-color, #1676f3);
      color: var(--lumo-primary-text-color, #1676f3);
      background: transparent;
      border-radius: var(--lumo-border-radius-m, 6px);
      padding: 6px 14px;
      cursor: pointer;
      text-decoration: none;
    }
    .dl:hover { background: var(--lumo-primary-color-10pct, #eaf2ff); }
    .err {
      color: var(--lumo-error-text-color, #c22b2b);
      font-size: 12px;
    }
    .spin {
      flex: none;
      width: 18px;
      height: 18px;
      border: 2px solid var(--lumo-contrast-30pct, #c0c0c0);
      border-top-color: var(--lumo-primary-color, #1676f3);
      border-radius: 50%;
      animation: rot .8s linear infinite;
    }
    @keyframes rot { to { transform: rotate(360deg); } }
    .empty {
      padding: 20px;
      text-align: center;
      color: var(--lumo-secondary-text-color, #6f7787);
      font-size: 13px;
    }
  `;

  protected render(): TemplateResult {
    const pending = this.items.filter(i => i.status === 'pending').length;
    return html`
      <div class="wrap">
        <div class="card settings">
          <div class="field">
            <label>输出格式</label>
            <select @change=${this.onFormatChange}>
              <option value="image/jpeg" ?selected=${this.outputFormat === 'image/jpeg'}>JPG</option>
              <option value="image/png" ?selected=${this.outputFormat === 'image/png'}>PNG</option>
              <option value="image/webp" ?selected=${this.outputFormat === 'image/webp'}>WebP</option>
              <option value="image/avif" ?selected=${this.outputFormat === 'image/avif'}>AVIF</option>
            </select>
          </div>
          <div class="field">
            <label>质量（JPG / WebP / AVIF）</label>
            <div>
              <input type="range" min="1" max="100" .value=${String(this.quality)}
                     @input=${(e: Event) => this.quality = Number((e.target as HTMLInputElement).value)} />
              <span class="val">${this.quality}</span>
            </div>
          </div>
          <button class="btn" ?disabled=${pending === 0 || this.busy}
                  @click=${this.convertAll}>开始转换${pending > 0 ? html`（${pending}）` : ''}</button>
          <button class="btn secondary" ?disabled=${this.items.length === 0 || this.busy}
                  @click=${this.clearAll}>清空列表</button>
        </div>

        <div class="drop ${this.dragging ? 'over' : ''}"
             @click=${this.pickFiles}
             @dragover=${(e: DragEvent) => { e.preventDefault(); this.dragging = true; }}
             @dragleave=${() => this.dragging = false}
             @drop=${this.onDrop}>
          <div>点击选择图像，或把图像拖到这里（一次最多 ${MAX_FILES} 个文件）</div>
          <div class="hint">支持 HEIC / JPG / PNG / WebP / GIF / BMP / TIFF / AVIF / ICO；
          转换只在浏览器本地进行，不会上传到服务器——快速、安全、私密</div>
        </div>
        <input id="file-input" type="file" accept="image/*,.heic,.heif,.hif,.tif,.tiff,.avif,.ico" multiple hidden
               @change=${this.onFilesPicked} />

        ${this.items.length === 0 ? html`
          <div class="empty">还没有图像。选择或拖入图像，选好输出格式后点「开始转换」。</div>
        ` : html`
          <div class="list">
            ${this.items.map((item, idx) => this.renderItem(item, idx))}
          </div>
        `}
      </div>
    `;
  }

  private renderItem(item: ConvertResult, idx: number): TemplateResult {
    return html`
      <div class="item card">
        ${item.status === 'pending' ? html`
          <div class="row">
            <div class="meta">
              <span class="name" title=${item.file.name}>${item.file.name}</span>
              <span class="sub">待转换 · ${fmtBytes(item.origSize)}</span>
            </div>
            <button class="btn secondary" @click=${() => this.removeItem(idx)}>移除</button>
          </div>
        ` : item.status === 'working' ? html`
          <div class="row">
            <div class="spin"></div>
            <div class="meta">
              <span class="name" title=${item.file.name}>${item.file.name}</span>
              <span class="sub">转换中…</span>
            </div>
          </div>
        ` : item.status === 'error' ? html`
          <div class="row">
            <div class="meta">
              <span class="name" title=${item.file.name}>${item.file.name}</span>
              <span class="err">转换失败：${item.error ?? '未知错误'}</span>
            </div>
            <button class="btn secondary" @click=${() => this.removeItem(idx)}>移除</button>
          </div>
        ` : html`
          <div class="compare">
            <span>之前： ${fmtBytes(item.origSize)}（${shortType(item.file)}）</span>
            <span>之后： <em class="fmt">${fmtBytes(item.outSize)} · ${item.encoder ?? ''}</em></span>
          </div>
          <div class="row">
            <img class="thumb" src=${item.outUrl ?? ''} alt="" />
            <div class="meta">
              <span class="name" title=${item.outName ?? ''}>${item.outName}</span>
              <span class="sub">${item.width != null ? html`${item.width}×${item.height}` : ''}</span>
            </div>
            <a class="dl" href=${item.outUrl ?? '#'} download=${item.outName ?? ''}>保存</a>
          </div>
        `}
      </div>
    `;
  }

  // ------------------------------------------------------------------
  // 文件入口
  // ------------------------------------------------------------------

  private pickFiles(): void {
    this.fileInput.click();
  }

  private onFilesPicked(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  private onDrop(e: DragEvent): void {
    e.preventDefault();
    this.dragging = false;
    this.addFiles(Array.from(e.dataTransfer?.files ?? []).filter(isSupportedImage));
  }

  private addFiles(files: File[]): void {
    if (files.length === 0) {
      return;
    }
    const remain = MAX_FILES - this.items.length;
    if (files.length > remain) {
      this.items = [...this.items];
      window.alert(`一次最多 ${MAX_FILES} 个文件，已只保留前 ${Math.max(remain, 0)} 个。`);
    }
    const added = files.slice(0, Math.max(remain, 0)).map(f => ({
      file: f, status: 'pending', origSize: f.size, outSize: 0,
    }) as ConvertResult);
    this.items = [...this.items, ...added];
  }

  private removeItem(idx: number): void {
    const item = this.items[idx];
    if (item?.outUrl) {
      URL.revokeObjectURL(item.outUrl);
    }
    this.items = this.items.filter((_, i) => i !== idx);
  }

  private clearAll(): void {
    for (const item of this.items) {
      if (item.outUrl) {
        URL.revokeObjectURL(item.outUrl);
      }
    }
    this.items = [];
  }

  private onFormatChange(e: Event): void {
    this.outputFormat = (e.target as HTMLSelectElement).value as OutputFormat;
  }

  // ------------------------------------------------------------------
  // 转换主流程
  // ------------------------------------------------------------------

  private async convertAll(): Promise<void> {
    // 重复转换前清掉旧结果（重新转换全部 pending 的文件）
    const targets = this.items.filter(i => i.status === 'pending' || i.status === 'error');
    if (targets.length === 0) {
      return;
    }
    this.busy = true;
    try {
      for (const item of targets) {
        if (item.outUrl) {
          URL.revokeObjectURL(item.outUrl);
          item.outUrl = undefined;
        }
        item.status = 'working';
        this.items = [...this.items];
        try {
          await this.convertOne(item);
        } catch (err) {
          item.status = 'error';
          item.error = err instanceof Error ? err.message : String(err);
        }
        this.items = [...this.items];
      }
    } finally {
      this.busy = false;
    }
  }

  private async convertOne(item: ConvertResult): Promise<void> {
    const { file } = item;
    const target = this.outputFormat;
    const canvas = await decodeToCanvas(file);
    item.width = canvas.width;
    item.height = canvas.height;

    let blob: Blob;
    let encoder: string;
    if (target === 'image/jpeg') {
      blob = await encodeJpeg(canvas, this.quality);
      encoder = 'MozJPEG';
    } else if (target === 'image/webp') {
      blob = await encodeWebp(canvas, this.quality);
      encoder = 'WebP';
    } else if (target === 'image/avif') {
      blob = await encodeAvif(canvas, this.quality);
      encoder = 'AVIF';
    } else {
      blob = await encodePng(canvas);
      encoder = 'PNG';
    }

    const ext = target.slice('image/'.length) === 'jpeg' ? 'jpg' : target.slice('image/'.length);
    item.status = 'done';
    item.blob = blob;
    item.outSize = blob.size;
    item.outName = `${stripExt(file.name)}.${ext}`;
    item.encoder = encoder;
    item.outUrl = URL.createObjectURL(blob);
  }
}

/** AVIF 编码：quality 直接映射滑块；大图提速（speed 6→8），避免编码过久 */
async function encodeAvif(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) {
    throw new Error('无法创建 2D 画布');
  }
  const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const speed = canvas.width * canvas.height > 2_000_000 ? 8 : 6;
  try {
    const buf = await avifEncode(imageData, { quality, speed });
    return new Blob([buf], { type: 'image/avif' });
  } catch {
    throw new Error('AVIF 编码失败（当前浏览器可能不支持 WASM SIMD）');
  }
}

/** 按扩展名/MIME 判断是否为支持的输入图（HEIC 等在 Windows 上 MIME 常为空） */
function isSupportedImage(f: File): boolean {
  if (f.type.startsWith('image/')) {
    return true;
  }
  return /\.(heic|heif|hif|jpe?g|png|webp|gif|bmp|tiff?|avif|ico|cur|svg)$/i.test(f.name);
}

function shortType(file: File): string {
  const ext = file.name.slice(file.name.lastIndexOf('.') + 1).toUpperCase();
  return ext || (file.type.replace('image/', '').toUpperCase() || '?');
}
