<template>
  <div class="sent-page">
    <!-- ==================== 顶部 Banner ==================== -->
    <section class="sent-banner">
      <div class="banner-text">
        <h2 class="banner-title">
          <el-icon><MagicStick /></el-icon>
          情感分析 · 让机器读懂情绪
        </h2>
        <p class="banner-sub">
          基于 LangChain4j 的文本情感分类能力, 提供客户反馈、社交媒体、聊天机器人
          三个常见业务场景的一键分析。
        </p>
      </div>

      <div class="banner-scenes">
        <div
          v-for="s in scenarios"
          :key="s.key"
          class="scene-card"
          :class="'scene-' + s.key"
        >
          <div class="scene-icon">
            <el-icon><component :is="sceneIcon(s.icon)" /></el-icon>
          </div>
          <div class="scene-body">
            <div class="scene-title">{{ s.title }}</div>
            <div class="scene-desc">{{ s.desc }}</div>
          </div>
          <el-icon class="scene-arrow"><ArrowRight /></el-icon>
        </div>
      </div>
    </section>

    <!-- ==================== 三场景 Tab ==================== -->
    <section class="sent-tabs-card">
      <el-tabs v-model="activeTab" class="sent-tabs">
        <!-- ============= Tab 1: 客户反馈分析 ============= -->
        <el-tab-pane name="feedback">
          <template #label>
            <span class="tab-label-inner">
              <el-icon><ChatLineSquare /></el-icon>
              客户反馈分析
            </span>
          </template>

          <div class="tab-grid">
            <!-- 左: 输入区 -->
            <div class="input-card">
              <div class="input-header">
                <span class="input-title">批量评论</span>
                <span class="input-counter">共 {{ fbTexts.length }} 条</span>
              </div>
              <el-input
                v-model="fbRaw"
                type="textarea"
                :rows="12"
                resize="none"
                placeholder="每行一条评论, 例:&#10;东西收到了, 还行&#10;质量很好, 非常满意&#10;发货太慢了, 不会再来"
                class="fb-textarea"
              />
              <div class="input-actions">
                <el-button size="small" :disabled="fbLoading" @click="fillFBSample">
                  <el-icon class="btn-icon"><DocumentCopy /></el-icon>示例评论
                </el-button>
                <el-button
                  size="small"
                  type="primary"
                  :loading="fbLoading"
                  :disabled="!fbTexts.length"
                  @click="runFeedback"
                >
                  开始分析
                </el-button>
              </div>
            </div>

            <!-- 右: 结果区 -->
            <div class="result-card">
              <template v-if="fbResult">
                <!-- 分布 -->
                <div class="result-section">
                  <div class="section-title">情感分布</div>
                  <div class="dist-grid">
                    <div class="dist-cell pos">
                      <div class="dist-num">{{ fbResult.distribution.POSITIVE }}</div>
                      <div class="dist-label">积极 · POSITIVE</div>
                      <el-progress
                        :percentage="fbResult.positiveRate"
                        :stroke-width="8"
                        color="#67c23a"
                        :show-text="false"
                        class="dist-bar"
                      />
                      <div class="dist-pct">{{ fbResult.positiveRate }}%</div>
                    </div>
                    <div class="dist-cell neu">
                      <div class="dist-num">{{ fbResult.distribution.NEUTRAL }}</div>
                      <div class="dist-label">中性 · NEUTRAL</div>
                      <el-progress
                        :percentage="neutralRate"
                        :stroke-width="8"
                        color="#909399"
                        :show-text="false"
                        class="dist-bar"
                      />
                      <div class="dist-pct">{{ neutralRate }}%</div>
                    </div>
                    <div class="dist-cell neg">
                      <div class="dist-num">{{ fbResult.distribution.NEGATIVE }}</div>
                      <div class="dist-label">消极 · NEGATIVE</div>
                      <el-progress
                        :percentage="fbResult.negativeRate"
                        :stroke-width="8"
                        color="#f56c6c"
                        :show-text="false"
                        class="dist-bar"
                      />
                      <div class="dist-pct">{{ fbResult.negativeRate }}%</div>
                    </div>
                  </div>
                </div>

                <!-- 关键词 -->
                <div class="result-section">
                  <div class="section-title">高频关键词</div>
                  <div class="kw-block">
                    <div class="kw-label">
                      <el-icon class="kw-icon pos-icon"><CircleCheckFilled /></el-icon>
                      Top 表扬
                    </div>
                    <div class="kw-chips">
                      <el-tag
                        v-for="k in fbResult.topPositive"
                        :key="'p' + k.word"
                        type="success"
                        effect="light"
                        round
                      >
                        {{ k.word }} × {{ k.count }}
                      </el-tag>
                      <span v-if="!fbResult.topPositive.length" class="kw-empty">暂无</span>
                    </div>
                  </div>
                  <div class="kw-block">
                    <div class="kw-label">
                      <el-icon class="kw-icon neg-icon"><WarningFilled /></el-icon>
                      Top 抱怨
                    </div>
                    <div class="kw-chips">
                      <el-tag
                        v-for="k in fbResult.topNegative"
                        :key="'n' + k.word"
                        type="danger"
                        effect="light"
                        round
                      >
                        {{ k.word }} × {{ k.count }}
                      </el-tag>
                      <span v-if="!fbResult.topNegative.length" class="kw-empty">暂无</span>
                    </div>
                  </div>
                </div>

                <!-- 样例列表 -->
                <div class="result-section">
                  <div class="section-title">分类明细</div>
                  <div class="sample-list">
                    <div
                      v-for="(s, idx) in fbResult.samples"
                      :key="idx"
                      class="sample-row"
                    >
                      <span class="sample-idx">#{{ idx + 1 }}</span>
                      <span class="sample-text">{{ s.text }}</span>
                      <el-tag
                        :type="tagType(s.tag)"
                        effect="dark"
                        size="small"
                        class="sample-tag"
                      >
                        {{ tagLabel(s.tag) }}
                      </el-tag>
                    </div>
                  </div>
                </div>
              </template>
              <el-empty
                v-else
                description="粘贴评论后点击「开始分析」"
                :image-size="100"
              />
            </div>
          </div>
        </el-tab-pane>

        <!-- ============= Tab 2: 社交媒体监控 ============= -->
        <el-tab-pane name="social">
          <template #label>
            <span class="tab-label-inner">
              <el-icon><View /></el-icon>
              社交媒体监控
            </span>
          </template>

          <div class="tab-grid">
            <div class="input-card">
              <div class="input-header">
                <span class="input-title">单条社交文本</span>
              </div>
              <div class="form-row">
                <span class="form-label">平台</span>
                <el-select v-model="social.platform" placeholder="选择平台" size="small" class="form-control">
                  <el-option v-for="p in platforms" :key="p" :label="p" :value="p" />
                </el-select>
              </div>
              <el-input
                v-model="social.text"
                type="textarea"
                :rows="8"
                resize="none"
                placeholder="粘贴一条社媒评论, 例: 今天收到的快递包装破损, 真的太失望了"
                class="fb-textarea"
              />
              <div class="input-actions">
                <el-button size="small" :disabled="socialLoading" @click="fillSocialSample">
                  <el-icon class="btn-icon"><DocumentCopy /></el-icon>示例
                </el-button>
                <el-button
                  size="small"
                  type="primary"
                  :loading="socialLoading"
                  :disabled="!social.text.trim()"
                  @click="runSocial"
                >
                  实时分析
                </el-button>
              </div>
            </div>

            <div class="result-card">
              <template v-if="socialResult">
                <div class="social-result-head">
                  <el-tag
                    :type="tagType(socialResult.sentiment)"
                    effect="dark"
                    size="large"
                    class="social-tag"
                  >
                    {{ tagLabel(socialResult.sentiment) }}
                  </el-tag>
                  <el-tag
                    v-if="socialResult.severity"
                    :type="severityType(socialResult.severity)"
                    effect="plain"
                    size="large"
                  >
                    严重度 · {{ severityLabel(socialResult.severity) }}
                  </el-tag>
                  <el-tag
                    v-if="socialResult.platform"
                    effect="plain"
                    size="large"
                    type="info"
                  >
                    {{ socialResult.platform }}
                  </el-tag>
                </div>

                <div class="social-section">
                  <div class="section-title">原文</div>
                  <div class="social-text">{{ socialResult.text }}</div>
                </div>

                <div v-if="socialResult.keywords?.length" class="social-section">
                  <div class="section-title">命中关键词</div>
                  <div class="kw-chips">
                    <el-tag
                      v-for="k in socialResult.keywords"
                      :key="k"
                      type="danger"
                      effect="light"
                      round
                    >
                      {{ k }}
                    </el-tag>
                  </div>
                </div>

                <div class="social-section">
                  <div class="section-title">运营建议</div>
                  <div class="social-advice" :class="'advice-' + (socialResult.sentiment || 'NEUTRAL').toLowerCase()">
                    {{ adviceForSocial(socialResult) }}
                  </div>
                </div>
              </template>
              <el-empty
                v-else
                description="输入一条社媒文本后点击「实时分析」"
                :image-size="100"
              />
            </div>
          </div>
        </el-tab-pane>

        <!-- ============= Tab 3: 聊天机器人响应 ============= -->
        <el-tab-pane name="chat">
          <template #label>
            <span class="tab-label-inner">
              <el-icon><Service /></el-icon>
              聊天机器人响应
            </span>
          </template>

          <div class="tab-grid">
            <div class="input-card">
              <div class="input-header">
                <span class="input-title">对话上下文</span>
                <el-button size="small" :disabled="chatLoading" @click="addChatTurn">
                  <el-icon class="btn-icon"><Plus /></el-icon>新增一轮
                </el-button>
              </div>
              <div class="chat-history">
                <div
                  v-for="(t, idx) in chatHistory"
                  :key="idx"
                  class="chat-turn"
                >
                  <el-select v-model="t.role" size="small" class="turn-role">
                    <el-option label="用户" value="user" />
                    <el-option label="客服" value="assistant" />
                  </el-select>
                  <el-input
                    v-model="t.content"
                    size="small"
                    placeholder="本轮对话内容"
                  />
                  <el-icon class="turn-del" @click="chatHistory.splice(idx, 1)"><Close /></el-icon>
                </div>
                <div v-if="!chatHistory.length" class="chat-empty">暂无对话历史, 可点击「新增一轮」添加</div>
              </div>

              <div class="input-header" style="margin-top: 14px;">
                <span class="input-title">当前用户消息</span>
              </div>
              <el-input
                v-model="chatMsg"
                type="textarea"
                :rows="3"
                resize="none"
                placeholder="例: 这服务也太差劲了, 烦死了"
              />
              <div class="input-actions">
                <el-button size="small" :disabled="chatLoading" @click="fillChatSample">
                  <el-icon class="btn-icon"><DocumentCopy /></el-icon>示例
                </el-button>
                <el-button
                  size="small"
                  type="primary"
                  :loading="chatLoading"
                  :disabled="!chatMsg.trim()"
                  @click="runChat"
                >
                  生成回复建议
                </el-button>
              </div>
            </div>

            <div class="result-card">
              <template v-if="chatResult">
                <div class="chat-result-head">
                  <span class="chat-user-msg">用户: {{ chatResult.userMessage }}</span>
                </div>

                <div class="chat-bubble-row">
                  <div class="chat-avatar">AI</div>
                  <div class="chat-bubble">
                    <div class="bubble-meta">
                      <el-tag
                        :type="tagType(chatResult.sentiment)"
                        effect="dark"
                        size="small"
                      >
                        {{ tagLabel(chatResult.sentiment) }}
                      </el-tag>
                      <span class="bubble-tone">{{ chatResult.tone }}</span>
                    </div>
                    <div class="bubble-text">{{ chatResult.templateReply }}</div>
                  </div>
                </div>

                <div class="chat-section">
                  <div class="section-title">客服回复建议</div>
                  <ul class="chat-tip-list">
                    <li v-for="(t, i) in replyTips(chatResult)" :key="i">{{ t }}</li>
                  </ul>
                </div>
              </template>
              <el-empty
                v-else
                description="输入当前用户消息后点击「生成回复建议」"
                :image-size="100"
              />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, markRaw } from 'vue'
