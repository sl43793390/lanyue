/**
 * image-compressor — 浏览器本地图片压缩（Lit Web Component）。
 *
 * 对标 imagecompressor.com 的核心体验：图片只在浏览器里解码/重编码，
 * 不上传任何服务器；质量与颜色数可调，压缩后逐张展示前后体积与节省比例，
 * 单张下载。服务端对应 com.sl.ui.component.ImageCompressor 只是把组件挂进页面，
 * 没有任何属性同步——所有交互（选文件、压缩、下载）都发生在客户端。
 *
 * 各格式的编码路径：
 * - JPEG：MozJPEG WASM（@jsquash/jpeg，Squoosh 同源，与 imagecompressor.com
 *   同款编码器），progressive + 优化 Huffman + trellis 量化；WASM 不可用时
 *   降级 Canvas 原生编码器。JPEG 输出前先铺白底（透明区域直接编码会变黑）。
 * - WebP：Canvas 原生编码器，quality 1-100 直接映射 toBlob 的 0-1。
 * - PNG：Canvas 的 toBlob('image/png') 只有无损，压不动照片类图片，
 *   所以这里走「有损 PNG」：中位切分（median cut）建调色板 + 可选
 *   Floyd–Steinberg 抖动 + 逐像素映射到调色板索引，再用手写的
 *   PNG chunk 编码器（zlib 段用 CompressionStream('deflate')）产出
 *   索引色 PNG——这正是 pngquant 一类工具的压缩原理。
 * - GIF：浏览器无 GIF 编码器，按静态图处理转 PNG（只取第一帧）；
 *   SVG：按固有尺寸栅格化后走所选格式。
 * - 重编码天然丢掉 EXIF/GPS 等元数据（与 imagecompressor 的行为一致），
 *   且 createImageBitmap 默认按 EXIF orientation 摆正方向。
 *
 * 质量兜底：若压缩结果反而比原图大（小文件、已压缩过的图），保留原图字节，
 * 结果卡上标注「保留原图」，下载不会越压越大。
 */
import { LitElement, html, css, TemplateResult } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
// 解码/编码工具在 image-codecs.ts（与图片转换页共享）：
// JPEG=MozJPEG WASM、WebP/PNG=Canvas、解码统一 decodeToCanvas
import {
  decodeToCanvas, encodeJpeg, encodeWebp, fmtBytes, stripExt,
} from './image-codecs';

type OutputFormat = 'auto' | 'image/jpeg' | 'image/png' | 'image/webp';

interface CompressResult {
  file: File;
  status: 'working' | 'done' | 'error';
  /** 压缩后的字节（done 时有值；保留原图时等于原文件字节） */
  blob?: Blob;
  outName?: string;
  outUrl?: string;
  origSize: number;
  outSize: number;
  width?: number;
  height?: number;
  keptOriginal?: boolean;
  error?: string;
}

@customElement('image-compressor')
export class ImageCompressor extends LitElement {

  @property({ type: String }) outputFormat: OutputFormat = 'auto';
  @property({ type: Number }) quality = 80;
  @property({ type: Number }) pngColors = 256;
  @property({ type: Boolean }) dithering = true;

