# Design System Master File

> **LOGIC:** When building a specific page, first check `design-system/pages/[page-name].md`.
> If that file exists, its rules **override** this Master file.
> If not, strictly follow the rules below.

---

**Project:** 语桥
**Generated:** 2026-09-23 15:28:25
**Category:** Banking/Traditional Finance

---

## Global Rules

### Color Palette

> **品牌覆盖（2026-09-23）**：以下 token 为用户品牌（蓝青渐变手语声波 logo
> #1666D6/#17D1E8/底#F6F8FB）派生并实测对比度后的**实际实现值**，取代 skill
> 数据库默认的 navy/gold 方案。实现见 `src/app/src/main/java/com/repovoyage/sign/ui/Theme.kt`
> （Material3 ColorScheme，浅色+深色双主题）。下表为浅色主题；深色 primary=#4FD8EE、
> surface=#0E1520，全部关键文本对比度 ≥4.5:1（浅色 ≥4.6:1、深色 ≥10:1 已校验）。

| Role | Hex (Light) | Compose token |
|------|-----|--------------|
| Primary | `#1259C0` | `lightColorScheme.primary`（logo 蓝加深，6.14:1） |
| On Primary | `#FFFFFF` | `onPrimary` |
| Primary Container | `#D9E6FF` | `primaryContainer` |
| Secondary | `#0E7C8C` | `secondary`（logo 青加深，4.61:1） |
| Secondary Container | `#C4F0F6` | `secondaryContainer`（草稿字幕卡） |
| Tertiary | `#44576E` | `tertiary`（待核对/分组标签） |
| Background / Surface | `#F6F8FB` | `background`/`surface`（logo 底色） |
| On Surface | `#101826` | `onSurface`（字幕正文 16.7:1） |
| Surface Container Low | `#FBFCFE` | `surfaceContainerLow`（卡片） |
| Error | `#BA1A1A` | `error` |

### Typography

- **Heading Font:** Lexend
- **Body Font:** Source Sans 3
- **Mood:** corporate, trustworthy, accessible, readable, professional, clean
- **Google Fonts:** [Lexend + Source Sans 3](https://fonts.googleapis.com/css2?family=Lexend:wght@300;400;500;600;700&family=Source+Sans+3:wght@300;400;500;600;700&display=swap)

**CSS Import:**
```css
@import url('https://fonts.googleapis.com/css2?family=Lexend:wght@300;400;500;600;700&family=Source+Sans+3:wght@300;400;500;600;700&display=swap');
```

### Spacing Variables

| Token | Value | Usage |
|-------|-------|-------|
| `--space-xs` | `4px` / `0.25rem` | Tight gaps |
| `--space-sm` | `8px` / `0.5rem` | Icon gaps, inline spacing |
| `--space-md` | `16px` / `1rem` | Standard padding |
| `--space-lg` | `24px` / `1.5rem` | Section padding |
| `--space-xl` | `32px` / `2rem` | Large gaps |
| `--space-2xl` | `48px` / `3rem` | Section margins |
| `--space-3xl` | `64px` / `4rem` | Hero padding |

### Shadow Depths

| Level | Value | Usage |
|-------|-------|-------|
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.05)` | Subtle lift |
| `--shadow-md` | `0 4px 6px rgba(0,0,0,0.1)` | Cards, buttons |
| `--shadow-lg` | `0 10px 15px rgba(0,0,0,0.1)` | Modals, dropdowns |
| `--shadow-xl` | `0 20px 25px rgba(0,0,0,0.15)` | Hero images, featured cards |

---

## Component Specs

### Buttons

```css
/* Primary Button */
.btn-primary {
  background: #A16207;
  color: white;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}

.btn-primary:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

/* Secondary Button */
.btn-secondary {
  background: transparent;
  color: #0F172A;
  border: 2px solid #0F172A;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}
```

### Cards

```css
.card {
  background: #F8FAFC;
  border-radius: 12px;
  padding: 24px;
  box-shadow: var(--shadow-md);
  transition: all 200ms ease;
  cursor: pointer;
}

.card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}
```

### Inputs

```css
.input {
  padding: 12px 16px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 16px;
  transition: border-color 200ms ease;
}

.input:focus {
  border-color: #0F172A;
  outline: none;
  box-shadow: 0 0 0 3px #0F172A20;
}
```

### Modals

```css
.modal-overlay {
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
}

