/**
 * image-codecs — 图片压缩/转换共用的浏览器端编解码工具。
 *
 * 被两个页面共享：image-compressor.ts（压缩）与 image-converter.ts（格式转换）。
 * 所有解码/编码都在浏览器本地完成，图片字节不上传服务器。
 *
 * 解码路径（decodeToCanvas）：
 * - HEIC/HEIF：heic-to（libheif WASM，浏览器构建）→ ImageBitmap；
 * - TIFF：utif（photopea/UTIF.js 纯 JS）→ RGBA → ImageData；
 * - 其余（JPG/PNG/WebP/GIF/BMP/AVIF/ICO）走 createImageBitmap，
 *   失败再退 <img> 解码；SVG 单独走 <img>（需按固有尺寸栅格化）。
 *   createImageBitmap 默认按 EXIF orientation 摆正方向。
 *
 * 编码路径（encodeJpeg/encodeWebp/encodePng）：
 * - JPEG：MozJPEG WASM（@jsquash/jpeg，Squoosh 同源），progressive +
 *   优化 Huffman + trellis 量化；WASM 不可用时降级 Canvas 原生编码器。
 *   JPEG 无 alpha，输出前先铺白底（透明区域直接编码会变黑）。
 * - WebP：Canvas 原生编码器，quality 1-100 映射 toBlob 的 0-1。
 * - PNG：无损，Canvas toBlob('image/png')。
 * - AVIF：@jsquash/avif（libavif WASM）在 image-converter.ts 内实现，
 *   因为编码参数与速度策略是转换页特有的。
 */
import { encode as mozjpegEncode } from '@jsquash/jpeg';
import { heicTo } from 'heic-to';
import * as UTIF from 'utif';

// --------------------------------------------------------------------
// 解码
// --------------------------------------------------------------------

/**
 * 解码任意受支持图片为 canvas（像素已摆正、尺寸为真实像素）。
 * 大小兜底：超过 1 亿像素直接报错，防止内存爆掉。
 */
export async function decodeToCanvas(file: File): Promise<HTMLCanvasElement> {
  const { source, width, height } = await decodeSource(file);
  if (width * height > 100_000_000) {
    throw new Error('图片过大（超过 1 亿像素）');
  }
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('无法创建 2D 画布');
  }
  ctx.drawImage(source as CanvasImageSource, 0, 0);
  if ('close' in source && typeof source.close === 'function') {
    source.close();
  }
  return canvas;
}

async function decodeSource(file: File): Promise<{
  source: ImageBitmap | HTMLImageElement;
  width: number;
  height: number;
}> {
  const name = file.name.toLowerCase();
  const type = (file.type || '').toLowerCase();

  // HEIC/HEIF：libheif WASM 解码（Windows/Chrome 原生不支持）
  if (type.includes('heic') || type.includes('heif')
      || /\.(heic|heif|hif)$/.test(name)) {
    const bitmap = await heicTo({ blob: file, type: 'bitmap' });
    return { source: bitmap, width: bitmap.width, height: bitmap.height };
  }

  // TIFF：UTIF 纯 JS 解码（浏览器原生不支持），多页取第一页
  if (type.includes('tiff') || /\.(tif|tiff)$/.test(name)) {
    return decodeTiff(file);
  }

  // SVG：走 <img>（无固有尺寸时兜底 1024）
  if (type === 'image/svg+xml' || /\.svg$/.test(name)) {
    const img = await decodeViaImg(file);
    return { source: img, width: img.naturalWidth || 1024, height: img.naturalHeight || 1024 };
  }

  // 其余格式交给浏览器原生解码（JPG/PNG/WebP/GIF/BMP/AVIF/ICO…）
  try {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
    return { source: bitmap, width: bitmap.width, height: bitmap.height };
  } catch {
    // 少数变体 createImageBitmap 失败，退回 <img> 解码
    const img = await decodeViaImg(file);
    return { source: img, width: img.naturalWidth, height: img.naturalHeight };
  }
}