  @state() private items: CompressResult[] = [];
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
    .check {
      display: flex;
      align-items: center;
      gap: 6px;
      padding-bottom: 6px;
      user-select: none;
      cursor: pointer;
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
    .summary {
      font-size: 13px;
      color: var(--lumo-secondary-text-color, #6f7787);
    }
    .summary b { color: var(--lumo-body-text-color, #333); }
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
    /* 对比行：压之前 → 压之后，放结果最上方让效果一目了然 */
    .compare {
      display: flex;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 4px 16px;
      font-size: 15px;
      font-weight: 700;
      color: var(--lumo-body-text-color, #333);
    }
    .compare .save {
      color: var(--lumo-success-text-color, #1b7a1b);
      font-style: normal;
    }
    .compare .kept {
      color: var(--lumo-secondary-text-color, #6f7787);
      font-size: 13px;
      font-weight: 400;
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
    const done = this.items.filter(i => i.status === 'done');
    const origTotal = done.reduce((s, i) => s + i.origSize, 0);
    const outTotal = done.reduce((s, i) => s + i.outSize, 0);
    const saved = origTotal > 0 ? Math.round((1 - outTotal / origTotal) * 100) : 0;
    return html`
      <div class="wrap">
        <div class="card settings">
          <div class="field">
            <label>输出格式</label>
            <select @change=${this.onFormatChange}>
              <option value="auto" ?selected=${this.outputFormat === 'auto'}>保持原格式</option>
              <option value="image/jpeg" ?selected=${this.outputFormat === 'image/jpeg'}>JPEG</option>
              <option value="image/png" ?selected=${this.outputFormat === 'image/png'}>PNG</option>
              <option value="image/webp" ?selected=${this.outputFormat === 'image/webp'}>WebP</option>
            </select>
          </div>
          <div class="field">
            <label>压缩质量（JPEG / WebP）</label>
            <div>
              <input type="range" min="1" max="100" .value=${String(this.quality)}
                     @input=${(e: Event) => this.quality = Number((e.target as HTMLInputElement).value)} />
              <span class="val">${this.quality}</span>
            </div>
          </div>
          <div class="field">
            <label>颜色数（PNG / GIF→PNG）</label>
            <div>
              <input type="range" min="8" max="256" step="8" .value=${String(this.pngColors)}
                     @input=${(e: Event) => this.pngColors = Number((e.target as HTMLInputElement).value)} />
              <span class="val">${this.pngColors}</span>
            </div>
          </div>
          <label class="check">
            <input type="checkbox" .checked=${this.dithering}
                   @change=${(e: Event) => this.dithering = (e.target as HTMLInputElement).checked} />
            抖动（PNG）
          </label>
          <button class="btn secondary" ?disabled=${this.items.length === 0 || this.busy}
                  @click=${this.recompressAll}>重新压缩</button>
          <button class="btn secondary" ?disabled=${this.items.length === 0 || this.busy}
                  @click=${this.clearAll}>清空列表</button>
        </div>

        <div class="drop ${this.dragging ? 'over' : ''}"
             @click=${this.pickFiles}
             @dragover=${(e: DragEvent) => { e.preventDefault(); this.dragging = true; }}
             @dragleave=${() => this.dragging = false}
             @drop=${this.onDrop}>
          <div>点击选择图片，或把图片拖到这里</div>
          <div class="hint">支持 JPG / PNG / WebP / GIF / SVG / BMP，可多选；图片只在浏览器本地压缩，不会上传到服务器</div>
        </div>
        <input id="file-input" type="file" accept="image/*" multiple hidden
               @change=${this.onFilesPicked} />

        ${this.items.length === 0 ? html`
          <div class="empty">还没有图片。选择或拖入图片后立即自动压缩。</div>
        ` : html`
          <div class="summary">
            共 <b>${this.items.length}</b> 张，压缩后合计
            <b>${fmtBytes(origTotal)}</b> → <b>${fmtBytes(outTotal)}</b>，整体节省 <b>${Math.max(saved, 0)}%</b>
          </div>
          <div class="list">
            ${this.items.map((item, idx) => this.renderItem(item, idx))}
          </div>
        `}
      </div>
    `;
  }

  private renderItem(item: CompressResult, idx: number): TemplateResult {
    const ratio = item.origSize > 0 ? Math.round((1 - item.outSize / item.origSize) * 100) : 0;
    return html`
      <div class="item card">
        ${item.status === 'working' ? html`
          <div class="row">
            <div class="spin"></div>
            <div class="meta">
              <span class="name" title=${item.file.name}>${item.file.name}</span>
              <span class="sub">压缩中…</span>
            </div>
          </div>
        ` : item.status === 'error' ? html`
          <div class="row">
            <div class="meta">
              <span class="name" title=${item.file.name}>${item.file.name}</span>
              <span class="err">压缩失败：${item.error ?? '未知错误'}</span>
            </div>
            <button class="btn secondary" @click=${() => this.removeItem(idx)}>移除</button>
          </div>
        ` : html`
          <div class="compare">
            <span>之前： ${fmtBytes(item.origSize)}</span>
            <span>之后：
              ${item.keptOriginal
                ? html`${fmtBytes(item.outSize)} <em class="kept">（压缩后更大，已保留原图）</em>`
                : html`<em class="save">${fmtBytes(item.outSize)}${ratio > 0 ? html` (-${ratio}%)` : ''}</em>`}
            </span>
          </div>
          <div class="row">
            <img class="thumb" src=${item.outUrl ?? ''} alt="" />
            <div class="meta">
              <span class="name" title=${item.file.name}>${item.file.name}</span>
              <span class="sub">
                ${item.width != null ? html`${item.width}×${item.height}` : ''}
                ${item.width != null ? html` · ` : ''}${item.blob?.type === 'image/jpeg' ? 'MozJPEG' : '已重编码'}
              </span>
            </div>
            <a class="dl" href=${item.outUrl ?? '#'} download=${item.outName ?? ''}>下载</a>
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
    this.addFiles(Array.from(e.dataTransfer?.files ?? []).filter(f => f.type.startsWith('image/') || /\.(svg|bmp)$/i.test(f.name)));
  }

  private addFiles(files: File[]): void {
    if (files.length === 0) {
      return;
    }
    const added: CompressResult[] = files.map(f => ({
      file: f, status: 'working', origSize: f.size, outSize: f.size,
    }));
    this.items = [...this.items, ...added];
    this.compressList(added);
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

  private recompressAll(): void {
    const files = this.items.map(i => i.file);
    for (const item of this.items) {
      if (item.outUrl) {
        URL.revokeObjectURL(item.outUrl);
      }
    }
    const fresh: CompressResult[] = files.map(f => ({
      file: f, status: 'working', origSize: f.size, outSize: f.size,
    }));
    this.items = fresh;
    this.compressList(fresh);
  }

  private onFormatChange(e: Event): void {
    this.outputFormat = (e.target as HTMLSelectElement).value as OutputFormat;
  }

  // ------------------------------------------------------------------
  // 压缩主流程
  // ------------------------------------------------------------------

  private async compressList(list: CompressResult[]): Promise<void> {
    this.busy = true;
    try {
      for (const item of list) {
        // 设置可能在压缩过程中被改，逐张读取当前值，行为与 imagecompressor 一致
        try {
          await this.compressOne(item);
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

  private async compressOne(item: CompressResult): Promise<void> {
    const { file } = item;
    const target = this.resolveFormat(file);
    const canvas = await decodeToCanvas(file);
    item.width = canvas.width;
    item.height = canvas.height;

    let blob: Blob;
    if (target === 'image/jpeg') {
      blob = await encodeJpeg(canvas, this.quality);
    } else if (target === 'image/webp') {
      blob = await encodeWebp(canvas, this.quality);
    } else {
      blob = await encodePngIndexed(canvas, this.pngColors, this.dithering);
    }

    const ext = target === 'image/jpeg' ? 'jpg' : target.slice('image/'.length);
    const base = stripExt(file.name);
    item.status = 'done';
    if (blob.size < file.size) {
      item.blob = blob;
      item.outSize = blob.size;
      item.outName = `${base}-min.${ext}`;
    } else {
      // 越压越大：保留原字节，保证下载结果永远不劣化
      item.blob = file;
      item.outSize = file.size;
      item.outName = file.name;
      item.keptOriginal = true;
    }
    item.outUrl = URL.createObjectURL(item.blob);
  }

  /** 决定某张图的目标格式：auto 时按原图格式映射，GIF/SVG/BMP 转 PNG */
  private resolveFormat(file: File): 'image/jpeg' | 'image/png' | 'image/webp' {
    if (this.outputFormat !== 'auto') {
      return this.outputFormat;
    }
    const type = file.type.toLowerCase();
    if (type === 'image/jpeg' || type === 'image/webp') {
      return type;
    }
    if (type === 'image/png' || type === 'image/gif' || type === 'image/svg+xml' || type === 'image/bmp') {
      return 'image/png';
    }
    // avif 等浏览器能解码但 Canvas 不一定有的格式，转 WebP 兜底
    return 'image/webp';
  }
}

// --------------------------------------------------------------------
// 有损 PNG：调色板量化 + 索引色 PNG 编码
// --------------------------------------------------------------------

/**
 * 中位切分取调色板 →（可选）Floyd–Steinberg 抖动逐像素映射 → 写索引色 PNG。
 * 透明处理：任一像素 alpha < 255 即视为含透明度，alpha < 128 的像素归入
 * 保留的透明索引（二值透明，与 GIF/palette PNG 的常见做法一致）。
 */
async function encodePngIndexed(canvas: HTMLCanvasElement, colorCount: number, dither: boolean): Promise<Blob> {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) {
    throw new Error('无法创建 2D 画布');
  }
  const { width, height } = canvas;
  const data = ctx.getImageData(0, 0, width, height).data;

  let hasAlpha = false;
  for (let i = 3; i < data.length; i += 4) {
    if (data[i] < 255) {
      hasAlpha = true;
      break;
    }
  }

  const palette = medianCut(data, colorCount, hasAlpha);
  const indices = mapToPalette(data, width, height, palette, hasAlpha, dither);

  const trns = hasAlpha ? new Uint8Array(palette.length).fill(255) : null;
  const png = await encodeIndexedPng(width, height, indices, palette, trns);
  return new Blob([png], { type: 'image/png' });
}

/** 中位切分：采样最多 65536 个不透明像素，按最长轴反复二分到目标色数 */
function medianCut(data: Uint8ClampedArray, colorCount: number, hasAlpha: boolean): number[][] {
  const reserve = hasAlpha ? 1 : 0;          // 留一个槽位给透明索引
  const target = Math.max(2, Math.min(256, colorCount)) - reserve;
  const maxSample = 65536;

  // 采样不透明像素
  const pixels: number[][] = [];
  const total = data.length / 4;
  const stride = Math.max(1, Math.floor(total / maxSample));
  for (let p = 0; p < total; p += stride) {
    const i = p * 4;
    if (data[i + 3] >= 128) {
      pixels.push([data[i], data[i + 1], data[i + 2]]);
    }
  }
  if (pixels.length === 0) {
    // 全透明图：给一个纯色调色板占位
    return hasAlpha ? [[0, 0, 0], [255, 255, 255]] : [[255, 255, 255]];
  }

  let boxes: number[][][] = [pixels];
  while (boxes.length < target) {
    // 找像素最多且轴跨度 > 0 的盒子拆分，全不可拆即提前结束
    let best = -1;
    let bestAxis = -1;
    let bestSpan = 0;
    for (let b = 0; b < boxes.length; b++) {
      const box = boxes[b];
      if (box.length < 2) {
        continue;
      }
      const span = axisSpan(box);
      if (span.span > bestSpan) {
        bestSpan = span.span;
        best = b;
        bestAxis = span.axis;
      }
    }
    if (best < 0) {
      break;
    }
    const box = boxes[best];
    box.sort((a, b) => a[bestAxis] - b[bestAxis]);
    const mid = box.length >> 1;
    boxes = boxes.filter((_, i) => i !== best)
      .concat([box.slice(0, mid), box.slice(mid)]);
  }

  return boxes.map(box => {
    let r = 0, g = 0, b = 0;
    for (const px of box) {
      r += px[0]; g += px[1]; b += px[2];
    }
    const n = box.length;
    return [Math.round(r / n), Math.round(g / n), Math.round(b / n)];
  }).concat(hasAlpha ? [[0, 0, 0]] : []);   // 末位=透明索引
}

function axisSpan(box: number[][]): { axis: number; span: number } {
  let rMin = 255, rMax = 0, gMin = 255, gMax = 0, bMin = 255, bMax = 0;
  for (const [r, g, b] of box) {
    if (r < rMin) rMin = r; if (r > rMax) rMax = r;
    if (g < gMin) gMin = g; if (g > gMax) gMax = g;
    if (b < bMin) bMin = b; if (b > bMax) bMax = b;
  }
  const rs = rMax - rMin, gs = gMax - gMin, bs = bMax - bMin;
  if (rs >= gs && rs >= bs) {
    return { axis: 0, span: rs };
  }
  return gs >= bs ? { axis: 1, span: gs } : { axis: 2, span: bs };
}

/** 逐像素映射到调色板索引；透明像素映射到末位索引，不参与误差扩散 */
function mapToPalette(
  data: Uint8ClampedArray, width: number, height: number,
  palette: number[][], hasAlpha: boolean, dither: boolean,
): Uint8Array {
  const out = new Uint8Array(width * height);
  const transIdx = palette.length - 1;
  const opaqueCount = hasAlpha ? palette.length - 1 : palette.length;

  // 就近查找缓存：RGB 各取高 6 位做 key，命中即免线性扫描
  const cache = new Int32Array(1 << 18).fill(-1);
  const nearest = (r: number, g: number, b: number): number => {
    const key = ((r >> 2) << 12) | ((g >> 2) << 6) | (b >> 2);
    const hit = cache[key];
    if (hit >= 0) {
      return hit;
    }
    let bestIdx = 0;
    let bestDist = Infinity;
    for (let p = 0; p < opaqueCount; p++) {
      const dr = r - palette[p][0];
      const dg = g - palette[p][1];
      const db = b - palette[p][2];
      const dist = dr * dr + dg * dg + db * db;
      if (dist < bestDist) {
        bestDist = dist;
        bestIdx = p;
      }
    }
    cache[key] = bestIdx;
    return bestIdx;
  };

  if (!dither) {
    for (let p = 0, i = 0; p < out.length; p++, i += 4) {
      out[p] = data[i + 3] < 128 && hasAlpha ? transIdx : nearest(data[i], data[i + 1], data[i + 2]);
    }
    return out;
  }

  // Floyd–Steinberg：双行误差缓冲，向右/下扩散 7/3/5/1 十六分误差
  const errA = new Float32Array(width * 3);
  const errB = new Float32Array(width * 3);
  let cur = errA;
  let next = errB;
  for (let y = 0; y < height; y++) {
    next.fill(0);
    for (let x = 0; x < width; x++) {
      const p = y * width + x;
      const i = p * 4;
      if (hasAlpha && data[i + 3] < 128) {
        out[p] = transIdx;
        continue;
      }
      const r = clamp255(data[i] + cur[x * 3]);
      const g = clamp255(data[i + 1] + cur[x * 3 + 1]);
      const b = clamp255(data[i + 2] + cur[x * 3 + 2]);
      const idx = nearest(r, g, b);
      out[p] = idx;
      const er = r - palette[idx][0];
      const eg = g - palette[idx][1];
      const eb = b - palette[idx][2];
      if (x + 1 < width) {
        cur[(x + 1) * 3] += er * 7 / 16;
        cur[(x + 1) * 3 + 1] += eg * 7 / 16;
        cur[(x + 1) * 3 + 2] += eb * 7 / 16;
      }
      if (y + 1 < height) {
        if (x > 0) {
          next[(x - 1) * 3] += er * 3 / 16;
          next[(x - 1) * 3 + 1] += eg * 3 / 16;
          next[(x - 1) * 3 + 2] += eb * 3 / 16;
        }
        next[x * 3] += er * 5 / 16;
        next[x * 3 + 1] += eg * 5 / 16;
        next[x * 3 + 2] += eb * 5 / 16;
        if (x + 1 < width) {
          next[(x + 1) * 3] += er / 16;
          next[(x + 1) * 3 + 1] += eg / 16;
          next[(x + 1) * 3 + 2] += eb / 16;
        }
      }
    }
    const tmp = cur;
    cur = next;
    next = tmp;
  }
  return out;
}

function clamp255(v: number): number {
  return v < 0 ? 0 : v > 255 ? 255 : v;
}

// --------------------------------------------------------------------
// 索引色 PNG 编码（IHDR colorType=3 + PLTE + tRNS + zlib IDAT）
// --------------------------------------------------------------------

async function encodeIndexedPng(
  width: number, height: number, indices: Uint8Array,
  palette: number[][], trns: Uint8Array | null,
): Promise<Uint8Array<ArrayBuffer>> {
  // 原始扫描流：每行前置 filter 字节 0（None）
  const stride = width + 1;
  const raw = new Uint8Array(stride * height);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0;
    for (let x = 0; x < width; x++) {
      raw[y * stride + 1 + x] = indices[y * width + x];
    }
  }

  const chunks: Uint8Array[] = [];
  chunks.push(pngChunk('IHDR', u32be(width).concat(u32be(height), [8, 3, 0, 0, 0])));
  chunks.push(pngChunk('PLTE', flatPalette(palette)));
  if (trns) {
    chunks.push(pngChunk('tRNS', Array.from(trns)));
  }
  chunks.push(pngChunk('IDAT', Array.from(await zlibDeflate(raw))));
  chunks.push(pngChunk('IEND', []));

  let size = 8;
  for (const c of chunks) {
    size += c.length;
  }
  const png = new Uint8Array(size);
  png.set([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A], 0);
  let off = 8;
  for (const c of chunks) {
    png.set(c, off);
    off += c.length;
  }
  return png;
}

function u32be(v: number): number[] {
  return [(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff];
}

function flatPalette(palette: number[][]): number[] {
  const out: number[] = [];
  for (const [r, g, b] of palette) {
    out.push(r, g, b);
  }
  return out;
}

function pngChunk(type: string, data: number[]): Uint8Array {
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length);
  for (let i = 0; i < 4; i++) {
    out[4 + i] = type.charCodeAt(i);
  }
  out.set(data, 8);
  let crc = 0xffffffff;
  for (let i = 4; i < 8 + data.length; i++) {
    crc = CRC_TABLE[(crc ^ out[i]) & 0xff] ^ (crc >>> 8);
  }
  view.setUint32(8 + data.length, (crc ^ 0xffffffff) >>> 0);
  return out;
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c >>> 0;
  }
  return table;
})();

/** zlib 压缩（CompressionStream 的 'deflate' 即 zlib 格式，正是 PNG IDAT 要求的格式） */
async function zlibDeflate(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const cs = new CompressionStream('deflate');
  const writer = cs.writable.getWriter();
  writer.write(data);
  writer.close();
  const parts: Uint8Array[] = [];
  const reader = cs.readable.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    parts.push(value);
  }
  let size = 0;
  for (const p of parts) {
    size += p.length;
  }
  const out = new Uint8Array(size);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}
