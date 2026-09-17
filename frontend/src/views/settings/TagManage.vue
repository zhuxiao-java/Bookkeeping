<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tagApi, ApiError } from '@/api'
import { useDictStore } from '@/stores/dict'
import { transactionApi } from '@/api'
import type { Tag, Transaction } from '@/types/model'
import { parseTagIds } from '@/types/model'
import { bus, TAG_CHANGED, TRANSACTION_CHANGED } from '@/utils/bus'
import { COLOR_CHOICES, DISTINCT_COLORS } from '@/utils/constants'
import EmptyState from '@/components/EmptyState.vue'

/** 设置 → 标签管理（需求文档 4.6）：列表 + 新增/编辑/删除 + 使用统计（前端聚合） */
const dict = useDictStore()

const dialogVisible = ref(false)
const editing = ref<Tag | null>(null)
const saving = ref(false)
const form = reactive({ name: '', color: COLOR_CHOICES[0] })

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
  dialogVisible.value = true
}

function openEdit(tag: Tag) {
  editing.value = tag
  form.name = tag.name
  form.color = tag.color || COLOR_CHOICES[0]
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
      await tagApi.update({ name: form.name.trim(), color: form.color, id: editing.value.id })
      ElMessage.success('修改成功')
    } else {
      await tagApi.save({ name: form.name.trim(), color: form.color })
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
      <el-button type="primary" class="page-head__actions" @click="openCreate">+ 新增标签</el-button>
    </header>
    <section class="page-section" aria-labelledby="tag-list-heading">
      <div class="toolbar page-section__head">
        <h2 id="tag-list-heading" class="section-heading">我的标签</h2>
        <span class="card-hint">记账时可同时选择多个标签</span>
      </div>
      <div class="surface">
        <div v-for="tag in dict.tags" :key="tag.id" class="tag-row">
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
        <EmptyState v-if="!dict.tags.length" description="还没有标签，试着给交易加个标记" :size="104">
          <el-button type="primary" @click="openCreate">新增第一个标签</el-button>
        </EmptyState>
      </div>
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
          <el-input v-model="form.name" maxlength="10" show-word-limit placeholder="如：报销、出差" />
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
.tag-row { display: flex; align-items: center; gap: 16px; padding-block: 20px; }
.tag-row__copy { display: flex; flex-direction: column; flex: 1; min-width: 0; gap: 3px; }
.tag-row__name { font-size: 14px; font-weight: 550; overflow-wrap: anywhere; }
.tag-row__usage { font-size: 13px; color: var(--bk-text-secondary); }
.tag-row__actions { flex-shrink: 0; gap: 4px; }

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
</style>
