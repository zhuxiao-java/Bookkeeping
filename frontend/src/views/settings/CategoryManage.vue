<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowUp, Plus } from '@element-plus/icons-vue'
import { categoryApi, ApiError } from '@/api'
import { useDictStore } from '@/stores/dict'
import type { ArchivedFlag, Category, CategoryType } from '@/types/model'
import { bus, CATEGORY_CHANGED } from '@/utils/bus'
import { CATEGORY_ICON_GROUPS, COLOR_CHOICES } from '@/utils/constants'
import { shadeColor } from '@/utils/chartTheme'
import CategoryDot from '@/components/CategoryDot.vue'

/** 设置 → 分类管理（需求文档 4.5）：树形列表 + 图标/颜色 + 排序 + 归档 */
const dict = useDictStore()
const route = useRoute()

/** 支持 ?type=income/expense 直达（记账弹窗空分类引导跳转用） */
const queryType = route.query.type as CategoryType | undefined
const activeTab = ref<CategoryType>(queryType === 'income' || queryType === 'expense' ? queryType : 'expense')
const expandedIds = ref<Set<number>>(new Set())

const dialogVisible = ref(false)
const editing = ref<Category | null>(null)
const parentCategory = ref<Category | null>(null)
const saving = ref(false)
const form = ref({
  name: '',
  type: 'expense' as CategoryType,
  parentId: undefined as number | undefined,
  icon: 'other',
  color: COLOR_CHOICES[0],
  sortOrder: 0,
  archived: 0 as ArchivedFlag
})

/** 当前 Tab 的一级分类（含归档，按 sortOrder） */
const rootCategories = computed(() =>
  dict.categories
    .filter((c) => c.parentId == null && c.type === activeTab.value)
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id)
)

const childrenOf = (id: number) =>
  dict.categories
    .filter((c) => c.parentId === id)
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id)

/** 分类最多三层（一级 / 二级 / 三级），第三层不可再新增子分类 */
const MAX_DEPTH = 3
const maxDepth = MAX_DEPTH

interface VisibleNode {
  category: Category
  /** 0=一级 1=二级 2=三级 */
  depth: number
}

/** 按当前 Tab 与展开状态扁平化出可见节点（含层级深度），供树形列表渲染 */
const visibleNodes = computed<VisibleNode[]>(() => {
  const out: VisibleNode[] = []
  const walk = (parentId: number | null, depth: number) => {
    const list = dict.categories
      .filter((c) => (parentId == null ? c.parentId == null : c.parentId === parentId) && c.type === activeTab.value)
      .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id - b.id)
    for (const c of list) {
      out.push({ category: c, depth })
      if (depth + 1 < MAX_DEPTH && expandedIds.value.has(c.id)) walk(c.id, depth + 1)
    }
  }
  walk(null, 0)
  return out
})

const iconGroups = CATEGORY_ICON_GROUPS

/** 指定收支类型下已被其他分类占用的颜色（排除 excludeId 自身）；颜色存库大小写可能不一致，统一小写比较 */
function occupiedColors(type: CategoryType, excludeId?: number): Set<string> {
  const set = new Set<string>()
  for (const c of dict.categories) {
    if (c.type !== type || c.id === excludeId) continue
    if (c.color) set.add(c.color.toLowerCase())
  }
  return set
}

/** 当前表单类型下已占用的颜色集（编辑时排除自身，自己的颜色仍可保留） */
const usedColors = computed(() => occupiedColors(form.value.type, editing.value?.id))

/** 色池中第一个未被占用的颜色（优先高区分度色）；全部占满时返回 undefined，由调用方兜底 */
function firstFreeColor(type: CategoryType, excludeId?: number) {
  const used = occupiedColors(type, excludeId)
  return COLOR_CHOICES.find((c) => !used.has(c.toLowerCase()))
}

/**
 * 子分类默认色：在父色基础上派生「同色系但明度不同」的变体（保留家族关联），
 * 取第一个未被同类型占用的；父色本身已被父分类占用，故子分类不会与父完全同色，
 * 饼图按子分类聚合时也能区分。全部变体撞色时回退到色池第一个可用色。
 */