import { ElMessage } from 'element-plus'
import {
  MagicStick,
  ChatLineSquare,
  View,
  Service,
  DocumentCopy,
  CircleCheckFilled,
  WarningFilled,
  ArrowRight,
  Plus,
  Close,
  ChatLineRound
} from '@element-plus/icons-vue'
import {
  analyzeFeedback,
  analyzeSocial,
  chatReply,
  getScenarios
} from '@/api/sentiment'

// 顶部 banner 场景卡图标映射(后端只给名字, 前端组件映射成图标组件)
import {
  ChatLineSquare as FbIcon,
  View as SocialIcon,
  Service as ChatIcon
} from '@element-plus/icons-vue'

// ============== 状态 ==============
const activeTab = ref('feedback')

const scenarios = ref([])
const platforms = ['微博', '小红书', '抖音', 'B站', '知乎', '其他']

// Tab 1: 客户反馈
const fbRaw = ref('')
const fbLoading = ref(false)
const fbResult = ref(null)
const fbTexts = computed(() =>
  fbRaw.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
)
const neutralRate = computed(() => {
  if (!fbResult.value || !fbResult.value.total) return 0
  const r = (fbResult.value.distribution.NEUTRAL * 1000) / fbResult.value.total
  return Math.round(r) / 10
})

