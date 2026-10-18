/**
 * text-diff-core — 文本对比的核心：按行切分 + Myers 线性空间差分算法。
 *
 * 这个文件**不依赖 lit / 浏览器 DOM**，是纯函数模块：
 * - text-diff.ts（Lit 组件）import 它做计算；
 * - 测试脚本可以单独 transpile 后在 Node 里跑随机对照（见 scripts 下的验证）。
 *
 * 算法选型： Myers 的 O(ND) 差分 + 线性空间精化（divide & conquer 找中间蛇形路径）。
 * 为什么不写简单版的 DP LCS：
 * - DP 是 O(N*M) 时间 + O(N*M) 空间，两份各 1 万行且完全不同的文本就要 1 亿个单元格，
 *   浏览器里直接把标签页卡死；
 * - 带 trace 数组的回退版 Myers 空间是 O(D²)，D（编辑距离）在"两份完全不同的长文本"时
 *   会退化成 N+M，同样爆内存。
 * 线性空间版：时间 O(ND)（相似文本 D 很小，远快于 DP），空间 O(N+M)，任意输入都不会炸。
 * 参考：Eugene W. Myers, "An O(ND) Difference Algorithm and Its Variations", 1986。
 *
 * 另外每一层递归都先剥公共前缀/后缀：真实场景里绝大部分行是相同的，
 * 剥完之后中间那一段通常很短，常见对比基本是线性速度。
 */

/** 一个编辑操作：对 a[start,end) 与 b[start,end) 施加的动作。 */
export interface DiffOp {
  type: 'equal' | 'del' | 'ins';
  aStart: number;
  aEnd: number;
  bStart: number;
  bEnd: number;
}

/** 按行切分文本：兼容 CRLF / CR / LF；行尾留空行（与 git diff 的行为一致）。 */
export function splitLines(text: string): string[] {
  return text.split(/\r\n|\r|\n/);
}

/** 相邻同类且区间相接的 op 合并成一个，免得结果里出现一串碎块。 */
function pushOp(out: DiffOp[], type: DiffOp['type'], aStart: number, aEnd: number, bStart: number, bEnd: number): void {
  const last = out[out.length - 1];
  if (last && last.type === type && last.aEnd === aStart && last.bEnd === bStart) {
    last.aEnd = aEnd;
    last.bEnd = bEnd;
    return;
  }
  out.push({ type, aStart, aEnd, bStart, bEnd });
}

/**
 * 在子问题 [aStart,aEnd) × [bStart,bEnd) 上找"中间蛇形路径"。
 * 返回 [startX, startY, endX, endY]（绝对坐标）：蛇形从 (startX,startY) 沿对角线延伸到 (endX,endY)。
 * 找不到返回 null（对合法输入不会发生，调用方有兜底）。
 *
 * 正向搜索从 (0,0) 往 (N,M) 走，反向搜索从 (N,M) 往 (0,0) 走（反向坐标是"距终点的距离"）。
 * 当某一侧的对角线被另一侧覆盖时，最后一次延伸的对角线就是中间蛇形——
 * 它落在某条最优 D-路径上，于是原问题被切成两个互不影响的子问题。
 */
