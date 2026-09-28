<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { tagApi, ApiError } from '@/api'
import { useDictStore } from '@/stores/dict'
import { transactionApi } from '@/api'
import type { Tag, TagGroup, Transaction } from '@/types/model'
import { parseTagIds } from '@/types/model'
import { bus, TAG_CHANGED, TRANSACTION_CHANGED } from '@/utils/bus'
import { COLOR_CHOICES, DISTINCT_COLORS, TAG_GROUP_OPTIONS } from '@/utils/constants'
import { aggregateByBrand } from '@/utils/aggregate'
import EmptyState from '@/components/EmptyState.vue'

/** 设置 → 标签管理（需求文档 4.6）：列表 + 新增/编辑/删除 + 使用统计（前端聚合） */
const dict = useDictStore()

const dialogVisible = ref(false)
const editing = ref<Tag | null>(null)
const saving = ref(false)
const form = reactive({ name: '', color: COLOR_CHOICES[0], group: 'scene' as TagGroup })

/** 新增标签默认色：色池中第一个未被现有标签占用的（去重，替代原随机取色） */
function firstFreeTagColor(): string {
  const used = new Set(dict.tags.map((t) => (t.color || '').toLowerCase()))
  return COLOR_CHOICES.find((c) => !used.has(c.toLowerCase())) ?? DISTINCT_COLORS[0]
}

/** 标签使用次数（基于全量流水 tags 字段统计，过渡方案） */
const usageCount = computed(() => {
  const map = new Map<number, number>()
  for (const t of allTransactions.value) {
    for (const id of parseTagIds(t.tags)) {
      map.set(id, (map.get(id) ?? 0) + 1)
    }
  }
  return map
})

const allTransactions = ref<Transaction[]>([])

/** 品牌标签 / 场景标签分列展示，让各大品牌一目了然 */
const brandTags = computed(() => dict.tags.filter((t) => t.group === 'brand'))
const sceneTags = computed(() => dict.tags.filter((t) => t.group !== 'brand'))
/** 分组渲染列表：品牌在前、场景在后，空组不展示 */
const tagGroups = computed(() => [
  { key: 'brand', label: '品牌标签', list: brandTags.value },
  { key: 'scene', label: '场景标签', list: sceneTags.value }
])

/** 品牌消费榜：基于全量流水按品牌标签跨分类聚合（不限时间口径） */
const brandRanking = computed(() => aggregateByBrand(allTransactions.value, dict.tags))
/** 榜首金额，作为条形宽度基准 */
const brandMax = computed(() => (brandRanking.value.length ? brandRanking.value[0].amount : 0))
function barWidth(amount: number): string {
  const max = brandMax.value
  return max > 0 ? `${Math.max(4, Math.round((amount / max) * 100))}%` : '0%'
}

async function loadUsage() {
  try {
    allTransactions.value = await transactionApi.selectAll()
  } catch {
    allTransactions.value = []
  }
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.color = firstFreeTagColor()
  form.group = 'scene'
  dialogVisible.value = true
}

function openEdit(tag: Tag) {
  editing.value = tag
  form.name = tag.name
  form.color = tag.color || COLOR_CHOICES[0]
  form.group = tag.group ?? 'scene'
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入标签名称')
    return
  }
  if (form.name.trim().length > 10) {
    ElMessage.warning('标签名称不能超过 10 个字符')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await tagApi.update({ ...editing.value, name: form.name.trim(), color: form.color, group: form.group })
      ElMessage.success('修改成功')
    } else {
      await tagApi.save({ name: form.name.trim(), color: form.color, group: form.group })
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    bus.emit(TAG_CHANGED)
    await dict.refreshTags()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'S0809') {
      ElMessage.info('保存失败，请检查内容后重试')
    }
  } finally {
    saving.value = false
  }
}