// Tab 2: 社交媒体
const social = ref({ text: '', platform: '微博' })
const socialLoading = ref(false)
const socialResult = ref(null)

// Tab 3: 聊天回复
const chatHistory = ref([
  { role: 'user', content: '你好, 我上周买的那个东西还没到' },
  { role: 'assistant', content: '您好, 请问您的订单号是? 我帮您查询一下' }
])
const chatMsg = ref('')
const chatLoading = ref(false)
const chatResult = ref(null)

// ============== 图标映射 ==============
function sceneIcon(name) {
  if (name === 'ChatLineSquare') return markRaw(FbIcon)
  if (name === 'View') return markRaw(SocialIcon)
  if (name === 'Service') return markRaw(ChatIcon)
  return markRaw(ChatLineRound)
}

// ============== 标签/严重度 ==============
function tagLabel(tag) {
  return tag === 'POSITIVE' ? '积极' : tag === 'NEGATIVE' ? '消极' : '中性'
}
function tagType(tag) {
  return tag === 'POSITIVE' ? 'success' : tag === 'NEGATIVE' ? 'danger' : 'info'
}
function severityType(s) {
  return s === 'high' ? 'danger' : s === 'medium' ? 'warning' : 'info'
}
function severityLabel(s) {
  return s === 'high' ? '高' : s === 'medium' ? '中' : '低'
}

