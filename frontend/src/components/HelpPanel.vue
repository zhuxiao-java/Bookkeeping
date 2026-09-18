<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Right, Search } from '@element-plus/icons-vue'
import EmptyState from './EmptyState.vue'
import {
  HELP_SECTIONS,
  blockSearchText,
  sectionSearchText,
  type HelpLink,
  type HelpSection,
  type HelpTable
} from '@/utils/helpContent'
import { bus, OPEN_QUICK_RECORD } from '@/utils/bus'
import { useSettingsStore } from '@/stores/settings'
import { useGuideStore } from '@/stores/guide'

/**
 * 使用说明面板：顶栏「?」抽屉与设置页「使用说明」菜单共用。
 * 内容与操作入口全部来自 utils/helpContent.ts，此处只负责渲染与检索。
 * 执行操作入口后 emit('action')，由外层决定是否关闭容器（抽屉需关闭，设置页可忽略）。
 */
const emit = defineEmits<{ (e: 'action'): void }>()

const router = useRouter()
const settings = useSettingsStore()
const guide = useGuideStore()

const keyword = ref('')
const activeNames = ref<string[]>(HELP_SECTIONS.map((s) => s.key))

/** 关键词过滤：章节标题命中则整章保留，否则只保留命中的条目 */
const filtered = computed<HelpSection[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return HELP_SECTIONS
  const result: HelpSection[] = []
  for (const section of HELP_SECTIONS) {
    if (!sectionSearchText(section).includes(kw)) continue
    const head = `${section.title} ${section.intro ?? ''}`.toLowerCase()
    const blocks = head.includes(kw)
      ? section.blocks
      : section.blocks.filter((b) => blockSearchText(b).includes(kw))
    result.push({ ...section, blocks })
  }
  return result
})

// 检索结果变化时自动展开命中章节；清空关键词后恢复全部展开
watch(filtered, (list) => {
  activeNames.value = list.map((s) => s.key)
})

/** 表格首列固定宽度，其余列按列数均分（CSS 变量供 grid-template-columns 的 repeat 使用） */
function tableStyle(table: HelpTable): Record<string, string> {
  return { '--help-cols': String(Math.max(1, table.columns.length - 1)) }
}

function run(link: HelpLink) {
  const { action } = link
  if (action.type === 'route') {
    // 已在目标页时 router 会拒绝重复导航，此处静默忽略
    router.push({ path: action.path, query: action.query }).catch(() => {})
  } else if (action.type === 'quick-record') {
    bus.emit(OPEN_QUICK_RECORD)
  } else if (action.type === 'guide') {
    guide.start(action.scope)
  } else if (action.type === 'guide-reset') {
    guide.resetAll()
    ElMessage.success('已重置引导记录，进入各页面时会重新提示')
    return
  } else {
    // 切换主题：不关面板，便于立即看到效果
    settings.toggleTheme()
    return
  }
  emit('action')
}
</script>

<template>
  <div class="help quiet-controls">
    <div class="help__bar">
      <el-input
        v-model="keyword"
        placeholder="搜索说明，如「预算」「快捷键」「背景」"
        aria-label="搜索使用说明"
        clearable
        :prefix-icon="Search"
      />
    </div>

    <el-collapse v-if="filtered.length" v-model="activeNames" class="help__collapse">
      <el-collapse-item v-for="s in filtered" :key="s.key" :name="s.key">
        <template #title>
          <span class="help__title">{{ s.title }}</span>
        </template>

        <p v-if="s.intro" class="help__intro">{{ s.intro }}</p>

        <div v-for="(b, bi) in s.blocks" :key="bi" class="help-block">
          <div v-if="b.title" class="help-block__title">{{ b.title }}</div>

          <div v-if="b.table" class="help-table" :style="tableStyle(b.table)">
            <div class="help-table__row help-table__head">
              <span v-for="c in b.table.columns" :key="c">{{ c }}</span>
            </div>
            <div v-for="(row, ri) in b.table.rows" :key="ri" class="help-table__row">
              <span v-for="(cell, ci) in row" :key="ci" :class="{ 'is-key': ci === 0 }">{{ cell }}</span>
            </div>
          </div>

          <ul v-if="b.lines?.length" class="help-block__lines">
            <li v-for="(line, li) in b.lines" :key="li">{{ line }}</li>
          </ul>

          <div v-if="b.links?.length" class="help-block__links">
            <el-button v-for="l in b.links" :key="l.label" text type="primary" size="small" @click="run(l)">
              {{ l.label }}
              <el-icon class="el-icon--right"><Right /></el-icon>
            </el-button>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <EmptyState v-else description="没有匹配的说明内容" :size="96" />
  </div>
</template>

<style scoped>
.help {
  container: help / inline-size;
  min-width: 0;
}

.help__bar {
  position: sticky;
  top: 0;
  z-index: 1;
  padding-bottom: var(--bk-gap);
  background: var(--bk-bg);
}

.help__title {
  font-size: 14px;
  font-weight: 550;
}

.help__intro {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.help-block {
  padding: 4px 0 var(--bk-row-padding);
}

.help-block + .help-block {
  border-top: 1px solid var(--bk-border-light);
  padding-top: var(--bk-row-padding);
}

.help-block__title {
  margin-bottom: 6px;
  font-size: 14px;
  font-weight: 600;
}

.help-block__lines {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.9;
}

.help-block__links {
  display: flex;
  flex-wrap: wrap;
  gap: var(--bk-action-gap);
  margin-top: 12px;
}

.help-table {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 13px;
  overflow: hidden;
}

.help-table__row {
  display: grid;
  grid-template-columns: minmax(100px, 0.8fr) repeat(var(--help-cols, 1), minmax(0, 1fr));
}

.help-table__row + .help-table__row {
  border-top: 1px solid var(--el-border-color-lighter);
}

.help-table__row > span {
  padding: 10px 12px;
  line-height: 1.7;
  min-width: 0;
  overflow-wrap: anywhere;
}

.help-table__head {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-weight: 600;
}

.help-table .is-key {
  color: var(--el-color-primary);
}
.help__collapse { border: 0; }
.help__collapse :deep(.el-collapse-item) {
  margin-bottom: var(--bk-gap);
  padding-inline: var(--bk-panel-padding);
  border-radius: var(--bk-radius-lg);
  background: var(--bk-surface);
}
.help__collapse :deep(.el-collapse-item:last-child) { margin-bottom: 0; }
.help__collapse :deep(.el-collapse-item__header),
.help__collapse :deep(.el-collapse-item__wrap) { background: transparent; border-bottom: 0; }
.help__collapse :deep(.el-collapse-item__header) { min-height: 0; height: auto; padding-block: var(--bk-row-padding); line-height: 1.6; }
.help__collapse :deep(.el-collapse-item__content) { padding-bottom: 0; }
.help-block__links :deep(.el-button) { margin-left: 0; white-space: normal; height: auto; min-height: var(--bk-control-height-small); padding-block: 6px; text-align: left; }
@container help (max-width: 420px) {
  .help-table__row { grid-template-columns: minmax(80px, 0.65fr) repeat(var(--help-cols, 1), minmax(0, 1fr)); }
  .help-table__row > span { padding: 8px; }
}
</style>
