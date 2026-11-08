<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="订单号 / 下单用户 / 商品名称"
          clearable
          style="width: 220px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-input
          v-model="query.city"
          placeholder="下单城市"
          clearable
          style="width: 140px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-select v-model="query.status" placeholder="订单状态" clearable style="width: 140px" @change="load(1)">
          <el-option v-for="(label, value) in STATUS" :key="value" :label="label" :value="Number(value)" />
        </el-select>
        <el-button type="primary" @click="load(1)"><el-icon><Search /></el-icon>查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="orderNo" label="订单号" width="170" />
        <el-table-column prop="userName" label="下单用户" width="110" />
        <el-table-column prop="phone" label="联系电话" width="130">
          <!-- 展示层同步脱敏, 与问数结果的手机号规则一致 -->
          <template #default="{ row }">
            {{ maskPhone(row.phone) }}
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="商品名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="category" label="类目" width="110" />
        <el-table-column prop="amount" label="金额(元)" width="110" align="right">
          <template #default="{ row }">¥ {{ row.amount }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="70" align="right" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_TYPE[row.status] || 'info'">{{ STATUS[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="payChannel" label="支付渠道" width="100" />
        <el-table-column prop="city" label="城市" width="100" />
        <el-table-column prop="createdAt" label="下单时间" width="170" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { listOrders } from '@/api/text2sql'

const STATUS = { 1: '待付款', 2: '已付款', 3: '已发货', 4: '已完成', 5: '已取消' }
const STATUS_TYPE = { 1: 'warning', 2: 'primary', 3: 'primary', 4: 'success', 5: 'info' }

const query = reactive({ keyword: '', city: '', status: null })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await listOrders({
      keyword: query.keyword || undefined,
      city: query.city || undefined,
      status: query.status ?? undefined,
      page: page.value,
      size: size.value
    })
    // axios 拦截器已解包 R 信封, res 即 PageResult
    rows.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function maskPhone(phone) {
  if (!phone || phone.length < 7) return phone
  return phone.slice(0, 3) + '****' + phone.slice(-4)
}

onMounted(() => load(1))
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; padding-top: 10px; }
</style>
