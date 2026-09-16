<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { QuestionFilled } from '@element-plus/icons-vue'
import HelpPanel from '@/components/HelpPanel.vue'

/**
 * 顶栏「使用说明」入口：问号按钮 + 右侧抽屉。
 * 面板内执行跳转、记一笔等操作后会 emit('action')，此处顺势关闭抽屉，避免遮挡目标页面。
 */
const route = useRoute()
const visible = ref(false)

// 换页时自动收起：避免抽屉残留遮挡新页面内容与新手引导
watch(
  () => route.path,
  () => {
    visible.value = false
  }
)
</script>

<template>
  <div class="help-entry">
    <el-tooltip content="使用说明" placement="bottom">
      <el-button circle data-guide="help" @click="visible = true">
        <el-icon><QuestionFilled /></el-icon>
      </el-button>
    </el-tooltip>

    <el-drawer v-model="visible" size="560px">
      <template #header>
        <span class="help-entry__title">使用说明</span>
      </template>

      <HelpPanel @action="visible = false" />
    </el-drawer>
  </div>
</template>

<style scoped>
.help-entry {
  display: inline-flex;
  align-items: center;
}

.help-entry__title {
  font-size: 16px;
  font-weight: 600;
}
</style>
