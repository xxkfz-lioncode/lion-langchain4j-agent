<script>
import { useUserStore } from '@/stores/user'

export default {
  onLaunch() {
    // 已登录直接进入对话页; 未登录停在登录页(pages.json 首个页面)
    const user = useUserStore()
    if (user.token) {
      uni.reLaunch({ url: '/pages/chat/chat' })
    }
  }
}
</script>

<style>
page {
  background: #f0f2f5;
  font-size: 14px;
  color: #303133;
}
</style>
