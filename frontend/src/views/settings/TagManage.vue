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
  <div class="tag-manage">
    <div class="tag-manage__toolbar">
      <span class="tag-manage__hint">标签用于标记交易，可在记账时多选</span>
      <div class="tag-manage__spacer" />
      <el-button type="primary" @click="openCreate">+ 新增标签</el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="dict.tags" style="width: 100%">
        <el-table-column label="标签" min-width="200">
          <template #default="{ row }">
            <span
              class="tag-chip"
              :style="{
                background: (row.color || '#909399') + '22',
                color: row.color || '#909399',
                borderColor: (row.color || '#909399') + '55'
              }"
            >
              {{ row.name }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="颜色" width="120">
          <template #default="{ row }">
            <span class="color-dot" :style="{ background: row.color || '#909399' }" />
          </template>
        </el-table-column>
        <el-table-column label="使用次数" width="120">
          <template #default="{ row }">{{ usageCount.get(row.id) ?? 0 }} 次</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无标签，点击右上角新增" />
        </template>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑标签' : '新增标签'"
      width="400px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="10" show-word-limit placeholder="如：报销、出差" />
        </el-form-item>
        <el-form-item label="颜色">
          <div class="color-grid">
            <span
              v-for="color in COLOR_CHOICES"
              :key="color"
              class="color-grid__item"
              :class="{ 'is-active': form.color === color }"
              :style="{ background: color }"
              @click="form.color = color"
            />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tag-manage__toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.tag-manage__hint {
  color: var(--bk-text-secondary);
  font-size: 13px;
}

.tag-manage__spacer {
  flex: 1;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border: 1px solid;
  border-radius: var(--bk-radius-pill);
  font-size: 13px;
}

.color-dot {
  display: inline-block;
  width: 18px;
  height: 18px;
  border-radius: 50%;
}

.color-grid {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.color-grid__item {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  cursor: pointer;
  border: 2px solid transparent;
  transition: all 0.15s;
}

.color-grid__item.is-active {
  border-color: var(--el-text-color-primary);
  transform: scale(1.12);
}
</style>