.modal {
  background: white;
  border-radius: 16px;
  padding: 32px;
  box-shadow: var(--shadow-xl);
  max-width: 500px;
  width: 90%;
}
```

---

## Style Guidelines

**Style:** Minimalism & Swiss Style

**Keywords:** Clean, simple, spacious, functional, white space, high contrast, geometric, sans-serif, grid-based, essential

**Best For:** Enterprise apps, dashboards, documentation sites, SaaS platforms, professional tools

**Key Effects:** Subtle hover (200-250ms), smooth transitions, sharp shadows if any, clear type hierarchy, fast loading

### Page Pattern

**Pattern Name:** Trust & Authority + Conversion

- **Conversion Strategy:** Security badges. Case studies. Transparent pricing. Low-friction form. Provide pause/stop and stop the logo carousel on focus, hover, and reduced motion. Previous/next controls provide the keyboard equivalent; pause offscreen/hidden and render a static logo set under reduced motion.
- **CTA Placement:** Contact Sales / Get Quote (primary) + Nav
- **Section Order:** Hero (mission/credibility) > Proof (logos, certs, stats) > Solution overview > Clear CTA path

---

## Anti-Patterns (Do NOT Use)

- ❌ Playful design
- ❌ Poor security UX
- ❌ AI purple/pink gradients

### Additional Forbidden Patterns

- ❌ **Emojis as icons** — Use SVG icons (Heroicons, Lucide, Simple Icons)
- ❌ **Missing cursor:pointer** — All clickable elements must have cursor:pointer
- ❌ **Layout-shifting hovers** — Avoid scale transforms that shift layout
- ❌ **Low contrast text** — Maintain 4.5:1 minimum contrast ratio
- ❌ **Instant state changes** — Always use transitions (150-300ms)
- ❌ **Invisible focus states** — Focus states must be visible for a11y

---

## 语桥 App 设计落地（frontend-design 二遍法，2026-09-23）

> 本节记录重构后的**实际实现决策**（`src/app/src/main/java/com/repovoyage/sign/ui/`），
> 与上表 token 一并作为后续界面改动的基准。

### 结构装置：时间轨（Timeline Rail）

- 字幕流每行左侧 3dp 竖向状态色条（`TimelineRow`）：草稿=secondary 青、
  待核对=tertiary 蓝灰、已确认=outlineVariant 浅灰。**状态用颜色+位置双通道编码**，
  不单靠颜色（无障碍）。
- chrome 全部退为平面层：发丝线 `HorizontalDivider` 分区（会话控制台/翻译控制/
  字幕流/训练入口），**无卡片套件、无阴影、无渐变背景**。
- 唯一圆角留给可交互 pill（Button/FilterChip）；圆角不用于纯展示容器。

### 排版：字幕是 hero

- `YuqiaoType.subtitle` = 22sp/30sp/字距 0.3sp/Medium（远距离可读，全屏最大字号）；
  `subtitleDraft` = 18sp/26sp；chrome 标签 ≥12sp（labelSmall 下限红线）。
- CJK 不 bundling 显示字体（包体控制）；个性由**尺度/字重对比**承载
  （字幕 SemiBold vs 状态行 labelSmall）。
- 每屏只有一个 boldly-styled 元素：主界面=字幕行；设置/历史=分区标题（primary 色）。

### 文案

- 空态给方向而非道歉：「点上方「开始翻译」，识别到的句子会按新到旧落在这里」。
- 状态行合并信息源：`识别源｜当前模型`，一行 labelSmall，不占独立卡片。
- 重打提示=临时行（8dp tertiary 圆点+文字，6s 自动消失），不是待核实标志、不震动。

### 反模板自查结论（第二遍）

- 已避免：SaaS 卡片套件、cream+serif、全大写 eyebrow、居中 hero 三件套、
  渐变按钮、emoji 图标（底栏用 vector icon）。
- 保留的克制项：无动画（字幕流即时更新；后续如需入场动效 ≤300ms 且尊重
  reduced-motion）。

---

## Pre-Delivery Checklist

Before delivering any UI code, verify:

- [ ] No emojis used as icons (use SVG instead)
- [ ] All icons from consistent icon set (Heroicons/Lucide)
- [ ] `cursor-pointer` on all clickable elements
- [ ] Hover states with smooth transitions (150-300ms)
- [ ] Light mode: text contrast 4.5:1 minimum
- [ ] Focus states visible for keyboard navigation
- [ ] `prefers-reduced-motion` respected
- [ ] Responsive: 375px, 768px, 1024px, 1440px
- [ ] No content hidden behind fixed navbars
- [ ] No horizontal scroll on mobile
