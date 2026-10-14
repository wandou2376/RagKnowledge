import { createRouter, createWebHistory } from 'vue-router'

/*
 * 只有两个路由：对话是主界面，检索调试是排查用的独立页面。
 * 知识库管理和文档管理不再是页面，分别收进了边栏的弹窗和抽屉。
 */
const routes = [
  { path: '/', name: 'chat', component: () => import('../views/ChatView.vue') },
  {
    path: '/retrieval',
    name: 'retrieval',
    component: () => import('../views/RetrievalPlayground.vue')
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