function siblingShade(parentColor: string, type: CategoryType): string {
  if (!parentColor) return firstFreeColor(type) ?? COLOR_CHOICES[0]
  const used = occupiedColors(type)
  for (const p of [-16, 16, -32, 32, -48, 48]) {
    const c = shadeColor(parentColor, p)
    if (!used.has(c.toLowerCase())) return c
  }
  return firstFreeColor(type) ?? parentColor
}

/**
 * 颜色是否禁选：已被同类型其他分类占用且不是当前选中项。
 * 当前选中项不置灰（如子分类默认继承父分类颜色），避免「已选中却不可点」的矛盾状态
 */
function isColorDisabled(color: string) {
  if (color.toLowerCase() === form.value.color.toLowerCase()) return false
  return usedColors.value.has(color.toLowerCase())
}

// 切换收支类型后，若当前颜色在新类型下已被占用，自动换成第一个可用色
watch(
  () => form.value.type,
  () => {
    if (usedColors.value.has(form.value.color.toLowerCase())) {
      form.value.color = firstFreeColor(form.value.type, editing.value?.id) ?? form.value.color
    }
  }
)

/**
 * 类型字段可见性：新建一级分类可选；
 * 编辑时仅「无子分类的一级分类」允许改类型（子分类类型须跟随父分类，
 * 有子分类的一级分类改类型会造成父子类型不一致）
 */
const showTypeField = computed(() => {
  if (!editing.value) return !parentCategory.value
  return editing.value.parentId == null && childrenOf(editing.value.id).length === 0
})

const dialogTitle = computed(() => {
  if (editing.value) return '编辑分类'
  if (parentCategory.value) return `新增子分类（${parentCategory.value.name}）`
  // 标题直接标出归属类型，避免把收入分类误建到支出下
  return `新增分类（${form.value.type === 'income' ? '收入' : '支出'}）`
})

function toggleExpand(id: number) {
  const set = new Set(expandedIds.value)
  if (set.has(id)) set.delete(id)
  else set.add(id)
  expandedIds.value = set
}

/** 展开指定分类（幂等） */
function expand(id: number) {
  if (expandedIds.value.has(id)) return
  const set = new Set(expandedIds.value)
  set.add(id)
  expandedIds.value = set
}

/** 折叠指定分类（幂等） */
function collapse(id: number) {
  if (!expandedIds.value.has(id)) return
  const set = new Set(expandedIds.value)
  set.delete(id)
  expandedIds.value = set
}

function openCreate(parent?: Category) {
  editing.value = null
  parentCategory.value = parent ?? null
  const type = parent ? parent.type : activeTab.value
  form.value = {
    name: '',
    type,
    parentId: parent?.id,
    icon: parent ? parent.icon : 'other',
    // 子分类默认取父色的同族明度变体（可与兄弟/父区分）；新建一级分类默认选第一个未被占用的颜色
    color: parent ? siblingShade(parent.color, parent.type) : firstFreeColor(type) ?? COLOR_CHOICES[0],
    // 新增子分类默认排到末尾，避免都挤在 0 号位
    sortOrder: parent ? childrenOf(parent.id).length : 0,
    archived: 0
  }
  // 自动展开父分类，保存后能立即看到新增的子分类
  if (parent) expand(parent.id)
  dialogVisible.value = true
}

function openEdit(category: Category) {
  editing.value = category
  parentCategory.value = null
  form.value = {
    name: category.name,
    type: category.type,
    parentId: category.parentId ?? undefined,
    icon: category.icon || 'other',
    color: category.color || COLOR_CHOICES[0],
    sortOrder: category.sortOrder ?? 0,
    archived: category.archived
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }
  if (form.value.name.trim().length > 20) {
    ElMessage.warning('分类名称不能超过 20 个字符')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      type: form.value.type,
      parentId: form.value.parentId ?? null,
      icon: form.value.icon,
      color: form.value.color,
      sortOrder: form.value.sortOrder,
      archived: form.value.archived
    }
    if (editing.value) {
      await categoryApi.update({ ...payload, id: editing.value.id })
      ElMessage.success('修改成功')
    } else {
      await categoryApi.save(payload)
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    bus.emit(CATEGORY_CHANGED)
    await dict.refreshCategories()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'S0809') {
      ElMessage.info('保存失败，请检查内容后重试')
    }
  } finally {
    saving.value = false
  }
}

