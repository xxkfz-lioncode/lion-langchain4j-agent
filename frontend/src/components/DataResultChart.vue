<template>
  <div class="dq-chart">
    <!-- 单列单行: 大数字指标卡 -->
    <div v-if="type === 'METRIC'" class="metric-box">
      <div class="metric-value">{{ metricText }}</div>
      <div v-if="columns[0]" class="metric-label">{{ columns[0].name }}</div>
    </div>

    <!-- 明细/空结果: 手动切到"图表"时给出解释, 而不是渲染一块空白 -->
    <div v-else-if="type === 'TABLE' || type === 'EMPTY'" class="no-chart">
      <el-empty :image-size="70"
        :description="type === 'EMPTY'
          ? '查询结果为空, 无内容可展示'
          : '该结果是明细数据(列较多或单列清单), 适合用「表格」查看; 想看图表可以换个聚合口径提问, 如「各城市订单金额总和」'" />
    </div>

    <div v-else ref="el" class="chart-el" :style="{ height: chartHeight + 'px' }"></div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  type: { type: String, default: 'TABLE' },
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] }
})

const el = ref(null)
let chart = null

const metricText = computed(() => {
  const v = props.rows[0]?.[0]
  return v === null || v === undefined ? '-' : String(v)
})

const chartHeight = computed(() => (props.rows.length > 8 ? 260 : 220))

function buildOption() {
  const cols = props.columns || []
  const rows = props.rows || []
  const xName = cols[0]?.name || ''
  const yNames = cols.slice(1).map((c) => c.name)

  if (props.type === 'PIE') {
    return {
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll' },
      series: [
        {
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '46%'],
          data: rows.map((r) => ({ name: String(r[0]), value: Number(r[1]) || 0 }))
        }
      ]
    }
  }

  const isLine = props.type === 'LINE'
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 52, right: 20, top: 24, bottom: 48 },
    legend: yNames.length > 1 ? { bottom: 0, type: 'scroll' } : undefined,
    xAxis: {
      type: 'category',
      data: rows.map((r) => String(r[0])),
      axisLabel: { rotate: rows.length > 6 ? 30 : 0, hideOverlap: false }
    },
    yAxis: { type: 'value', name: yNames[0] || '' },
    series: yNames.map((name, idx) => ({
      name,
      type: isLine ? 'line' : 'bar',
      smooth: isLine,
      areaStyle: isLine ? { opacity: 0.12 } : undefined,
      data: rows.map((r) => Number(r[idx + 1]) || 0)
    }))
  }
}

async function render() {
  if (props.type === 'METRIC' || props.type === 'TABLE' || !el.value) return
  await nextTick()
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  chart.setOption(buildOption(), true)
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})

watch(() => [props.type, props.columns, props.rows], render, { deep: true })
</script>

<style scoped>
.dq-chart {
  width: 100%;
}

.metric-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 22px 12px;
  background: linear-gradient(135deg, #f4f7fd 0%, #eef2fb 100%);
  border-radius: 8px;
  border: 1px solid #e3eaf7;
}

.metric-value {
  font-size: 32px;
  font-weight: 700;
  color: #2d4a8f;
  line-height: 1.1;
  word-break: break-all;
}

.metric-label {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.no-chart {
  padding: 8px 0;
}

.chart-el {
  width: 100%;
}
</style>