// ============== Tab 1: 客户反馈分析 ==============
const FB_SAMPLE = [
  '东西收到了, 还行吧',
  '质量很好, 非常满意, 下次还会回购',
  '物流太慢了, 等了一周才到',
  '客服态度很好, 点赞',
  '和描述完全不符, 失望透顶',
  '一般般, 凑合用',
  '包装破损, 漏气, 差评',
  '超出预期, 物超所值, 推荐购买',
  '做工粗糙, 缝隙明显, 退货了',
  '颜色比图片好看, 挺满意'
]
function fillFBSample() {
  fbRaw.value = FB_SAMPLE.join('\n')
}
async function runFeedback() {
  const texts = fbTexts.value
  if (!texts.length) {
    ElMessage.warning('请先粘贴评论')
    return
  }
  fbLoading.value = true
  try {
    fbResult.value = await analyzeFeedback(texts)
  } catch (e) {
    // 拦截器已弹错误
  } finally {
    fbLoading.value = false
  }
}

// ============== Tab 2: 社交媒体监控 ==============
const SOCIAL_SAMPLES = [
  { platform: '微博', text: '今天收到的快递包装破损, 真的太失望了' },
  { platform: '小红书', text: '博主推荐的这款面膜真的超好用, 物超所值!' },
  { platform: '抖音', text: '还行吧, 价格差不多, 可以接受' },
  { platform: '微博', text: '假货无疑, 已经投诉, 这种商家必须差评' }
]
function fillSocialSample() {
  const s = SOCIAL_SAMPLES[Math.floor(Math.random() * SOCIAL_SAMPLES.length)]
  social.value.platform = s.platform
  social.value.text = s.text
}
async function runSocial() {
  const text = social.value.text.trim()
  if (!text) {
    ElMessage.warning('请输入社交文本')
    return
  }
  socialLoading.value = true
  try {
    socialResult.value = await analyzeSocial(text, social.value.platform)
  } catch (e) {
    // 拦截器已处理
  } finally {
    socialLoading.value = false
  }
}