async function toggleArchive(category: Category) {
  const toArchived: ArchivedFlag = category.archived === 0 ? 1 : 0
  await categoryApi.update({ ...category, archived: toArchived })
  ElMessage.success(category.archived === 0 ? '已归档' : '已取消归档')
  bus.emit(CATEGORY_CHANGED)
  await dict.refreshCategories()
}

async function remove(category: Category) {
  if (childrenOf(category.id).length > 0) {
    ElMessage.warning('该分类下存在子分类，请先删除或移除子分类')
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除分类「${category.name}」吗？`, '删除分类', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await categoryApi.remove(category.id)
    ElMessage.success('删除成功')
    // 删完最后一个子分类后折叠父分类，避免残留空区块
    // 此时字典尚未刷新，列表里仍包含当前这条，故阈值为 1
    if (category.parentId != null && childrenOf(category.parentId).length <= 1) {
      collapse(category.parentId)
    }
    bus.emit(CATEGORY_CHANGED)
    await dict.refreshCategories()
  } catch (e) {
    // 分类被流水引用时外键约束导致删除失败
    if (e instanceof ApiError && e.code === 'S0813') {
      ElMessage.info('该分类已被交易记录使用，无法删除；可将其归档')
    }
  }
}

/** 上移/下移：与相邻一级分类交换 sortOrder */
async function move(category: Category, direction: -1 | 1) {
  const siblings = rootCategories.value
  const index = siblings.findIndex((c) => c.id === category.id)
  const targetIndex = index + direction
  if (targetIndex < 0 || targetIndex >= siblings.length) return
  const target = siblings[targetIndex]
  const a = category.sortOrder ?? index
  const b = target.sortOrder ?? targetIndex
  await Promise.all([
    categoryApi.update({ ...category, sortOrder: b }),
    categoryApi.update({ ...target, sortOrder: a })
  ])
  bus.emit(CATEGORY_CHANGED)
  await dict.refreshCategories()
}

function onChanged() {
  dict.refreshCategories()
}

onMounted(() => {
  dict.loadAll()
  bus.on(CATEGORY_CHANGED, onChanged)
})

onBeforeUnmount(() => {
  bus.off(CATEGORY_CHANGED, onChanged)
})
</script>

<template>
  <div class="cat-manage">
    <div class="cat-manage__toolbar">
      <el-radio-group v-model="activeTab">
        <el-radio-button value="expense">支出</el-radio-button>
        <el-radio-button value="income">收入</el-radio-button>
      </el-radio-group>
      <div class="cat-manage__spacer" />
      <el-button type="primary" @click="openCreate()">+ 新增分类</el-button>
    </div>

    <el-card shadow="never">
      <div v-if="visibleNodes.length" class="cat-tree">
        <div
          v-for="node in visibleNodes"
          :key="node.category.id"
          class="cat-row"
          :class="{ 'is-archived': node.category.archived === 1 }"
        >
          <div class="cat-row__main" :style="{ paddingLeft: `${4 + node.depth * 24}px` }">
            <el-button
              v-if="childrenOf(node.category.id).length && node.depth < maxDepth - 1"
              text
              circle
              size="small"
              @click="toggleExpand(node.category.id)"
            >
              <el-icon :class="{ 'is-expanded': expandedIds.has(node.category.id) }"><ArrowDown /></el-icon>
            </el-button>
            <span v-else class="cat-row__toggle-spacer" />
            <CategoryDot
              :name="node.category.name"
              :icon="node.category.icon"
              :color="node.category.color"
              :size="node.depth === 0 ? 32 : 24"
            />
            <span class="cat-row__name">{{ node.category.name }}</span>
            <el-tag v-if="node.category.archived === 1" size="small" type="info">已归档</el-tag>
            <span v-if="childrenOf(node.category.id).length" class="cat-row__count">
              {{ childrenOf(node.category.id).length }} 个子分类
            </span>
            <div class="cat-row__spacer" />
            <template v-if="node.depth === 0">
              <el-button text size="small" @click="move(node.category, -1)"><el-icon><ArrowUp /></el-icon></el-button>
              <el-button text size="small" @click="move(node.category, 1)"><el-icon><ArrowDown /></el-icon></el-button>
            </template>
            <el-button
              v-if="node.depth < maxDepth - 1"
              link
              type="primary"
              size="small"
              :disabled="node.category.archived === 1"
              @click="openCreate(node.category)"
            >
              <el-icon><Plus /></el-icon> 添加子分类
            </el-button>
            <el-button link type="primary" size="small" @click="openEdit(node.category)">编辑</el-button>
            <el-button link type="primary" size="small" @click="toggleArchive(node.category)">
              {{ node.category.archived === 0 ? '归档' : '恢复' }}
            </el-button>
            <el-button link type="danger" size="small" @click="remove(node.category)">删除</el-button>
          </div>
        </div>
      </div>

      <el-empty v-else description="暂无分类，点击右上角新增">
        <el-button type="primary" @click="openCreate()">新增第一个分类</el-button>
      </el-empty>
    </el-card>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="480px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="20" show-word-limit placeholder="如：餐饮、工资" />
        </el-form-item>
        <el-form-item v-if="showTypeField" label="类型" required>
          <el-radio-group v-model="form.type">
            <el-radio value="expense">支出</el-radio>
            <el-radio value="income">收入</el-radio>
          </el-radio-group>
          <div v-if="editing" class="cat-tip">修改后该分类将移到对应收支类型下，历史流水仍跟随该分类</div>
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-picker">
            <div v-for="g in iconGroups" :key="g.group" class="icon-group">
              <div class="icon-group__title">{{ g.group }}</div>
              <div class="icon-grid">
                <span
                  v-for="item in g.icons"
                  :key="item.name"
                  class="icon-grid__item"
                  :class="{ 'is-active': form.icon === item.name }"
                  @click="form.icon = item.name"
                >
                  {{ item.emoji }}
                </span>
              </div>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="颜色">
          <div class="color-grid">
            <span
              v-for="color in COLOR_CHOICES"
              :key="color"
              class="color-grid__item"
              :class="{ 'is-active': form.color === color, 'is-disabled': isColorDisabled(color) }"
              :style="{ background: color }"
              :title="isColorDisabled(color) ? '该颜色已被其他分类使用' : ''"
              @click="!isColorDisabled(color) && (form.color = color)"
            />
          </div>
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
        </el-form-item>
        <el-form-item v-if="editing" label="归档状态">
          <el-switch v-model="form.archived" :active-value="1" :inactive-value="0" active-text="已归档" inactive-text="正常" />
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
.cat-manage__toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.cat-manage__spacer {
  flex: 1;
}

.cat-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--bk-text-secondary);
}

.cat-row.is-archived {
  opacity: 0.6;
}

.cat-row__main {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.cat-row__toggle-spacer {
  width: 24px;
  flex: none;
}

.cat-row__name {
  font-weight: 600;
}

.cat-row__count {
  color: var(--bk-text-secondary);
  font-size: 12px;
}

.cat-row__spacer {
  flex: 1;
}

.is-expanded {
  transform: rotate(0deg);
}

/* 图标选择器：分组 + 限高滚动，容纳 100+ 图标不撑爆对话框 */
.icon-picker {
  width: 100%;
  max-height: 240px;
  overflow-y: auto;
  padding-right: 4px;
}

.icon-group__title {
  font-size: 12px;
  color: var(--bk-text-secondary);
  margin: 8px 0 4px;
  line-height: 1;
}

.icon-group:first-child .icon-group__title {
  margin-top: 0;
}

.icon-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 6px;
  width: 100%;
}

.icon-grid__item {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--bk-radius-md);
  cursor: pointer;
  font-size: 20px;
  transition: all 0.15s;
}

.icon-grid__item:hover {
  border-color: var(--el-color-primary-light-5);
}

.icon-grid__item.is-active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
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

/* 已被其他分类占用的颜色：置灰禁选 */
.color-grid__item.is-disabled {
  cursor: not-allowed;
  opacity: 0.25;
}
</style>
