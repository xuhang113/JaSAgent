/** 启动控制台：挂上路由、Element Plus 和全局样式。 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles.css'

createApp(App).use(router).use(ElementPlus).mount('#app')