/** TIFF → RGBA → ImageData → canvas；多页取第一页 */
async function decodeTiff(file: File): Promise<{
  source: ImageBitmap | HTMLImageElement;
  width: number;
  height: number;
}> {
  const buf = await file.arrayBuffer();
  const ifds = UTIF.decode(buf);
  if (ifds.length === 0) {
    throw new Error('TIFF 中没有图像数据');
  }
  const ifd = ifds[0];
  UTIF.decodeImage(buf, ifd);
  const rgba = UTIF.toRGBA8(ifd);          // 统一转 8bit RGBA
  const width = ifd.width;
  const height = ifd.height;
  if (!width || !height) {
    throw new Error('TIFF 尺寸信息缺失');
  }
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('无法创建 2D 画布');
  }
  // toRGBA8 返回 Uint8Array，拷贝成 ImageData 要求的 Uint8ClampedArray
  ctx.putImageData(new ImageData(new Uint8ClampedArray(rgba), width, height), 0, 0);
  const bitmap = await createImageBitmap(canvas);
  return { source: bitmap, width, height };
}

function decodeViaImg(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('浏览器无法解码该图片')); };
    img.src = url;
  });
}

// --------------------------------------------------------------------
// 编码
// --------------------------------------------------------------------

export async function encodeJpeg(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  const flat = flattenForJpeg(canvas);
  // 首选 MozJPEG WASM（与 imagecompressor.com 同款编码器）
  if (await initMozjpeg()) {
    try {
      const ctx = flat.getContext('2d', { willReadFrequently: true });
      if (!ctx) {
        throw new Error('无法创建 2D 画布');
      }
      const imageData = ctx.getImageData(0, 0, flat.width, flat.height);
      const buf = await mozjpegEncode(imageData, { quality });
      return new Blob([buf], { type: 'image/jpeg' });
    } catch {
      // WASM 初始化或编码失败（如内存不足），降级到 Canvas 编码器
    }
  }
  const blob = await toBlob(flat, 'image/jpeg', quality / 100);
  if (!blob) {
    throw new Error('JPEG 编码失败');
  }
  return blob;
}

/** MozJPEG WASM 预热；成功返回 true。只初始化一次，失败后不再重试 */
let mozjpegInit: Promise<boolean> | null = null;
function initMozjpeg(): Promise<boolean> {
  mozjpegInit ??= (async () => {
    try {
      await mozjpegEncode(new ImageData(new Uint8ClampedArray(4), 1, 1), { quality: 80 });
      return true;
    } catch {
      return false;
    }
  })();
  return mozjpegInit;
}

/** JPEG 无 alpha 通道：返回铺了白底的画布，否则透明区域会被编码成黑色 */
export function flattenForJpeg(canvas: HTMLCanvasElement): HTMLCanvasElement {
  const flat = document.createElement('canvas');
  flat.width = canvas.width;
  flat.height = canvas.height;
  const fctx = flat.getContext('2d');
  if (!fctx) {
    throw new Error('无法创建 2D 画布');
  }
  fctx.fillStyle = '#ffffff';
  fctx.fillRect(0, 0, flat.width, flat.height);
  fctx.drawImage(canvas, 0, 0);
  return flat;
}

export async function encodeWebp(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  const blob = await toBlob(canvas, 'image/webp', quality / 100);
  if (!blob || blob.type !== 'image/webp') {
    throw new Error('当前浏览器不支持 WebP 编码');
  }
  return blob;
}

export async function encodePng(canvas: HTMLCanvasElement): Promise<Blob> {
  const blob = await toBlob(canvas, 'image/png');
  if (!blob) {
    throw new Error('PNG 编码失败');
  }
  return blob;
}

export function toBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(b => resolve(b), type, quality);
    // toBlob 内部异常不会走 onerror，只能靠超时兜底；正常路径毫秒级返回
    setTimeout(() => reject(new Error(type + ' 编码超时')), 60_000);
  });
}

// --------------------------------------------------------------------
// 小工具
// --------------------------------------------------------------------

export function fmtBytes(n: number): string {
  if (n < 1024) {
    return n + ' B';
  }
  if (n < 1024 * 1024) {
    return (n / 1024).toFixed(1) + ' KB';
  }
  return (n / 1024 / 1024).toFixed(2) + ' MB';
}

export function stripExt(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) : name;
}