function findMiddleSnake(
  a: string[], b: string[],
  aStart: number, aEnd: number, bStart: number, bEnd: number,
): [number, number, number, number] | null {
  const N = aEnd - aStart;
  const M = bEnd - bStart;
  const delta = N - M;
  const odd = delta % 2 !== 0;
  const maxD = Math.ceil((N + M) / 2);
  // vf/vr 用对角线 k 直接索引；offset 把负对角线搬进非负区间。
  // offset+1 处的种子值对应论文里的 V[1]=0：D=0 时 k=0 只能从"上方"取 x=0，
  // 这是唯一一次读取，之后每一格都是真实值。
  const offset = maxD + 1;
  const size = 2 * maxD + 3;
  const vf = new Int32Array(size).fill(-1);
  const vr = new Int32Array(size).fill(-1);
  vf[offset + 1] = 0;
  vr[offset + 1] = 0;
  const A = (i: number): string => a[aStart + i];
  const B = (j: number): string => b[bStart + j];

  for (let d = 0; d <= maxD; d++) {
    // —— 正向：对角线 k 上能到达的最大 x ——
    for (let k = -d; k <= d; k += 2) {
      let x: number;
      if (k === -d || (k !== d && vf[offset + k - 1] < vf[offset + k + 1])) {
        x = vf[offset + k + 1]; // 从上一行的对角线下来（插入 b 的一行）
      } else {
        x = vf[offset + k - 1] + 1; // 从左边推过去（删掉 a 的一行）
      }
      let y = x - k;
      const xs = x;
      const ys = y;
      while (x < N && y < M && A(x) === B(y)) {
        x++;
        y++;
      }
      vf[offset + k] = x;
      // 重叠检测：delta 为奇时，反向才走到 d-1 步，只能和它已覆盖的对角线比
      if (odd && k >= delta - (d - 1) && k <= delta + (d - 1)) {
        if (vf[offset + k] + vr[offset + delta - k] >= N) {
          return [aStart + xs, bStart + ys, aStart + x, bStart + y];
        }
      }
    }
    // —— 反向：同一个递推，但从序列末尾往前 ——
    for (let k = -d; k <= d; k += 2) {
      let x: number;
      if (k === -d || (k !== d && vr[offset + k - 1] < vr[offset + k + 1])) {
        x = vr[offset + k + 1];
      } else {
        x = vr[offset + k - 1] + 1;
      }
      let y = x - k;
      const xs = x;
      const ys = y;
      while (x < N && y < M && A(N - x - 1) === B(M - y - 1)) {
        x++;
        y++;
      }
      vr[offset + k] = x;
      // 重叠检测：delta 为偶时，正向已走到 d 步
      if (!odd && k >= delta - d && k <= delta + d) {
        if (vr[offset + k] + vf[offset + delta - k] >= N) {
          // 反向坐标换算回正向坐标：蛇形起点是 (N-x, M-y)，终点是 (N-xs, M-ys)
          return [aStart + (N - x), bStart + (M - y), aStart + (N - xs), bStart + (M - ys)];
        }
      }
    }
  }
  return null;
}

/** 递归子问题：先剥公共头尾，再找中间蛇形对半切。 */
function diffRange(
  a: string[], b: string[],
  aStart: number, aEnd: number, bStart: number, bEnd: number,
  out: DiffOp[],
): void {
  // 剥下来的公共前缀/后缀本身就是 equal 段，必须回填，否则整段相同内容会丢
  // （典型受害者：a 与 b 完全相同时，头尾剥完两个区间都空了，一个 op 都不剩）。
  let prefix = 0;
  while (aStart < aEnd && bStart < bEnd && a[aStart] === b[bStart]) {
    aStart++;
    bStart++;
    prefix++;
  }
  let suffix = 0;
  while (aEnd > aStart && bEnd > bStart && a[aEnd - 1] === b[bEnd - 1]) {
    aEnd--;
    bEnd--;
    suffix++;
  }
  const emitSuffix = (): void => {
    if (suffix > 0) pushOp(out, 'equal', aEnd, aEnd + suffix, bEnd, bEnd + suffix);
  };
  if (prefix > 0) {
    pushOp(out, 'equal', aStart - prefix, aStart, bStart - prefix, bStart);
  }
  if (aStart === aEnd) {
    if (bStart < bEnd) pushOp(out, 'ins', aStart, aStart, bStart, bEnd);
    emitSuffix();
    return;
  }
  if (bStart === bEnd) {
    if (aStart < aEnd) pushOp(out, 'del', aStart, aEnd, bStart, bStart);
    emitSuffix();
    return;
  }
  const snake = findMiddleSnake(a, b, aStart, aEnd, bStart, bEnd);
  if (!snake) {
    // 理论上不可达（输入非空且已剥头尾时必然能找到蛇形）。兜底走全删全增，
    // 至少保证结果合法、不会无限递归。
    pushOp(out, 'del', aStart, aEnd, bStart, bStart);
    pushOp(out, 'ins', aStart, aStart, bStart, bEnd);
    emitSuffix();
    return;
  }
  const [sx, sy, ex, ey] = snake;
  diffRange(a, b, aStart, sx, bStart, sy, out);
  if (ex > sx) pushOp(out, 'equal', sx, ex, sy, ey);
  diffRange(a, b, ex, aEnd, ey, bEnd, out);
  emitSuffix();
}

/**
 * 计算两份行数组的差异，返回按顺序排列的编辑脚本。
 * 保证：按 op 顺序重放，equal 段两侧内容一致，del/ins 段拼起来正好是原序列。
 */
export function diffLines(a: string[], b: string[]): DiffOp[] {
  const out: DiffOp[] = [];
  diffRange(a, b, 0, a.length, 0, b.length, out);
  return out;
}

/** 统计新增/删除的行数（供结果区计数器用）。 */
export function countChanges(ops: DiffOp[]): { added: number; deleted: number } {
  let added = 0;
  let deleted = 0;
  for (const op of ops) {
    if (op.type === 'ins') added += op.bEnd - op.bStart;
    else if (op.type === 'del') deleted += op.aEnd - op.aStart;
  }
  return { added, deleted };
}
