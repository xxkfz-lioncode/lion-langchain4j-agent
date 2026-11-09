<template>
  <router-view />
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', Arial, sans-serif;
}

/* 侧边栏分组标题插槽的单根包裹节点(Layout.vue):
   与原 [icon, span] 平级布局视觉一致, 图标文字垂直居中 */
.el-menu .el-sub-menu__title .group-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>