async function remove(tag: Tag) {
  try {
    await ElMessageBox.confirm(
      `删除标签「${tag.name}」后，历史流水中的该标签将不再显示，确定删除吗？`,
      '删除标签',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    await tagApi.remove(tag.id)
    ElMessage.success('删除成功')
    bus.emit(TAG_CHANGED)
    await dict.refreshTags()
  } catch {
    // 拦截器已提示
  }
}

function onChanged() {
  dict.refreshTags()
  loadUsage()
}

onMounted(() => {
  dict.loadAll()
  loadUsage()
  bus.on(TAG_CHANGED, onChanged)
  bus.on(TRANSACTION_CHANGED, loadUsage)
})

onBeforeUnmount(() => {
  bus.off(TAG_CHANGED, onChanged)
  bus.off(TRANSACTION_CHANGED, loadUsage)
})
</script>

<template>
  <div class="page page--comfortable page--settings tag-manage">
    <header class="page-head page-head--actions">
      <div class="row-copy">
        <h1 class="page-head__title">标签管理</h1>
        <p class="page-head__sub">为交易加个标记，报销、旅行和日常都能轻松找到。</p>
      </div>
      <el-button type="primary" :icon="Plus" class="page-head__actions" @click="openCreate">新增标签</el-button>
    </header>
    <section v-if="brandRanking.length" class="page-section" aria-labelledby="brand-rank-heading">
      <div class="toolbar page-section__head">
        <h2 id="brand-rank-heading" class="section-heading">品牌消费榜</h2>
        <span class="card-hint">跨分类汇总各品牌支出，一目了然</span>
      </div>
      <div class="surface brand-rank">
        <div v-for="(item, i) in brandRanking" :key="item.tagId" class="brand-rank__row">
          <span class="brand-rank__no">{{ i + 1 }}</span>
          <span class="color-dot" :style="{ background: item.color }" aria-hidden="true" />
          <span class="brand-rank__name">{{ item.name }}</span>
          <div class="brand-rank__bar" :aria-hidden="true">
            <div class="brand-rank__bar-fill" :style="{ width: barWidth(item.amount), background: item.color }" />
          </div>
          <span class="brand-rank__amount">￥{{ item.amount.toFixed(2) }}</span>
          <span class="brand-rank__count">{{ item.count }} 笔</span>
        </div>
      </div>
    </section>

    <section class="page-section" aria-labelledby="tag-list-heading">
      <div class="toolbar page-section__head">
        <h2 id="tag-list-heading" class="section-heading">我的标签</h2>
        <span class="card-hint">记账时可同时选择多个标签</span>
      </div>
      <div v-for="group in tagGroups" :key="group.key" class="tag-group">
        <template v-if="group.list.length">
          <h3 class="tag-group__title">{{ group.label }}<span class="tag-group__count">{{ group.list.length }}</span></h3>
          <div class="surface">
            <div v-for="tag in group.list" :key="tag.id" class="tag-row">
              <span class="color-dot" :style="{ background: tag.color || 'var(--bk-text-secondary)' }" aria-hidden="true" />
              <div class="tag-row__copy">
                <span class="tag-row__name">{{ tag.name }}</span>
                <span class="tag-row__usage">已用于 {{ usageCount.get(tag.id) ?? 0 }} 笔交易</span>
              </div>
              <div class="toolbar tag-row__actions">
                <el-button text type="primary" size="small" :aria-label="`编辑标签${tag.name}`" @click="openEdit(tag)">编辑</el-button>
                <el-button text type="danger" size="small" :aria-label="`删除标签${tag.name}`" @click="remove(tag)">删除</el-button>
              </div>
            </div>
          </div>
        </template>
      </div>
      <EmptyState v-if="!dict.tags.length" description="还没有标签，试着给交易加个标记" :size="104">
        <el-button type="primary" @click="openCreate">新增第一个标签</el-button>
      </EmptyState>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑标签' : '新增标签'"
      width="440px"
      class="bk-dialog quiet-controls"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="10" show-word-limit placeholder="如：报销、耐克" />
        </el-form-item>
        <el-form-item label="分组">
          <el-radio-group v-model="form.group">
            <el-radio v-for="opt in TAG_GROUP_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="颜色">
          <div class="color-grid">
            <button
              v-for="color in COLOR_CHOICES"
              type="button"
              :key="color"
              class="color-grid__item"
              :class="{ 'is-active': form.color === color }"
              :aria-label="`颜色 ${color}`"
              :aria-pressed="form.color === color"
              :style="{ background: color }"
              @click="form.color = color"
            />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tag-row { display: flex; align-items: center; gap: 16px; padding-block: var(--bk-row-padding); }
.tag-row__copy { display: flex; flex-direction: column; flex: 1; min-width: 0; gap: 3px; }
.tag-row__name { font-size: 14px; font-weight: 550; overflow-wrap: anywhere; }
.tag-row__usage { font-size: 13px; color: var(--bk-text-secondary); }
.tag-row__actions { flex-shrink: 0; gap: var(--bk-action-gap); }

.color-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  border-radius: 50%;
}

.color-grid {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.color-grid__item {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 50%;
  cursor: pointer;
  border: 2px solid transparent;
  transition: border-color 0.15s;
}

.color-grid__item.is-active {
  border-color: var(--el-text-color-primary);
  box-shadow: 0 0 0 2px var(--bk-surface);
}

.tag-group { margin-bottom: 20px; }
.tag-group:last-child { margin-bottom: 0; }
.tag-group__title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--bk-text-secondary);
}
.tag-group__count {
  min-width: 18px;
  padding: 0 6px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  color: var(--bk-text-secondary);
  background: var(--bk-surface-hover, rgba(144, 147, 153, 0.14));
}

.brand-rank__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-block: var(--bk-row-padding);
}
.brand-rank__no {
  width: 18px;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--bk-text-secondary);
  text-align: center;
}
.brand-rank__name {
  flex-shrink: 0;
  min-width: 64px;
  font-size: 14px;
  font-weight: 550;
}
.brand-rank__bar {
  flex: 1;
  min-width: 40px;
  height: 8px;
  border-radius: 4px;
  background: var(--bk-surface-hover, rgba(144, 147, 153, 0.14));
  overflow: hidden;
}
.brand-rank__bar-fill {
  height: 100%;
  border-radius: 4px;
  opacity: 0.85;
  transition: width 0.3s ease;
}
.brand-rank__amount {
  flex-shrink: 0;
  min-width: 88px;
  text-align: right;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.brand-rank__count {
  flex-shrink: 0;
  width: 48px;
  text-align: right;
  font-size: 12px;
  color: var(--bk-text-secondary);
}
</style>