function adviceForSocial(r) {
  if (!r) return ''
  if (r.sentiment === 'POSITIVE') return '可点赞 / 转发, 引导用户参与活动, 提升品牌好感度。'
  if (r.sentiment === 'NEGATIVE') {
    if (r.severity === 'high') return '⚠️ 建议立即介入, 私信用户致歉并提供补偿方案, 同步内部排查。'
    if (r.severity === 'medium') return '建议 24h 内回复致歉, 引导用户私信客服处理。'
    return '建议关注后续评论, 必要时回复致歉并提供联系方式。'
  }
  return '建议主动询问使用体验, 引导留下更具体的反馈。'
}

// ============== Tab 3: 聊天机器人回复 ==============
function addChatTurn() {
  chatHistory.value.push({ role: 'user', content: '' })
}
function fillChatSample() {
  chatMsg.value = '这服务也太差劲了, 烦死了, 我要投诉!'
}
async function runChat() {
  const msg = chatMsg.value.trim()
  if (!msg) {
    ElMessage.warning('请输入当前用户消息')
    return
  }
  chatLoading.value = true
  try {
    chatResult.value = await chatReply(
      msg,
      chatHistory.value.filter((t) => t.content && t.content.trim())
    )
  } catch (e) {
    // 拦截器已处理
  } finally {
    chatLoading.value = false
  }
}

function replyTips(r) {
  if (!r) return []
  if (r.sentiment === 'POSITIVE') {
    return [
      '保持热情、真诚的语气, 避免过度推销;',
      '感谢用户后可邀请评价 / 推荐, 沉淀口碑;',
      '可询问是否需要其他帮助, 把对话延续下去。'
    ]
  }
  if (r.sentiment === 'NEGATIVE') {
    return [
      '先致歉, 再询问具体问题, 不要急着解释或推卸;',
      '提供明确的解决方案与时效, 让用户感受到被重视;',
      '必要时主动升级到人工客服并提供工单号。'
    ]
  }
  return [
    '用开放式问题引导用户补充细节, 例如「能否具体描述一下?」;',
    '回复以中性、专业为基调, 避免承诺超出能力范围的结果;',
    '若用户迟迟无回应, 可主动总结对话内容确认理解一致。'
  ]
}

// ============== 初始化 ==============
onMounted(async () => {
  try {
    scenarios.value = (await getScenarios()) || []
    // 兜底: 接口失败时使用内置数据
    if (!scenarios.value.length) {
      scenarios.value = [
        { key: 'feedback', icon: 'ChatLineSquare', title: '客户反馈分析', desc: '将客户评论分类为积极、中性或消极' },
        { key: 'social', icon: 'View', title: '社交媒体监控', desc: '分析社交媒体评论中的情感趋势' },
        { key: 'chat', icon: 'Service', title: '聊天机器人响应', desc: '理解用户情感以提供更好的响应' }
      ]
    }
  } catch (e) {
    scenarios.value = [
      { key: 'feedback', icon: 'ChatLineSquare', title: '客户反馈分析', desc: '将客户评论分类为积极、中性或消极' },
      { key: 'social', icon: 'View', title: '社交媒体监控', desc: '分析社交媒体评论中的情感趋势' },
      { key: 'chat', icon: 'Service', title: '聊天机器人响应', desc: '理解用户情感以提供更好的响应' }
    ]
  }
})
</script>

<style scoped>
.sent-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

/* ============= 顶部 Banner ============= */
.sent-banner {
  position: relative;
  padding: 24px 28px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2d4a8f 0%, #3e6cc7 100%);
  color: #fff;
  display: flex;
  gap: 24px;
  align-items: stretch;
  overflow: hidden;
}
.sent-banner::after {
  content: '';
  position: absolute;
  right: -40px;
  top: -40px;
  width: 220px;
  height: 220px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  pointer-events: none;
}
.banner-text {
  flex-shrink: 0;
  max-width: 320px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.banner-title {
  margin: 0 0 10px;
  font-size: 22px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.banner-title .el-icon {
  font-size: 22px;
}
.banner-sub {
  margin: 0;
  font-size: 13px;
  color: #dbe6f8;
  line-height: 1.7;
}
.banner-scenes {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  position: relative;
  z-index: 1;
}
.scene-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(4px);
  transition: background 0.2s, transform 0.2s;
  cursor: pointer;
}
.scene-card:hover {
  background: rgba(255, 255, 255, 0.2);
  transform: translateY(-1px);
}
.scene-card.scene-feedback {
  border-left: 3px solid #67c23a;
}
.scene-card.scene-social {
  border-left: 3px solid #409eff;
}
.scene-card.scene-chat {
  border-left: 3px solid #e6a23c;
}
.scene-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.18);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 20px;
}
.scene-body {
  flex: 1;
  min-width: 0;
}
.scene-title {
  font-size: 14px;
  font-weight: 600;
}
.scene-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #dbe6f8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.scene-arrow {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.7);
  flex-shrink: 0;
}

/* ============= Tab 卡片 ============= */
.sent-tabs-card {
  flex: 1;
  min-height: 0;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.05);
  padding: 4px 16px 16px;
  display: flex;
  flex-direction: column;
}
.sent-tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.sent-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.sent-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
}
.tab-label-inner {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.tab-label-inner .el-icon {
  font-size: 15px;
}

/* ============= Tab 通用布局: 左输入 / 右结果 ============= */
.tab-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(360px, 1.3fr);
  gap: 16px;
  height: 100%;
}
.input-card,
.result-card {
  background: #fafbfc;
  border: 1px solid #e8eaee;
  border-radius: 8px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  min-height: 360px;
  overflow: auto;
}
.input-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.input-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.input-counter {
  font-size: 12px;
  color: #909399;
}
.fb-textarea {
  flex: 1;
}
.fb-textarea :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 13px;
}
.input-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.btn-icon {
  margin-right: 4px;
}

/* ============= 结果区: 分布 ============= */
.result-section {
  margin-bottom: 18px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid #2d4a8f;
}
.dist-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.dist-cell {
  background: #fff;
  border: 1px solid #e8eaee;
  border-radius: 8px;
  padding: 14px;
  text-align: center;
  position: relative;
  overflow: hidden;
}
.dist-cell::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 4px;
  height: 100%;
}
.dist-cell.pos::before {
  background: #67c23a;
}
.dist-cell.neu::before {
  background: #909399;
}
.dist-cell.neg::before {
  background: #f56c6c;
}
.dist-num {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  line-height: 1;
}
.dist-cell.pos .dist-num {
  color: #67c23a;
}
.dist-cell.neg .dist-num {
  color: #f56c6c;
}
.dist-cell.neu .dist-num {
  color: #909399;
}
.dist-label {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.dist-bar {
  margin-top: 8px;
}
.dist-pct {
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
  font-weight: 600;
}

/* ============= 结果区: 关键词 ============= */
.kw-block {
  margin-bottom: 10px;
}
.kw-label {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.pos-icon {
  color: #67c23a;
}
.neg-icon {
  color: #f56c6c;
}
.kw-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.kw-empty {
  font-size: 12px;
  color: #c0c4cc;
}

/* ============= 结果区: 样例列表 ============= */
.sample-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.sample-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #eef0f4;
}
.sample-idx {
  flex-shrink: 0;
  width: 32px;
  font-size: 12px;
  color: #909399;
}
.sample-text {
  flex: 1;
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sample-tag {
  flex-shrink: 0;
}

/* ============= Tab 2: 社交媒体 ============= */
.form-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.form-label {
  flex-shrink: 0;
  font-size: 12px;
  color: #606266;
  width: 36px;
}
.form-control {
  flex: 1;
}
.social-result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.social-tag {
  font-weight: 600;
}
.social-section {
  margin-bottom: 14px;
}
.social-text {
  background: #fff;
  border: 1px solid #e8eaee;
  border-left: 3px solid #2d6cdf;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
}
.social-advice {
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
  background: #fff;
  border: 1px solid #e8eaee;
}
.social-advice.advice-positive {
  background: #f0f9eb;
  border-color: #c2e7b0;
  color: #5aaf3f;
}
.social-advice.advice-negative {
  background: #fef0f0;
  border-color: #fbc4c4;
  color: #c45656;
}
.social-advice.advice-neutral {
  background: #f4f4f5;
  border-color: #d3d4d6;
  color: #606266;
}

/* ============= Tab 3: 聊天回复 ============= */
.chat-history {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
}
.chat-turn {
  display: flex;
  align-items: center;
  gap: 6px;
}
.turn-role {
  width: 80px;
  flex-shrink: 0;
}
.turn-del {
  flex-shrink: 0;
  color: #c0c4cc;
  cursor: pointer;
  font-size: 14px;
}
.turn-del:hover {
  color: #f56c6c;
}
.chat-empty {
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
  padding: 16px 0;
}

.chat-result-head {
  margin-bottom: 12px;
}
.chat-user-msg {
  font-size: 13px;
  color: #606266;
  background: #f4f4f5;
  padding: 4px 10px;
  border-radius: 6px;
}
.chat-bubble-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 18px;
}
.chat-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #2d4a8f;
  color: #fff;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.chat-bubble {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-top-left-radius: 2px;
  border-radius: 10px;
  padding: 10px 14px;
  max-width: 80%;
}
.bubble-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.bubble-tone {
  font-size: 12px;
  color: #909399;
}
.bubble-text {
  font-size: 13px;
  color: #303133;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.chat-section {
  margin-top: 14px;
}
.chat-tip-list {
  margin: 0;
  padding-left: 22px;
  font-size: 13px;
  color: #606266;
  line-height: 1.9;
}

/* ============= 响应式 ============= */
@media (max-width: 1100px) {
  .sent-banner {
    flex-direction: column;
  }
  .banner-text {
    max-width: none;
  }
  .banner-scenes {
    grid-template-columns: 1fr;
  }
  .tab-grid {
    grid-template-columns: 1fr;
  }
}
</style>